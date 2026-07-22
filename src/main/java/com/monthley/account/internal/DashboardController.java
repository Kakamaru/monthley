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

    private static BigDecimal num(Object o) {
        return o == null ? BigDecimal.ZERO : new BigDecimal(o.toString());
    }

    private String sp() {
        String sp = TenantContext.get();
        if (sp == null || sp.isBlank()) throw new IllegalStateException("SP tak ditetapkan.");
        return sp;
    }
}
