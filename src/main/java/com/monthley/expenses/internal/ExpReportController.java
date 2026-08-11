package com.monthley.expenses.internal;

import com.monthley.shared.Access;
import com.monthley.shared.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Laporan modul Perbelanjaan.
 *
 * Semua laporan dibina daripada dokumen sumber (exp_invoice,
 * exp_payment, exp_cash_entry) dan BUKAN daripada lejar, walaupun
 * kedua-duanya mempos ke sana. Lejar tidak menyimpan pembekal, penerima,
 * atau jenis kategori — dan itulah yang menjadikan laporan ini berguna.
 *
 * Untung Rugi ialah laporan yang berbeza: ia datang dari lejar, kerana ia
 * mesti boleh dibuktikan seimbang.
 *
 * Baca sahaja — tiada ModuleGuard. Endpoint baca dibenarkan tanpa hak
 * modul (ADR 0016).
 */
@RestController
@RequestMapping("/api/v1/expenses/reports")
class ExpReportController {

    @PersistenceContext
    private EntityManager em;

    // ---------- Penyata perbelanjaan: kategori -> jenis ----------

    record ExpenseLine(String jenis, BigDecimal amount) {}
    record ExpenseGroup(String category, BigDecimal total, List<ExpenseLine> lines) {}
    record ExpenseReport(List<ExpenseGroup> groups, BigDecimal grandTotal) {}

    /**
     * Perbelanjaan dikumpul mengikut kategori induk, kemudian jenis.
     *
     * Menggabungkan invois pembekal dan bayaran terus: kedua-duanya
     * perbelanjaan, cuma satu melalui invois dan satu tidak. Memisahkannya
     * bermakna pengguna perlu menambah dua laporan secara manual.
     *
     * Invois dikira pada tarikh INVOIS, bukan tarikh bayar — perbelanjaan
     * berlaku apabila ia ditanggung, bukan apabila dibayar.
     */
    @GetMapping("/expense")
    @SuppressWarnings("unchecked")
    ExpenseReport expense(@RequestParam(required = false) LocalDate from,
                          @RequestParam(required = false) LocalDate to) {
        Access.requireAnyRole("melihat laporan perbelanjaan", "SP_ADMIN", "CLERK");

        List<Object[]> rows = em.createNativeQuery("""
                SELECT COALESCE(induk.name, jenis.name) AS kategori,
                       jenis.name AS jenis, SUM(x.amount) AS jumlah
                FROM (
                    SELECT it.category_id, it.amount
                    FROM   exp_invoice_item it
                    JOIN   exp_invoice i ON i.id = it.invoice_id
                    WHERE  i.sp_code = :sp AND i.status = 'ACTIVE'
                      AND (:from IS NULL OR i.inv_date >= :from)
                      AND (:to   IS NULL OR i.inv_date <= :to)
                    UNION ALL
                    SELECT e.category_id, e.amount
                    FROM   exp_cash_entry e
                    WHERE  e.sp_code = :sp AND e.status = 'ACTIVE'
                      AND (:from IS NULL OR e.entry_date >= :from)
                      AND (:to   IS NULL OR e.entry_date <= :to)
                ) x
                JOIN exp_category jenis ON jenis.id = x.category_id
                LEFT JOIN exp_category induk ON induk.id = jenis.parent_id
                GROUP BY COALESCE(induk.name, jenis.name), jenis.name
                ORDER BY COALESCE(induk.name, jenis.name), jenis.name
                """)
                .setParameter("sp", sp())
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();

        Map<String, List<ExpenseLine>> ikutKategori = new LinkedHashMap<>();
        Map<String, BigDecimal> jumlahKategori = new LinkedHashMap<>();
        BigDecimal grand = BigDecimal.ZERO;

        for (Object[] r : rows) {
            String kategori = r[0] == null ? "(tiada kategori)" : (String) r[0];
            String jenis = (String) r[1];
            BigDecimal amt = new BigDecimal(r[2].toString());

            ikutKategori.computeIfAbsent(kategori, k -> new ArrayList<>())
                    .add(new ExpenseLine(jenis, amt));
            jumlahKategori.merge(kategori, amt, BigDecimal::add);
            grand = grand.add(amt);
        }

        List<ExpenseGroup> groups = new ArrayList<>();
        ikutKategori.forEach((k, lines) ->
                groups.add(new ExpenseGroup(k, jumlahKategori.get(k), lines)));

        return new ExpenseReport(groups, grand);
    }

    // ---------- Perbelanjaan terperinci ----------

    record DetailRow(LocalDate date, String ref, String category, String jenis,
                     String note, String source, BigDecimal amount) {}
    record CategorySummary(String name, BigDecimal amount) {}
    record DetailReport(List<CategorySummary> summary, List<DetailRow> rows,
                        BigDecimal grandTotal) {}

    @GetMapping("/expense-detail")
    @SuppressWarnings("unchecked")
    DetailReport expenseDetail(@RequestParam(required = false) LocalDate from,
                               @RequestParam(required = false) LocalDate to) {
        Access.requireAnyRole("melihat laporan perbelanjaan", "SP_ADMIN", "CLERK");

        List<Object[]> rows = em.createNativeQuery("""
                SELECT x.dt, x.ref, COALESCE(induk.name, jenis.name) AS kategori,
                       jenis.name AS jenis, x.note, x.src, x.amount
                FROM (
                    SELECT i.inv_date AS dt, i.inv_no AS ref, it.category_id,
                           it.description AS note, 'INVOICE' AS src, it.amount
                    FROM   exp_invoice_item it
                    JOIN   exp_invoice i ON i.id = it.invoice_id
                    WHERE  i.sp_code = :sp AND i.status = 'ACTIVE'
                      AND (:from IS NULL OR i.inv_date >= :from)
                      AND (:to   IS NULL OR i.inv_date <= :to)
                    UNION ALL
                    SELECT e.entry_date, e.voucher_no, e.category_id,
                           CONCAT(e.payee, COALESCE(CONCAT(' — ', e.description), '')),
                           'TERUS', e.amount
                    FROM   exp_cash_entry e
                    WHERE  e.sp_code = :sp AND e.status = 'ACTIVE'
                      AND (:from IS NULL OR e.entry_date >= :from)
                      AND (:to   IS NULL OR e.entry_date <= :to)
                ) x
                JOIN exp_category jenis ON jenis.id = x.category_id
                LEFT JOIN exp_category induk ON induk.id = jenis.parent_id
                ORDER BY x.dt DESC, x.ref DESC
                """)
                .setParameter("sp", sp())
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();

        List<DetailRow> out = new ArrayList<>();
        Map<String, BigDecimal> ikutKategori = new LinkedHashMap<>();
        BigDecimal grand = BigDecimal.ZERO;

        for (Object[] r : rows) {
            BigDecimal amt = new BigDecimal(r[6].toString());
            String kategori = (String) r[2];
            out.add(new DetailRow(toDate(r[0]), (String) r[1], kategori,
                    (String) r[3], (String) r[4], (String) r[5], amt));
            ikutKategori.merge(kategori, amt, BigDecimal::add);
            grand = grand.add(amt);
        }

        List<CategorySummary> summary = new ArrayList<>();
        ikutKategori.forEach((k, v) -> summary.add(new CategorySummary(k, v)));

        return new DetailReport(summary, out, grand);
    }

    // ---------- Penuaan pembekal ----------

    record AgingRow(String supplier, BigDecimal current, BigDecimal d30,
                    BigDecimal d60, BigDecimal d90plus, BigDecimal total) {}
    record AgingTotals(BigDecimal current, BigDecimal d30, BigDecimal d60,
                       BigDecimal d90plus, BigDecimal total) {}
    record AgingReport(LocalDate asAt, List<AgingRow> rows, AgingTotals totals) {}

    /**
     * Penuaan dikira daripada tarikh TEMPOH, bukan tarikh invois.
     *
     * Invois bertarikh 1 Januari dengan tempoh 30 hari belum lewat pada
     * 15 Januari. Mengira dari tarikh invois memaparkan tunggakan yang
     * tidak wujud, dan pembekal dihubungi tanpa sebab.
     *
     * Invois tanpa tarikh tempoh dianggap SEMASA — tiada tarikh bermakna
     * tiada janji yang dilanggar.
     */
    @GetMapping("/aging")
    @SuppressWarnings("unchecked")
    AgingReport aging(@RequestParam(required = false) LocalDate asAt) {
        Access.requireAnyRole("melihat penuaan pembekal", "SP_ADMIN", "CLERK");

        LocalDate tarikh = asAt == null ? LocalDate.now() : asAt;

        List<Object[]> rows = em.createNativeQuery("""
                SELECT s.name,
                       SUM(CASE WHEN i.due_date IS NULL OR i.due_date >= :asAt
                                THEN b.balance ELSE 0 END) AS semasa,
                       SUM(CASE WHEN i.due_date < :asAt
                                 AND DATEDIFF(:asAt, i.due_date) <= 30
                                THEN b.balance ELSE 0 END) AS d30,
                       SUM(CASE WHEN DATEDIFF(:asAt, i.due_date) BETWEEN 31 AND 60
                                THEN b.balance ELSE 0 END) AS d60,
                       SUM(CASE WHEN DATEDIFF(:asAt, i.due_date) > 60
                                THEN b.balance ELSE 0 END) AS d90,
                       SUM(b.balance) AS jumlah
                FROM   exp_invoice i
                JOIN   exp_supplier s ON s.id = i.supplier_id
                JOIN   exp_invoice_balance b ON b.invoice_id = i.id
                WHERE  i.sp_code = :sp AND i.status = 'ACTIVE' AND b.balance > 0
                GROUP  BY s.id, s.name
                HAVING SUM(b.balance) > 0
                ORDER  BY s.name
                """)
                .setParameter("sp", sp())
                .setParameter("asAt", tarikh)
                .getResultList();

        List<AgingRow> out = new ArrayList<>();
        BigDecimal tc = BigDecimal.ZERO, t30 = BigDecimal.ZERO,
                   t60 = BigDecimal.ZERO, t90 = BigDecimal.ZERO, tt = BigDecimal.ZERO;

        for (Object[] r : rows) {
            BigDecimal c = num(r[1]), a30 = num(r[2]), a60 = num(r[3]),
                       a90 = num(r[4]), tot = num(r[5]);
            out.add(new AgingRow((String) r[0], c, a30, a60, a90, tot));
            tc = tc.add(c); t30 = t30.add(a30); t60 = t60.add(a60);
            t90 = t90.add(a90); tt = tt.add(tot);
        }

        return new AgingReport(tarikh, out, new AgingTotals(tc, t30, t60, t90, tt));
    }

    // ---------- Penyata bayaran ----------

    record PaymentRow(LocalDate payDate, String pvNo, String supplierName,
                      String invoiceNo, String method, BigDecimal amount) {}
    record PaymentReport(List<PaymentRow> rows, BigDecimal total) {}

    @GetMapping("/payments")
    @SuppressWarnings("unchecked")
    PaymentReport payments(@RequestParam(required = false) LocalDate from,
                           @RequestParam(required = false) LocalDate to) {
        Access.requireAnyRole("melihat penyata bayaran", "SP_ADMIN", "CLERK");

        List<Object[]> rows = em.createNativeQuery("""
                SELECT p.pay_date, p.pv_no, s.name, i.inv_no, p.method, p.amount
                FROM   exp_payment p
                JOIN   exp_invoice i  ON i.id = p.invoice_id
                JOIN   exp_supplier s ON s.id = i.supplier_id
                WHERE  p.sp_code = :sp AND p.status = 'ACTIVE'
                  AND (:from IS NULL OR p.pay_date >= :from)
                  AND (:to   IS NULL OR p.pay_date <= :to)
                ORDER  BY p.pay_date DESC, p.pv_no DESC
                """)
                .setParameter("sp", sp())
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();

        List<PaymentRow> out = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;

        for (Object[] r : rows) {
            BigDecimal amt = num(r[5]);
            out.add(new PaymentRow(toDate(r[0]), (String) r[1], (String) r[2],
                    (String) r[3], (String) r[4], amt));
            total = total.add(amt);
        }

        return new PaymentReport(out, total);
    }

    // ---------- Dashboard ----------

    record TrendPoint(String label, BigDecimal billed, BigDecimal paid) {}
    record CategorySlice(String name, BigDecimal amount) {}
    record UnsettledRow(Long id, String invNo, String supplierName,
                        LocalDate dueDate, BigDecimal total, BigDecimal balance,
                        String status, boolean overdue) {}
    record Dashboard(BigDecimal totalSpend, BigDecimal directCash, BigDecimal paid,
                     int pvCount, BigDecimal outstanding, int outstandingCount,
                     int overdueCount, List<TrendPoint> trend,
                     List<CategorySlice> byCategory, List<UnsettledRow> unsettled) {}

    /**
     * Ringkasan untuk skrin Dashboard.
     *
     * 'Jumlah Perbelanjaan' ialah duit yang benar-benar KELUAR — PV
     * ditambah bayaran terus. Ia berbeza daripada laporan perbelanjaan,
     * yang mengira apa yang DITANGGUNG (invois pada tarikh invois, sama
     * ada dibayar atau belum). Kedua-duanya betul untuk soalan
     * masing-masing: berapa banyak wang tinggal, berbanding berapa banyak
     * kos bulan ini.
     */
    @GetMapping("/dashboard")
    @SuppressWarnings("unchecked")
    Dashboard dashboard() {
        Access.requireAnyRole("melihat dashboard perbelanjaan", "SP_ADMIN", "CLERK");
        String sp = sp();

        BigDecimal pvJumlah = num(em.createNativeQuery(
                "SELECT COALESCE(SUM(amount),0) FROM exp_payment "
                + "WHERE sp_code = :sp AND status = 'ACTIVE'")
                .setParameter("sp", sp).getSingleResult());

        int pvCount = ((Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM exp_payment WHERE sp_code = :sp AND status = 'ACTIVE'")
                .setParameter("sp", sp).getSingleResult()).intValue();

        BigDecimal tunai = num(em.createNativeQuery(
                "SELECT COALESCE(SUM(amount),0) FROM exp_cash_entry "
                + "WHERE sp_code = :sp AND status = 'ACTIVE'")
                .setParameter("sp", sp).getSingleResult());

        Object[] tunggak = (Object[]) em.createNativeQuery("""
                SELECT COALESCE(SUM(b.balance),0), COUNT(*),
                       COALESCE(SUM(CASE WHEN i.due_date IS NOT NULL
                                          AND i.due_date < CURDATE() THEN 1 ELSE 0 END),0)
                FROM   exp_invoice i
                JOIN   exp_invoice_balance b ON b.invoice_id = i.id
                WHERE  i.sp_code = :sp AND i.status = 'ACTIVE' AND b.balance > 0
                """).setParameter("sp", sp).getSingleResult();

        // Trend tujuh bulan terakhir: diinvois berbanding dibelanjakan.
        // Bulan tanpa aktiviti tetap muncul sebagai sifar — jurang dalam
        // carta lebih mengelirukan daripada sifar yang jelas.
        List<Object[]> trendRows = em.createNativeQuery("""
                SELECT bulan,
                       COALESCE(SUM(billed),0) AS billed,
                       COALESCE(SUM(paid),0)   AS paid
                FROM (
                    SELECT DATE_FORMAT(i.inv_date,'%Y-%m') AS bulan, i.total AS billed, 0 AS paid
                    FROM   exp_invoice i
                    WHERE  i.sp_code = :sp AND i.status = 'ACTIVE'
                      AND  i.inv_date >= DATE_SUB(CURDATE(), INTERVAL 6 MONTH)
                    UNION ALL
                    SELECT DATE_FORMAT(p.pay_date,'%Y-%m'), 0, p.amount
                    FROM   exp_payment p
                    WHERE  p.sp_code = :sp AND p.status = 'ACTIVE'
                      AND  p.pay_date >= DATE_SUB(CURDATE(), INTERVAL 6 MONTH)
                    UNION ALL
                    SELECT DATE_FORMAT(e.entry_date,'%Y-%m'), 0, e.amount
                    FROM   exp_cash_entry e
                    WHERE  e.sp_code = :sp AND e.status = 'ACTIVE'
                      AND  e.entry_date >= DATE_SUB(CURDATE(), INTERVAL 6 MONTH)
                ) x
                GROUP BY bulan ORDER BY bulan
                """).setParameter("sp", sp).getResultList();

        List<TrendPoint> trend = new ArrayList<>();
        for (Object[] r : trendRows) {
            trend.add(new TrendPoint((String) r[0], num(r[1]), num(r[2])));
        }

        // Pecahan kategori: apa yang DIBELANJAKAN, bukan yang diinvois —
        // sepadan dengan kad Jumlah Perbelanjaan di atasnya.
        List<Object[]> catRows = em.createNativeQuery("""
                SELECT COALESCE(induk.name, jenis.name) AS kategori, SUM(x.amount) AS jumlah
                FROM (
                    SELECT it.category_id, it.amount * (p.amount / i.total) AS amount
                    FROM   exp_payment p
                    JOIN   exp_invoice i ON i.id = p.invoice_id
                    JOIN   exp_invoice_item it ON it.invoice_id = i.id
                    WHERE  p.sp_code = :sp AND p.status = 'ACTIVE' AND i.total > 0
                    UNION ALL
                    SELECT e.category_id, e.amount
                    FROM   exp_cash_entry e
                    WHERE  e.sp_code = :sp AND e.status = 'ACTIVE'
                ) x
                JOIN exp_category jenis ON jenis.id = x.category_id
                LEFT JOIN exp_category induk ON induk.id = jenis.parent_id
                GROUP BY COALESCE(induk.name, jenis.name)
                ORDER BY jumlah DESC
                """).setParameter("sp", sp).getResultList();

        List<CategorySlice> byCategory = new ArrayList<>();
        for (Object[] r : catRows) {
            byCategory.add(new CategorySlice((String) r[0],
                    num(r[1]).setScale(2, java.math.RoundingMode.HALF_UP)));
        }

        // Invois belum selesai — paling hampir tamat tempoh dahulu.
        List<Object[]> unsRows = em.createNativeQuery("""
                SELECT i.id, i.inv_no, s.name, i.due_date, i.total, b.balance, b.status,
                       (i.due_date IS NOT NULL AND i.due_date < CURDATE()) AS overdue
                FROM   exp_invoice i
                JOIN   exp_supplier s ON s.id = i.supplier_id
                JOIN   exp_invoice_balance b ON b.invoice_id = i.id
                WHERE  i.sp_code = :sp AND i.status = 'ACTIVE' AND b.balance > 0
                ORDER  BY i.due_date IS NULL, i.due_date, i.id
                LIMIT  20
                """).setParameter("sp", sp).getResultList();

        List<UnsettledRow> unsettled = new ArrayList<>();
        for (Object[] r : unsRows) {
            unsettled.add(new UnsettledRow(
                    ((Number) r[0]).longValue(), (String) r[1], (String) r[2],
                    toDate(r[3]), num(r[4]), num(r[5]), (String) r[6], bool(r[7])));
        }

        return new Dashboard(
                pvJumlah.add(tunai), tunai, pvJumlah, pvCount,
                num(tunggak[0]), ((Number) tunggak[1]).intValue(),
                ((Number) tunggak[2]).intValue(),
                trend, byCategory, unsettled);
    }

    // ---------- helper ----------

    /** tinyint(1) datang sebagai Boolean dari MySQL Connector/J. */
    private static boolean bool(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        return ((Number) v).intValue() != 0;
    }

    private static BigDecimal num(Object v) {
        return v == null ? BigDecimal.ZERO : new BigDecimal(v.toString());
    }

    private static LocalDate toDate(Object v) {
        if (v == null) return null;
        if (v instanceof LocalDate d) return d;
        if (v instanceof java.sql.Date d) return d.toLocalDate();
        return LocalDate.parse(v.toString());
    }

    private String sp() {
        String sp = TenantContext.get();
        if (sp == null || sp.isBlank()) {
            throw new IllegalStateException("Header X-SP-Id diperlukan");
        }
        return sp;
    }
}
