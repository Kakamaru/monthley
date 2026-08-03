package com.monthley.statement.internal;

import com.monthley.shared.Access;
import com.monthley.shared.PageResponse;
import com.monthley.shared.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Lejar SP — setiap transaksi merentas semua akaun (V60).
 *
 * Penyata pelanggan menjawab "akaun ini terdiri daripada apa"; ini
 * menjawab soalan yang bertentangan, dan tandanya dicerminkan: invois
 * MENURUNKAN baki SP kerana caj telah dikeluarkan tetapi belum dikutip.
 */
@RestController
@RequestMapping("/api/v1/sp-ledger")
class SpLedgerController {

    @PersistenceContext
    private EntityManager em;

    record Row(String txnAt, String accountNo, String docType, String docNo,
               String item, String remarks, String period,
               BigDecimal amount, BigDecimal balance, boolean cancelled) {}

    /**
     * BAKI BERJALAN DIKIRA SEBELUM TAPISAN.
     *
     * Kerani yang menapis 'Receipt' mesti melihat baki SEBENAR pada
     * setiap baris, bukan baki yang seolah-olah hanya resit wujud.
     * Tetingkap berjalan atas semua baris SP; tapisan digunakan
     * selepasnya.
     *
     * Kosnya: menapis kepada satu bulan TIDAK menjadikannya lebih
     * pantas. Diterima buat masa ini — diukur dengan data sebenar
     * sebelum dioptimumkan.
     */
    @GetMapping
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    PageResponse<Row> ledger(
            @RequestParam(required = false) String docNo,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) String docType,
            @RequestParam(required = false) Long periodId,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        Access.requireAnyRole("melihat lejar SP", "SP_ADMIN", "CLERK", "VIEWER");

        String where = """
                WHERE (:docNo IS NULL OR t.doc_no LIKE :docNoLike)
                  AND (:productId IS NULL OR t.product_id = :productId)
                  AND (:docType IS NULL OR t.doc_type = :docType)
                  AND (:from IS NULL OR t.doc_date >= :from)
                  AND (:to IS NULL OR t.doc_date <= :to)
                  -- Tempoh LIPUTAN, bukan tarikh transaksi. Invois
                  -- dijana pada Ogos boleh meliputi Julai, dan SP yang
                  -- menyemak 'semua caj Julai' memerlukan yang pertama.
                  AND (:periodId IS NULL OR EXISTS (
                        SELECT 1 FROM fi_period f
                        WHERE f.period_id = :periodId
                          AND t.period_start >= f.start_dt
                          AND t.period_start <= f.end_dt))
                """;

        String inner = """
                SELECT l.*, a.account_no,
                       SUM(l.signed_amount) OVER (
                           ORDER BY l.txn_at, l.document_id, l.line_id
                       ) AS running
                FROM   sp_ledger_line l
                JOIN   account a ON a.id = l.account_id
                WHERE  l.sp_code = :sp
                """;

        var countQ = em.createNativeQuery(
                "SELECT COUNT(*) FROM (" + inner + ") t " + where);
        bind(countQ, docNo, productId, docType, periodId, from, to);
        long total = ((Number) countQ.getSingleResult()).longValue();

        var dataQ = em.createNativeQuery("""
                SELECT t.txn_at, t.account_no, t.doc_type, t.doc_no, t.item,
                       t.remarks, t.period_start, t.period_end,
                       t.amount, t.running, t.status
                FROM   (%s) t %s
                ORDER  BY t.txn_at DESC, t.document_id DESC, t.line_id DESC
                LIMIT  :size OFFSET :offset
                """.formatted(inner, where));
        bind(dataQ, docNo, productId, docType, periodId, from, to);
        dataQ.setParameter("size", size);
        dataQ.setParameter("offset", (long) page * size);

        List<Object[]> rows = dataQ.getResultList();
        List<Row> items = new ArrayList<>();
        for (Object[] r : rows) {
            items.add(new Row(
                    masa(r[0]),
                    (String) r[1], (String) r[2], (String) r[3],
                    (String) r[4], (String) r[5],
                    tempoh(r[6], r[7]),
                    (BigDecimal) r[8], (BigDecimal) r[9],
                    "CANCELLED".equals(r[10])));
        }
        return new PageResponse<>(items, total, page, size);
    }

    private void bind(jakarta.persistence.Query q, String docNo, Long productId,
                      String docType, Long periodId, LocalDate from, LocalDate to) {
        q.setParameter("sp", sp());
        q.setParameter("docNo", docNo == null || docNo.isBlank() ? null : docNo);
        q.setParameter("docNoLike", docNo == null ? "" : "%" + docNo.trim() + "%");
        q.setParameter("productId", productId);
        q.setParameter("docType", docType == null || docType.isBlank() ? null : docType);
        q.setParameter("periodId", periodId);
        q.setParameter("from", from);
        q.setParameter("to", to);
    }

    /**
     * Cap masa untuk paparan — '03/08/2026 04:38 PM'.
     *
     * toString() pada LocalDateTime memberi ISO dengan 'T' di tengah,
     * yang bocor ke skrin DAN ke CSV. Diformat di sini supaya kedua-dua
     * pembaca melihat perkara yang sama.
     */
    private static String masa(Object v) {
        if (v == null) return null;
        java.time.LocalDateTime t;
        if (v instanceof java.time.LocalDateTime d) t = d;
        else if (v instanceof java.sql.Timestamp ts) t = ts.toLocalDateTime();
        else return v.toString();
        return t.format(java.time.format.DateTimeFormatter
                .ofPattern("dd/MM/yyyy hh:mm a", java.util.Locale.ENGLISH));
    }

    /** Tempoh sebagai teks — 'July, 2026' atau julat. */
    private static String tempoh(Object mula, Object tamat) {
        if (mula == null) return null;
        LocalDate a = tarikh(mula);
        LocalDate b = tamat == null ? a : tarikh(tamat);
        String namaA = BULAN[a.getMonthValue() - 1] + " " + a.getYear();
        if (a.getYear() == b.getYear() && a.getMonthValue() == b.getMonthValue()) {
            return namaA;
        }
        return namaA + " - " + BULAN[b.getMonthValue() - 1] + " " + b.getYear();
    }

    private static final String[] BULAN = {
            "Januari", "Februari", "Mac", "April", "Mei", "Jun",
            "Julai", "Ogos", "September", "Oktober", "November", "Disember" };

    private static LocalDate tarikh(Object v) {
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
