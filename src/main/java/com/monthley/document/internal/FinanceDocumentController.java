package com.monthley.document.internal;

import com.monthley.shared.Access;
import com.monthley.shared.PageResponse;
import com.monthley.shared.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Finance Documents — cari, papar dan cetak semula dokumen kewangan.
 *
 * SATU skrin untuk semua jenis: invois, resit, nota debit, nota kredit.
 * Legacy mempunyai skrin yang sama dan SP menggunakannya setiap hari
 * untuk mencari dokumen dan mencetaknya semula.
 *
 * LAJUR 'Title' datang daripada TETAPAN SP, bukan document.title.
 * sp_document_setting.invoice_title ialah 'Invois' dan receipt_title
 * ialah 'RESIT' untuk SP0002. document.title ialah 'Invois M01' —
 * keterangan per-dokumen, bukan label jenis.
 *
 * Produk TIDAK dipaparkan dalam senarai. Invois yang tidak dipecah
 * mempunyai banyak baris dan satu lajur tidak boleh mewakilinya; butiran
 * ada dalam modal transaksi.
 */
@RestController
@RequestMapping("/api/v1/documents")
class FinanceDocumentController {

    @PersistenceContext
    private EntityManager em;

    record DocumentRow(
            Long id,
            String docNo,
            String title,        // label jenis daripada tetapan SP
            String docType,      // INVOICE / RECEIPT / DEBIT_NOTE / CREDIT_NOTE
            String accountNo,
            String issuedTo,
            LocalDate docDate,
            String period,
            String status,
            BigDecimal amount,
            String paymentRefNo,
            /**
             * PAID | PARTIAL | UNPAID | ACTIVE | CANCELLED (V45/V46).
             *
             * 'Aktif' pada invois tidak memberitahu apa-apa — SEMUA invois
             * aktif sampai dibatalkan. Yang SP mahu tahu ialah invois mana
             * belum dibayar.
             */
            String paymentStatus,
            BigDecimal paid,
            BigDecimal outstanding) {}

    record LineRow(
            String productCode,
            String description,
            BigDecimal quantity,
            BigDecimal taxAmount,
            BigDecimal amount,
            LocalDate periodStart,
            LocalDate periodEnd) {}

    @GetMapping
    @SuppressWarnings("unchecked")
    PageResponse<DocumentRow> search(
            @RequestParam(required = false) String docNo,
            @RequestParam(required = false) String account,
            @RequestParam(required = false) Long periodId,
            @RequestParam(required = false) String docType,
            @RequestParam(required = false) String paymentRefNo,
            @RequestParam(required = false) LocalDate issuedFrom,
            @RequestParam(required = false) LocalDate issuedTo,
            // Tapis ikut status bayaran dan produk — SP bertanya 'bayaran
            // produk mana yang belum masuk', bukan 'dokumen mana aktif'.
            @RequestParam(required = false) String paymentStatus,
            @RequestParam(required = false) Long productId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        Access.requireAnyRole("melihat dokumen kewangan", "SP_ADMIN", "CLERK");

        String where = """
            WHERE d.sp_code = :sp
              AND (:docNo IS NULL OR LOWER(d.doc_no) LIKE :docNo)
              AND (:acc IS NULL OR LOWER(a.account_no) LIKE :acc
                                OR LOWER(a.account_name) LIKE :acc
                                OR LOWER(COALESCE(a.billto_name,'')) LIKE :acc)
              AND (:periodId IS NULL OR d.period_id = :periodId)
              AND (:docType IS NULL OR d.doc_type = :docType)
              AND (:payRef IS NULL OR LOWER(COALESCE(d.payment_ref_no,'')) LIKE :payRef)
              AND (:from IS NULL OR d.doc_date >= :from)
              AND (:to   IS NULL OR d.doc_date <= :to)
              AND (:payStatus IS NULL OR ps.payment_status = :payStatus)
              AND (:productId IS NULL OR EXISTS (
                    SELECT 1 FROM financial_document_line l
                    WHERE l.document_id = d.id AND l.product_id = :productId
                      AND l.active = 1))
            """;

        Query countQ = em.createNativeQuery(
                "SELECT COUNT(*) FROM financial_document d "
                + "JOIN account a ON a.id = d.account_id "
                + "LEFT JOIN document_payment_status ps ON ps.document_id = d.id "
                + where);
        bind(countQ, docNo, account, periodId, docType, paymentRefNo, issuedFrom, issuedTo,
                paymentStatus, productId);
        long total = ((Number) countQ.getSingleResult()).longValue();

        Query q = em.createNativeQuery("""
                SELECT d.id, d.doc_no, d.doc_type,
                       a.account_no,
                       COALESCE(NULLIF(a.billto_name,''), a.account_name) AS issued_to,
                       d.doc_date,
                       COALESCE(fp.name_, '') AS period_name,
                       d.status,
                       d.amount + d.tax_amount AS amount,
                       COALESCE(d.payment_ref_no, '') AS pay_ref,
                       COALESCE(s.invoice_title, 'Invois')  AS inv_title,
                       COALESCE(s.receipt_title, 'Resit')   AS rcp_title,
                       COALESCE(ps.payment_status, 'ACTIVE') AS pay_status,
                       COALESCE(ps.paid, 0)                  AS paid,
                       COALESCE(ps.outstanding, 0)           AS outstanding
                FROM   financial_document d
                JOIN   account a ON a.id = d.account_id
                LEFT   JOIN fi_period fp ON fp.period_id = d.period_id
                LEFT   JOIN sp_document_setting s ON s.sp_code = d.sp_code
                LEFT   JOIN document_payment_status ps ON ps.document_id = d.id
                """ + where + """
                ORDER BY d.doc_date DESC, d.id DESC
                LIMIT :size OFFSET :offset
                """);
        bind(q, docNo, account, periodId, docType, paymentRefNo, issuedFrom, issuedTo,
                paymentStatus, productId);
        q.setParameter("size", size);
        q.setParameter("offset", page * size);

        List<Object[]> rows = q.getResultList();
        List<DocumentRow> items = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            String type = (String) r[2];
            items.add(new DocumentRow(
                    ((Number) r[0]).longValue(),
                    (String) r[1],
                    labelJenis(type, (String) r[10], (String) r[11]),
                    type,
                    (String) r[3],
                    (String) r[4],
                    tarikh(r[5]),
                    (String) r[6],
                    (String) r[7],
                    (BigDecimal) r[8],
                    (String) r[9],
                    (String) r[12],
                    (BigDecimal) r[13],
                    (BigDecimal) r[14]));
        }
        return new PageResponse<>(items, total, page, size);
    }

    /**
     * Baris dokumen — modal 'List of Transaction'.
     *
     * DUA BENTUK, kerana resit dan invois disusun berbeza:
     *
     *   invois / nota  -> financial_document_line (produk yang dicaj)
     *   resit          -> fi_allocation (invois yang dibayar)
     *
     * Resit TIADA baris dokumen. Percubaan pertama menyoal
     * financial_document_line untuk kedua-duanya, dan resit memaparkan
     * 'Tiada baris transaksi' walaupun ia membayar tiga invois.
     */
    @GetMapping("/{id}/lines")
    @SuppressWarnings("unchecked")
    List<LineRow> lines(@PathVariable Long id) {
        Access.requireAnyRole("melihat dokumen kewangan", "SP_ADMIN", "CLERK");

        String type = (String) em.createNativeQuery(
                "SELECT doc_type FROM financial_document WHERE id = :id AND sp_code = :sp")
                .setParameter("id", id).setParameter("sp", sp())
                .getResultList().stream().findFirst().orElse(null);
        if (type == null) {
            return List.of();
        }
        if ("RECEIPT".equals(type) || "CREDIT_NOTE".equals(type)) {
            return alokasi(id);
        }

        List<Object[]> rows = em.createNativeQuery("""
                SELECT COALESCE(p.code, '') AS kod,
                       COALESCE(p.name, l.description, d.title) AS keterangan,
                       l.quantity, l.tax_amount, l.amount + l.tax_amount,
                       l.period_start, l.period_end
                FROM   financial_document_line l
                JOIN   financial_document d ON d.id = l.document_id
                LEFT   JOIN product p ON p.id = l.product_id
                WHERE  d.id = :id AND d.sp_code = :sp AND l.active = 1
                ORDER  BY l.period_start, l.id
                """)
                .setParameter("id", id)
                .setParameter("sp", sp())
                .getResultList();

        List<LineRow> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            out.add(new LineRow(
                    (String) r[0], (String) r[1],
                    (BigDecimal) r[2], (BigDecimal) r[3], (BigDecimal) r[4],
                    tarikh(r[5]), tarikh(r[6])));
        }
        return out;
    }

    /**
     * Baris untuk dokumen KREDIT — invois yang dibayarnya.
     *
     * Guna VIEW account_allocation_match yang sama seperti sub-baris
     * penyata dan item resit PDF. Satu sumber, tiga penggunaan.
     */
    @SuppressWarnings("unchecked")
    private List<LineRow> alokasi(Long id) {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT m.debit_doc_no,
                       COALESCE(m.product_name, m.line_description, m.debit_title) AS keterangan,
                       m.amount, m.debit_period_start, m.debit_period_end
                FROM   account_allocation_match m
                WHERE  m.sp_code = :sp AND m.credit_document_id = :id
                ORDER  BY m.debit_period_start, m.debit_doc_no
                """)
                .setParameter("sp", sp())
                .setParameter("id", id)
                .getResultList();

        List<LineRow> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            out.add(new LineRow(
                    // Lajur 'Kod Produk' membawa NO. INVOIS untuk resit —
                    // itu yang kerani cari apabila menyemak resit.
                    (String) r[0],
                    (String) r[1],
                    java.math.BigDecimal.ONE,
                    java.math.BigDecimal.ZERO,
                    (java.math.BigDecimal) r[2],
                    tarikh(r[3]), tarikh(r[4])));
        }
        return out;
    }

    // ---------- bantuan ----------

    /**
     * Pemacu MySQL memulangkan LocalDate untuk lajur DATE, bukan
     * java.sql.Date. Cast terus mencampak ClassCastException semasa
     * larian, bukan semasa kompil.
     */
    private static LocalDate tarikh(Object v) {
        if (v == null) return null;
        if (v instanceof LocalDate d) return d;
        if (v instanceof java.sql.Date d) return d.toLocalDate();
        if (v instanceof java.util.Date d) {
            return new java.sql.Date(d.getTime()).toLocalDate();
        }
        return LocalDate.parse(v.toString());
    }

    private void bind(Query q, String docNo, String account, Long periodId,
                      String docType, String payRef, LocalDate from, LocalDate to,
                      String payStatus, Long productId) {
        q.setParameter("sp", sp());
        q.setParameter("docNo", like(docNo));
        q.setParameter("acc", like(account));
        q.setParameter("periodId", periodId);
        q.setParameter("docType", blankToNull(docType));
        q.setParameter("payRef", like(payRef));
        q.setParameter("from", from);
        q.setParameter("to", to);
        q.setParameter("payStatus", blankToNull(payStatus));
        q.setParameter("productId", productId);
    }

    private static String like(String v) {
        String s = blankToNull(v);
        return s == null ? null : "%" + s.toLowerCase() + "%";
    }

    private static String blankToNull(String v) {
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    /**
     * Label jenis daripada tetapan SP.
     *
     * Nota debit dan kredit tiada tetapan — ia pelarasan yang jarang dan
     * tiada medan untuknya pada skrin Tetapan (ADR 0012).
     */
    private static String labelJenis(String docType, String invTitle, String rcpTitle) {
        return switch (docType) {
            case "INVOICE"     -> invTitle;
            case "RECEIPT"     -> rcpTitle;
            case "DEBIT_NOTE"  -> "Nota Debit";
            case "CREDIT_NOTE" -> "Nota Kredit";
            default            -> docType;
        };
    }

    private String sp() {
        String sp = TenantContext.get();
        if (sp == null || sp.isBlank()) {
            throw new IllegalStateException("Header X-SP-Id diperlukan");
        }
        return sp;
    }
}
