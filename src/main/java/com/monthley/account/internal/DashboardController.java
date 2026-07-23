package com.monthley.account.internal;

import com.monthley.shared.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Panel Utama (dashboard SP) — statistik agregat untuk SP semasa.
 * Native query cross-table (payment, account, financial_document, fi_allocation).
 */
@RestController
@RequestMapping("/api/v1/dashboard")
class DashboardController {

    @PersistenceContext
    private EntityManager em;

    record Summary(
            BigDecimal collectedThisMonth,   // terkumpul bulan ni (resit)
            BigDecimal outstanding,          // tunggakan (baki semua akaun)
            long activeAccounts,             // akaun aktif
            long inactiveAccounts,           // akaun tak aktif
            long billsThisMonth) {}          // bil (invois) dijana bulan ni

    @GetMapping("/summary")
    Summary summary() {
        String sp = sp();

        // 1. Terkumpul bulan ni — SUM payment.amount, bulan semasa, tak cancel.
        BigDecimal collected = num(em.createNativeQuery("""
                SELECT COALESCE(SUM(amount), 0) FROM payment
                WHERE sp_code = :sp AND cancelled_at IS NULL
                  AND YEAR(payment_date) = YEAR(CURDATE())
                  AND MONTH(payment_date) = MONTH(CURDATE())
                """).setParameter("sp", sp).getSingleResult());

        // 2. Tunggakan — SUM(INVOICE + DEBIT_NOTE) - SUM(alokasi aktif) merentas SP.
        BigDecimal outstanding = num(em.createNativeQuery("""
                SELECT COALESCE(SUM(d.amount + d.tax_amount), 0)
                       - COALESCE((
                         SELECT SUM(a.amount) FROM fi_allocation a
                         WHERE a.sp_code = :sp AND a.status = 'ACTIVE'), 0)
                FROM financial_document d
                WHERE d.sp_code = :sp AND d.status <> 'CANCELLED'
                  AND d.doc_type IN ('INVOICE','DEBIT_NOTE')
                """).setParameter("sp", sp).getSingleResult());

        // 3. Akaun aktif + tak aktif.
        Object[] acc = (Object[]) em.createNativeQuery("""
                SELECT SUM(CASE WHEN status = 'ACTIVE' THEN 1 ELSE 0 END),
                       SUM(CASE WHEN status <> 'ACTIVE' THEN 1 ELSE 0 END)
                FROM account WHERE sp_code = :sp
                """).setParameter("sp", sp).getSingleResult();
        long active = acc[0] == null ? 0 : ((Number) acc[0]).longValue();
        long inactive = acc[1] == null ? 0 : ((Number) acc[1]).longValue();

        // 4. Bil dijana bulan ni — COUNT invois, bulan semasa.
        long bills = ((Number) em.createNativeQuery("""
                SELECT COUNT(*) FROM financial_document
                WHERE sp_code = :sp AND doc_type = 'INVOICE' AND status <> 'CANCELLED'
                  AND YEAR(doc_date) = YEAR(CURDATE())
                  AND MONTH(doc_date) = MONTH(CURDATE())
                """).setParameter("sp", sp).getSingleResult()).longValue();

        return new Summary(collected, outstanding, active, inactive, bills);
    }

    record ChartPoint(String month, BigDecimal amount) {}

    /** Kutipan 6 atau 12 bulan (SUM payment per bulan). */
    @GetMapping("/collection-chart")
    @SuppressWarnings("unchecked")
    List<ChartPoint> collectionChart(@RequestParam(defaultValue = "6") int months) {
        String sp = sp();
        int span = (months == 12) ? 12 : 6;

        List<Object[]> rows = em.createNativeQuery("""
                SELECT DATE_FORMAT(payment_date, '%Y-%m') AS bln,
                       COALESCE(SUM(amount), 0) AS amt
                FROM payment
                WHERE sp_code = :sp AND cancelled_at IS NULL
                  AND payment_date >= DATE_SUB(DATE_FORMAT(CURDATE(), '%Y-%m-01'), INTERVAL :span MONTH)
                GROUP BY bln ORDER BY bln ASC
                """)
                .setParameter("sp", sp)
                .setParameter("span", span - 1)
                .getResultList();

        List<ChartPoint> out = new ArrayList<>();
        for (Object[] r : rows) {
            out.add(new ChartPoint((String) r[0], num(r[1])));
        }
        return out;
    }

    // ---------- P2: Produk Utama ----------
    record MainProduct(String name, BigDecimal rate, String frequency,
                       long subscribers, long paid, long unpaid, int collectionRate) {}

    @GetMapping("/main-product")
    MainProduct mainProduct() {
        String sp = sp();
        // Produk utama (main_product=1, ambil pertama).
        List<Object[]> prods = em.createNativeQuery("""
                SELECT id, name, unit_rate, charge_frequency FROM product
                WHERE sp_code = :sp AND main_product = 1 AND status = 'ACTIVE'
                ORDER BY id LIMIT 1
                """).setParameter("sp", sp).getResultList();
        if (prods.isEmpty()) {
            return new MainProduct(null, BigDecimal.ZERO, null, 0, 0, 0, 0);
        }
        Object[] pr = prods.get(0);
        Long prodId = ((Number) pr[0]).longValue();
        String name = (String) pr[1];
        BigDecimal rate = num(pr[2]);
        String freq = (String) pr[3];

        // Berapa akaun langgan produk ni (subscription aktif).
        long subs = ((Number) em.createNativeQuery("""
                SELECT COUNT(DISTINCT account_id) FROM account_subscription
                WHERE sp_code = :sp AND product_id = :pid AND status = 'ACTIVE'
                """).setParameter("sp", sp).setParameter("pid", prodId).getSingleResult()).longValue();

        // Berapa dah bayar bulan ni — invois produk ni bulan semasa yg dah lunas (baki=0).
        long paid = ((Number) em.createNativeQuery("""
                SELECT COUNT(*) FROM financial_document d
                WHERE d.sp_code = :sp AND d.doc_type = 'INVOICE' AND d.status <> 'CANCELLED'
                  AND YEAR(d.doc_date) = YEAR(CURDATE()) AND MONTH(d.doc_date) = MONTH(CURDATE())
                  AND EXISTS (SELECT 1 FROM financial_document_line l
                              WHERE l.document_id = d.id AND l.product_id = :pid)
                  AND (d.amount + d.tax_amount) <= COALESCE(
                      (SELECT SUM(a.amount) FROM fi_allocation a
                       WHERE a.debit_document_id = d.id AND a.status = 'ACTIVE'), 0)
                """).setParameter("sp", sp).setParameter("pid", prodId).getSingleResult()).longValue();

        long unpaid = Math.max(0, subs - paid);
        int cRate = subs > 0 ? (int) Math.round(paid * 100.0 / subs) : 0;
        return new MainProduct(name, rate, freq, subs, paid, unpaid, cRate);
    }

    // ---------- P2: Transaksi Terkini ----------
    record RecentTxn(String name, String accountNo, BigDecimal amount, String date) {}

    @GetMapping("/recent-transactions")
    @SuppressWarnings("unchecked")
    List<RecentTxn> recentTransactions() {
        String sp = sp();
        List<Object[]> rows = em.createNativeQuery("""
                SELECT a.account_name, a.account_no, p.amount, p.payment_date
                FROM payment p JOIN account a ON p.payer_account_id = a.id
                WHERE p.sp_code = :sp AND p.cancelled_at IS NULL
                ORDER BY p.payment_date DESC, p.id DESC LIMIT 6
                """).setParameter("sp", sp).getResultList();
        List<RecentTxn> out = new ArrayList<>();
        for (Object[] r : rows) {
            out.add(new RecentTxn((String) r[0], (String) r[1], num(r[2]), r[3].toString()));
        }
        return out;
    }

    // ---------- P2: Tunggakan Perlu Tindakan ----------
    record ArrearRow(String name, String accountNo, BigDecimal outstanding) {}

    @GetMapping("/top-arrears")
    @SuppressWarnings("unchecked")
    List<ArrearRow> topArrears() {
        String sp = sp();
        // Baki per akaun = SUM(INVOICE+DEBIT_NOTE) - SUM(alokasi aktif), > 0, tertinggi.
        List<Object[]> rows = em.createNativeQuery("""
                SELECT a.account_name, a.account_no,
                       COALESCE(SUM(d.amount + d.tax_amount), 0)
                       - COALESCE((SELECT SUM(al.amount) FROM fi_allocation al
                                   WHERE al.debit_document_id IN (
                                     SELECT d2.id FROM financial_document d2
                                     WHERE d2.account_id = a.id AND d2.doc_type IN ('INVOICE','DEBIT_NOTE'))
                                   AND al.status = 'ACTIVE'), 0) AS baki
                FROM account a
                JOIN financial_document d ON d.account_id = a.id
                  AND d.doc_type IN ('INVOICE','DEBIT_NOTE') AND d.status <> 'CANCELLED'
                WHERE a.sp_code = :sp
                GROUP BY a.id, a.account_name, a.account_no
                HAVING baki > 0
                ORDER BY baki DESC LIMIT 6
                """).setParameter("sp", sp).getResultList();
        List<ArrearRow> out = new ArrayList<>();
        for (Object[] r : rows) {
            out.add(new ArrearRow((String) r[0], (String) r[1], num(r[2])));
        }
        return out;
    }

    // ---------- Kutipan Ikut Produk (ADR 0006 P7) ----------
    record ProductSlice(String name, BigDecimal amount, int pct) {}

    /**
     * Kutipan dipecah ikut produk — bulan semasa.
     *
     * Join pada {@code debit_document_line_id} (peringkat line), BUKAN
     * {@code debit_document_id}. Join lama menggandakan setiap alokasi dengan
     * setiap line invois: kutipan sebenar RM1,350 dilaporkan RM13,377.
     *
     * Alokasi peringkat dokumen (DEBIT_NOTE, tiada line) tidak muncul di sini
     * kerana ia tiada produk untuk dikaitkan.
     */
    @GetMapping("/collection-by-product")
    @SuppressWarnings("unchecked")
    List<ProductSlice> collectionByProduct() {
        String sp = sp();

        List<Object[]> rows = em.createNativeQuery("""
                SELECT pr.name, COALESCE(SUM(al.amount), 0) amt
                FROM fi_allocation al
                JOIN financial_document_line l ON al.debit_document_line_id = l.id
                JOIN product pr ON l.product_id = pr.id
                JOIN financial_document rcp ON al.credit_document_id = rcp.id
                WHERE al.sp_code = :sp AND al.status = 'ACTIVE'
                  AND rcp.doc_type = 'RECEIPT'
                  AND YEAR(rcp.doc_date) = YEAR(CURDATE())
                  AND MONTH(rcp.doc_date) = MONTH(CURDATE())
                GROUP BY pr.id, pr.name
                ORDER BY amt DESC LIMIT 6
                """).setParameter("sp", sp).getResultList();

        BigDecimal total = BigDecimal.ZERO;
        for (Object[] r : rows) total = total.add(num(r[1]));

        List<ProductSlice> out = new ArrayList<>();
        for (Object[] r : rows) {
            BigDecimal amt = num(r[1]);
            int pct = total.signum() > 0
                    ? amt.multiply(new BigDecimal(100))
                         .divide(total, 0, java.math.RoundingMode.HALF_UP).intValue()
                    : 0;
            out.add(new ProductSlice((String) r[0], amt, pct));
        }
        return out;
    }

    private static BigDecimal num(Object o) {
        return o == null ? BigDecimal.ZERO : new BigDecimal(o.toString());
    }

    private String sp() {
        String sp = TenantContext.get();
        if (sp == null || sp.isBlank()) throw new IllegalStateException("SP tak ditetapkan.");
        return sp;
    }
}
