package com.monthley.expenses.internal;

import com.monthley.shared.Access;
import com.monthley.shared.PageResponse;
import com.monthley.shared.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Baucar bayaran (PV) dan bayaran terus.
 *
 *   GET    /api/v1/expenses/payments?page=&size=
 *   POST   /api/v1/expenses/payments
 *   DELETE /api/v1/expenses/payments/{id}
 *
 *   GET    /api/v1/expenses/cash-entries?from=&to=&page=&size=
 *   POST   /api/v1/expenses/cash-entries
 *   DELETE /api/v1/expenses/cash-entries/{id}
 *
 *   GET    /api/v1/expenses/cashbook?from=&to=
 *
 * Buku Tunai ialah GABUNGAN PV dan bayaran terus — kedua-duanya duit
 * keluar, cuma satu melalui invois dan satu tidak.
 */
@RestController
@RequestMapping("/api/v1/expenses")
class ExpPaymentController {

    private final ExpPaymentService service;

    @PersistenceContext
    private EntityManager em;

    ExpPaymentController(ExpPaymentService service) {
        this.service = service;
    }

    record PaymentRow(Long id, String pvNo, Long invoiceId, String invNo,
                      String supplierName, LocalDate payDate, BigDecimal amount,
                      String method, String refNo, String status) {}

    record CashRow(Long id, String voucherNo, LocalDate entryDate,
                   Long categoryId, String categoryName, String payee,
                   String description, BigDecimal amount, String method,
                   String refNo, String status) {}

    record CashbookRow(LocalDate date, String docNo, String source,
                       String description, BigDecimal amount) {}

    record NewPvRequest(Long invoiceId, LocalDate payDate, BigDecimal amount,
                        String method, String refNo, String note) {}

    record NewCashRequest(LocalDate entryDate, Long categoryId, String payee,
                          String description, BigDecimal amount,
                          String method, String refNo) {}

    record CancelRequest(String reason) {}

    // ---------- Baucar bayaran (PV) ----------

    @GetMapping("/payments")
    @SuppressWarnings("unchecked")
    PageResponse<PaymentRow> listPayments(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Access.requireAnyRole("melihat baucar bayaran", "SP_ADMIN", "CLERK");

        String base = """
            FROM   exp_payment p
            JOIN   exp_invoice i  ON i.id = p.invoice_id
            JOIN   exp_supplier s ON s.id = i.supplier_id
            WHERE  p.sp_code = :sp
            """;

        long total = ((Number) em.createNativeQuery("SELECT COUNT(*) " + base)
                .setParameter("sp", sp()).getSingleResult()).longValue();

        var q = em.createNativeQuery(
                "SELECT p.id, p.pv_no, p.invoice_id, i.inv_no, s.name, p.pay_date, "
                + "p.amount, p.method, p.ref_no, p.status "
                + base + " ORDER BY p.pay_date DESC, p.id DESC LIMIT :lim OFFSET :off");
        q.setParameter("sp", sp());
        q.setParameter("lim", size);
        q.setParameter("off", page * size);

        List<PaymentRow> items = new ArrayList<>();
        for (Object[] r : (List<Object[]>) q.getResultList()) {
            items.add(new PaymentRow(
                    ((Number) r[0]).longValue(), (String) r[1], ((Number) r[2]).longValue(),
                    (String) r[3], (String) r[4], toDate(r[5]), (BigDecimal) r[6],
                    (String) r[7], (String) r[8], (String) r[9]));
        }
        return new PageResponse<>(items, total, page, size);
    }

    @PostMapping("/payments")
    ResponseEntity<?> pay(@RequestBody NewPvRequest r) {
        Access.requireAnyRole("merekod bayaran pembekal", "SP_ADMIN", "CLERK");
        Long id = service.payInvoice(new ExpPaymentService.NewPv(
                r.invoiceId(), r.payDate(), r.amount(), r.method(), r.refNo(), r.note()));
        return ResponseEntity.ok(Map.of("id", id));
    }

    @DeleteMapping("/payments/{id}")
    ResponseEntity<?> cancelPayment(@PathVariable Long id, @RequestBody CancelRequest r) {
        Access.requireRole("SP_ADMIN", "membatalkan bayaran");
        service.cancelPayment(id, wajibSebab(r), currentUserId());
        return ResponseEntity.ok(Map.of("message", "Bayaran dibatalkan."));
    }

    // ---------- Bayaran terus ----------

    @GetMapping("/cash-entries")
    @SuppressWarnings("unchecked")
    PageResponse<CashRow> listCashEntries(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Access.requireAnyRole("melihat bayaran terus", "SP_ADMIN", "CLERK");

        String base = """
            FROM   exp_cash_entry e
            JOIN   exp_category c ON c.id = e.category_id
            WHERE  e.sp_code = :sp
              AND (:from IS NULL OR e.entry_date >= :from)
              AND (:to   IS NULL OR e.entry_date <= :to)
            """;

        var countQ = em.createNativeQuery("SELECT COUNT(*) " + base);
        countQ.setParameter("sp", sp());
        countQ.setParameter("from", from);
        countQ.setParameter("to", to);
        long total = ((Number) countQ.getSingleResult()).longValue();

        var q = em.createNativeQuery(
                "SELECT e.id, e.voucher_no, e.entry_date, e.category_id, c.name, "
                + "e.payee, e.description, e.amount, e.method, e.ref_no, e.status "
                + base + " ORDER BY e.entry_date DESC, e.id DESC LIMIT :lim OFFSET :off");
        q.setParameter("sp", sp());
        q.setParameter("from", from);
        q.setParameter("to", to);
        q.setParameter("lim", size);
        q.setParameter("off", page * size);

        List<CashRow> items = new ArrayList<>();
        for (Object[] r : (List<Object[]>) q.getResultList()) {
            items.add(new CashRow(
                    ((Number) r[0]).longValue(), (String) r[1], toDate(r[2]),
                    ((Number) r[3]).longValue(), (String) r[4], (String) r[5],
                    (String) r[6], (BigDecimal) r[7], (String) r[8],
                    (String) r[9], (String) r[10]));
        }
        return new PageResponse<>(items, total, page, size);
    }

    @PostMapping("/cash-entries")
    ResponseEntity<?> recordCash(@RequestBody NewCashRequest r) {
        Access.requireAnyRole("merekod bayaran terus", "SP_ADMIN", "CLERK");
        Long id = service.recordCashEntry(new ExpPaymentService.NewCashEntry(
                r.entryDate(), r.categoryId(), r.payee(), r.description(),
                r.amount(), r.method(), r.refNo()));
        return ResponseEntity.ok(Map.of("id", id));
    }

    @DeleteMapping("/cash-entries/{id}")
    ResponseEntity<?> cancelCash(@PathVariable Long id, @RequestBody CancelRequest r) {
        Access.requireRole("SP_ADMIN", "membatalkan bayaran terus");
        service.cancelCashEntry(id, wajibSebab(r), currentUserId());
        return ResponseEntity.ok(Map.of("message", "Rekod dibatalkan."));
    }

    // ---------- Buku Tunai ----------

    /**
     * Gabungan PV dan bayaran terus — semua duit keluar dalam satu senarai.
     *
     * Dibina dari dokumen sumber dan bukan dari lejar Bank, walaupun
     * kedua-duanya mempos ke sana. Lejar tidak menyimpan penerima atau
     * kategori, dan itulah yang menjadikan buku tunai berguna.
     */
    @GetMapping("/cashbook")
    @SuppressWarnings("unchecked")
    List<CashbookRow> cashbook(@RequestParam(required = false) LocalDate from,
                               @RequestParam(required = false) LocalDate to) {

        Access.requireAnyRole("melihat buku tunai", "SP_ADMIN", "CLERK");

        var q = em.createNativeQuery("""
                SELECT p.pay_date AS dt, p.pv_no AS no_, 'PV' AS src,
                       CONCAT(s.name, ' — ', i.inv_no) AS descr, p.amount
                FROM   exp_payment p
                JOIN   exp_invoice i  ON i.id = p.invoice_id
                JOIN   exp_supplier s ON s.id = i.supplier_id
                WHERE  p.sp_code = :sp AND p.status = 'ACTIVE'
                  AND (:from IS NULL OR p.pay_date >= :from)
                  AND (:to   IS NULL OR p.pay_date <= :to)
                UNION ALL
                SELECT e.entry_date, e.voucher_no, 'TERUS',
                       CONCAT(e.payee, COALESCE(CONCAT(' — ', e.description), '')), e.amount
                FROM   exp_cash_entry e
                WHERE  e.sp_code = :sp AND e.status = 'ACTIVE'
                  AND (:from IS NULL OR e.entry_date >= :from)
                  AND (:to   IS NULL OR e.entry_date <= :to)
                ORDER  BY dt DESC, no_ DESC
                """);
        q.setParameter("sp", sp());
        q.setParameter("from", from);
        q.setParameter("to", to);

        List<CashbookRow> out = new ArrayList<>();
        for (Object[] r : (List<Object[]>) q.getResultList()) {
            out.add(new CashbookRow(toDate(r[0]), (String) r[1], (String) r[2],
                    (String) r[3], (BigDecimal) r[4]));
        }
        return out;
    }

    // ---------- helper ----------

    private String wajibSebab(CancelRequest r) {
        String reason = (r == null || r.reason() == null) ? null : r.reason().trim();
        if (reason == null || reason.isBlank()) {
            throw new IllegalStateException("Sebab pembatalan diperlukan.");
        }
        return reason;
    }

    private Long currentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new IllegalStateException("Tiada pengguna dalam konteks.");
        }
        return Long.valueOf(auth.getName());
    }

    private static LocalDate toDate(Object v) {
        return v == null ? null : ((java.sql.Date) v).toLocalDate();
    }

    private String sp() {
        String sp = TenantContext.get();
        if (sp == null || sp.isBlank()) {
            throw new IllegalStateException("Header X-SP-Id diperlukan");
        }
        return sp;
    }
}
