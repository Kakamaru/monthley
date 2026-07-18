package com.monthley.payment.internal;

import com.monthley.payment.api.*;
import com.monthley.shared.Access;
import com.monthley.shared.PageResponse;
import com.monthley.shared.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Manual Payment — rekod bayaran tunai/cek/pindahan yang diterima di luar talian.
 *
 *   GET  /api/v1/payments/outstanding  — cari invois tertunggak
 *   POST /api/v1/payments/manual       — rekod bayaran
 *
 * PERANAN: CLERK (Cashier) sahaja. Admin TIDAK boleh terima bayaran —
 * ini pengasingan tugas yang disengajakan.
 */
@RestController
@RequestMapping("/api/v1/payments")
class ManualPaymentController {

    private final PaymentPort payments;

    @PersistenceContext
    private EntityManager em;

    ManualPaymentController(PaymentPort payments) {
        this.payments = payments;
    }

    // ---------- DTO ----------

    record OutstandingRow(
            Long documentId, String accountNo, String accountName, Long accountId,
            String invoiceNo, String period, LocalDate docDate, LocalDate dueDate,
            BigDecimal total, BigDecimal paid, BigDecimal outstanding) {}

    record ManualPaymentRequest(
            @NotNull Long documentId,
            @NotNull Long accountId,
            @NotBlank String paymentType,      // CASH | CHEQUE | TRANSFER | FPX | ADJUSTMENT
            String paymentRefNo,
            String paymentDate,                // 'YYYY-MM-DD'
            @NotNull @Positive BigDecimal amount,
            String remarks) {}

    record PaymentTypeDto(String code, String label) {}
    record MessageResponse(String message) {}

    // ---------- Endpoints ----------

    @GetMapping("/payment-types")
    List<PaymentTypeDto> paymentTypes() {
        return List.of(
                new PaymentTypeDto("CASH",     "Tunai"),
                new PaymentTypeDto("CHEQUE",   "Cek"),
                new PaymentTypeDto("TRANSFER", "Pindahan Bank"),
                new PaymentTypeDto("FPX",      "FPX / Online"),
                new PaymentTypeDto("ADJUSTMENT", "Penyelarasan"));
    }

    @GetMapping("/outstanding")
    @SuppressWarnings("unchecked")
    PageResponse<OutstandingRow> outstanding(
            @RequestParam(required = false) String account,
            @RequestParam(required = false) String invoice,
            @RequestParam(required = false) Long category,
            @RequestParam(required = false) Long product,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        Access.requireRole("CLERK", "melihat invois untuk bayaran");

        String acc = blankToNull(account);
        String inv = blankToNull(invoice);

        String where = """
            WHERE d.sp_code = :sp
              AND d.doc_type = 'INVOICE'
              AND d.status <> 'CANCELLED'
              AND (:acc IS NULL OR LOWER(a.account_no) LIKE :acc OR LOWER(a.account_name) LIKE :acc)
              AND (:inv IS NULL OR LOWER(d.doc_no) LIKE :inv)
              AND (:product IS NULL OR EXISTS (
                    SELECT 1 FROM financial_document_line l
                    WHERE l.document_id = d.id AND l.product_id = :product AND l.active = 1))
              AND (:category IS NULL OR EXISTS (
                    SELECT 1 FROM financial_document_line l
                    JOIN product p ON p.id = l.product_id
                    WHERE l.document_id = d.id AND p.category_id = :category AND l.active = 1))
              AND (d.amount + d.tax_amount) - COALESCE((
                    SELECT SUM(al.amount) FROM fi_allocation al
                    WHERE al.debit_document_id = d.id AND al.status = 'ACTIVE'), 0) > 0.005
            """;

        var countQ = em.createNativeQuery("""
                SELECT COUNT(*) FROM financial_document d
                JOIN account a ON a.id = d.account_id
                """ + where);
        bind(countQ, acc, inv, category, product);
        long total = ((Number) countQ.getSingleResult()).longValue();

        String sql = """
            SELECT d.id, a.account_no, a.account_name, a.id, d.doc_no, p.name_,
                   d.doc_date, d.due_date,
                   (d.amount + d.tax_amount) AS total,
                   COALESCE((SELECT SUM(al.amount) FROM fi_allocation al
                             WHERE al.debit_document_id = d.id AND al.status = 'ACTIVE'), 0) AS paid
            FROM financial_document d
            JOIN account a ON a.id = d.account_id
            LEFT JOIN fi_period p ON p.period_id = d.period_id
            """ + where + " ORDER BY a.account_no, d.due_date, d.doc_no LIMIT :lim OFFSET :off";

        var dataQ = em.createNativeQuery(sql);
        bind(dataQ, acc, inv, category, product);
        dataQ.setParameter("lim", size);
        dataQ.setParameter("off", page * size);

        List<Object[]> rows = dataQ.getResultList();
        List<OutstandingRow> items = new ArrayList<>();
        for (Object[] r : rows) {
            BigDecimal t = (BigDecimal) r[8];
            BigDecimal p = (BigDecimal) r[9];
            items.add(new OutstandingRow(
                    ((Number) r[0]).longValue(), (String) r[1], (String) r[2],
                    ((Number) r[3]).longValue(), (String) r[4], (String) r[5],
                    toLocalDate(r[6]), toLocalDate(r[7]), t, p, t.subtract(p)));
        }
        return new PageResponse<>(items, total, page, size);
    }

    @PostMapping("/manual")
    ResponseEntity<?> recordPayment(@Valid @RequestBody ManualPaymentRequest r) {
        Access.requireRole("CLERK", "merekod bayaran");

        PaymentResult result = payments.receivePayment(new NewPayment(
                sp(), r.accountId(), r.amount(),
                PaymentMethod.valueOf(r.paymentType()),
                r.paymentRefNo(),
                List.of(r.documentId())));   // knock-off invois yang dipilih

        return ResponseEntity.ok(result);
    }

    // ---------- helper ----------

    private void bind(jakarta.persistence.Query q, String acc, String inv, Long category, Long product) {
        q.setParameter("sp", sp());
        q.setParameter("acc", acc == null ? null : "%" + acc.toLowerCase() + "%");
        q.setParameter("inv", inv == null ? null : "%" + inv.toLowerCase() + "%");
        q.setParameter("category", category);
        q.setParameter("product", product);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private String sp() {
        String sp = TenantContext.get();
        if (sp == null || sp.isBlank()) {
            throw new IllegalStateException("Header X-SP-Id diperlukan");
        }
        return sp;
    }

    private static LocalDate toLocalDate(Object o) {
        if (o == null) return null;
        if (o instanceof LocalDate d) return d;
        if (o instanceof java.time.LocalDateTime dt) return dt.toLocalDate();
        if (o instanceof java.sql.Timestamp ts) return ts.toLocalDateTime().toLocalDate();
        if (o instanceof java.sql.Date d) return d.toLocalDate();
        return null;
    }
}
