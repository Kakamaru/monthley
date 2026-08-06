package com.monthley.statement.internal;

import com.monthley.shared.Access;
import com.monthley.shared.TenantContext;
import com.monthley.statement.api.InvoiceModel;
import com.monthley.statement.api.StatementRenderPort;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Cetak invois secara PUKAL.
 *
 * SP menjana bil untuk sebulan, kemudian mencetak semuanya sekali gus
 * untuk diedar. Legacy pernah mencetak 1,400 dalam satu PDF.
 *
 * Cetakan SATU invois hidup di skrin Dokumen Kewangan; ini untuk
 * cetakan pukal sahaja.
 */
@RestController
@RequestMapping("/api/v1/reports/print-invoice")
class InvoicePrintController {

    private final StatementService statements;
    private final StatementRenderPort renderer;

    @PersistenceContext
    private EntityManager em;

    InvoicePrintController(StatementService statements, StatementRenderPort renderer) {
        this.statements = statements;
        this.renderer = renderer;
    }

    record Row(long documentId, String docNo, String accountNo, String accountName,
               LocalDate docDate, String period, BigDecimal amount, String status) {}

    record Preview(int count, BigDecimal total, List<Row> rows) {}

    /**
     * Pratonton sebelum cetak.
     *
     * Mencetak 1,400 invois mengambil masa; SP patut tahu berapa banyak
     * yang akan keluar sebelum menekan butang.
     */
    @GetMapping
    @Transactional(readOnly = true)
    Preview senarai(@RequestParam int year, @RequestParam int month,
                    @RequestParam(required = false) Long categoryId,
                    @RequestParam(defaultValue = "false") boolean unpaidOnly) {
        Access.requireAnyRole("melihat invois untuk cetakan",
                "SP_ADMIN", "CLERK", "VIEWER");

        List<Object[]> rows = cari(year, month, categoryId, unpaidOnly);
        List<Row> items = new ArrayList<>();
        BigDecimal jumlah = BigDecimal.ZERO;

        for (Object[] r : rows) {
            BigDecimal amt = (BigDecimal) r[6];
            jumlah = jumlah.add(amt);
            items.add(new Row(((Number) r[0]).longValue(), (String) r[1],
                    (String) r[2], (String) r[3],
                    tarikh(r[4]),
                    (String) r[5], amt, (String) r[7]));
        }
        return new Preview(items.size(), jumlah, items);
    }

    @GetMapping(value = "/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @Transactional(readOnly = true)
    ResponseEntity<byte[]> pdf(@RequestParam int year, @RequestParam int month,
                               @RequestParam(required = false) Long categoryId,
                               @RequestParam(defaultValue = "false") boolean unpaidOnly) {
        Access.requireAnyRole("mencetak invois pukal", "SP_ADMIN", "CLERK", "VIEWER");

        List<Object[]> rows = cari(year, month, categoryId, unpaidOnly);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException(
                    "Tiada invois untuk kriteria ini.");
        }

        List<InvoiceModel> models = new ArrayList<>();
        for (Object[] r : rows) {
            models.add(statements.invoice(sp(), ((Number) r[0]).longValue()));
        }

        byte[] bytes = renderer.renderInvoiceBulkPdf(models);

        String nama = "invois-" + year + "-" + String.format("%02d", month) + ".pdf";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline()
                                .filename(nama, StandardCharsets.UTF_8)
                                .build().toString())
                .body(bytes);
    }

    /**
     * Invois bagi satu bulan.
     *
     * Ditapis mengikut TEMPOH LIPUTAN baris, bukan tarikh dokumen:
     * invois Ogos boleh meliputi Julai, dan SP yang mencetak 'bil Julai'
     * mahukan yang meliputi Julai.
     */
    @SuppressWarnings("unchecked")
    private List<Object[]> cari(int year, int month, Long categoryId, boolean unpaidOnly) {
        LocalDate from = LocalDate.of(year, month, 1);
        LocalDate to = from.withDayOfMonth(from.lengthOfMonth());

        return em.createNativeQuery("""
                SELECT d.id, d.doc_no, a.account_no,
                       COALESCE(NULLIF(a.billto_name,''), a.account_name),
                       d.doc_date,
                       DATE_FORMAT(l.period_start, '%M %Y'),
                       (d.amount + d.tax_amount),
                       d.status
                FROM   financial_document d
                JOIN   account a ON a.id = d.account_id
                JOIN   (SELECT document_id, MIN(period_start) AS period_start
                          FROM financial_document_line
                         WHERE active = 1 GROUP BY document_id) l
                       ON l.document_id = d.id
                WHERE  d.sp_code = :sp
                  AND  d.doc_type = 'INVOICE'
                  AND  d.status <> 'CANCELLED'
                  AND  l.period_start BETWEEN :from AND :to
                  AND  (:cat IS NULL OR a.category_id = :cat)
                  -- Belum lunas: jumlah melebihi apa yang telah dialokasikan.
                  AND  (:unpaid = 0 OR (d.amount + d.tax_amount)
                        > COALESCE((SELECT SUM(al.amount) FROM fi_allocation al
                                     WHERE al.debit_document_id = d.id
                                       AND al.status = 'ACTIVE'), 0) + 0.005)
                ORDER  BY a.account_no, d.doc_no
                """)
                .setParameter("sp", sp())
                .setParameter("from", from).setParameter("to", to)
                .setParameter("cat", categoryId)
                .setParameter("unpaid", unpaidOnly ? 1 : 0)
                .getResultList();
    }

    /**
     * Connector/J memulangkan LocalDate untuk lajur DATE, bukan
     * java.sql.Date — hantaran terus gagal pada masa jalan walaupun
     * kod dikompil.
     */
    private static LocalDate tarikh(Object v) {
        if (v == null) return null;
        if (v instanceof LocalDate d) return d;
        if (v instanceof java.sql.Date d) return d.toLocalDate();
        return LocalDate.parse(String.valueOf(v));
    }

    private String sp() {
        String sp = TenantContext.get();
        if (sp == null || sp.isBlank()) {
            throw new IllegalStateException("Header X-SP-Id diperlukan");
        }
        return sp;
    }
}
