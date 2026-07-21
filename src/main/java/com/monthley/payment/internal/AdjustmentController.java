package com.monthley.payment.internal;

import com.monthley.shared.Access;
import com.monthley.shared.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Account Adjustment — Credit Note (Reduction) / Debit Note (Additional).
 * Peranan: SP_ADMIN (adjustment sensitif — pengasingan tugas).
 * Rujuk docs/decisions/0003-account-adjustment.md.
 */
@RestController
@RequestMapping("/api/v1/payments")
class AdjustmentController {

    private final AdjustmentService adjustments;

    @PersistenceContext
    private EntityManager em;

    AdjustmentController(AdjustmentService adjustments) {
        this.adjustments = adjustments;
    }

    record AdjustmentRequest(
            @NotNull Long accountId,
            @NotNull AdjustmentService.Kind kind,     // ADDITIONAL | REDUCTION
            @NotNull @Positive BigDecimal amount,
            Long targetInvoiceId,                     // wajib untuk REDUCTION
            String remarks,
            @NotNull String sourceRef) {}             // token idempotency dari klien

    record AdjustmentResponse(Long documentId, String docType, String message) {}

    @PostMapping("/adjustment")
    AdjustmentResponse adjust(@Valid @RequestBody AdjustmentRequest req) {
        Access.requireRole("SP_ADMIN", "membuat pelarasan akaun");

        var result = adjustments.adjust(new AdjustmentService.NewAdjustment(
                sp(), req.accountId(), req.kind(), req.amount(),
                req.targetInvoiceId(), req.remarks(), req.sourceRef()));

        String label = "CREDIT_NOTE".equals(result.docType()) ? "Kredit Nota" : "Debit Nota";
        return new AdjustmentResponse(result.documentId(), result.docType(),
                label + " berjaya dicipta.");
    }

    record InvoiceOption(Long id, String docNo, java.math.BigDecimal outstanding) {}

    /** Invois akaun yang masih ada baki (untuk dropdown Reduction). */
    @GetMapping("/adjustment/invoices")
    @SuppressWarnings("unchecked")
    List<InvoiceOption> invoices(@RequestParam Long accountId) {
        Access.requireRole("SP_ADMIN", "melihat invois untuk pelarasan");

        List<Object[]> rows = em.createNativeQuery("""
                SELECT d.id, d.doc_no,
                       (d.amount + d.tax_amount) - COALESCE((
                         SELECT SUM(al.amount) FROM fi_allocation al
                         WHERE al.debit_document_id = d.id AND al.status = 'ACTIVE'), 0) AS outstanding
                FROM financial_document d
                WHERE d.account_id = :acc AND d.sp_code = :sp
                  AND d.doc_type = 'INVOICE' AND d.status <> 'CANCELLED'
                HAVING outstanding > 0.005
                ORDER BY d.doc_date DESC, d.id DESC
                """)
                .setParameter("acc", accountId)
                .setParameter("sp", sp())
                .getResultList();

        List<InvoiceOption> out = new ArrayList<>();
        for (Object[] r : rows) {
            out.add(new InvoiceOption(
                    ((Number) r[0]).longValue(), (String) r[1], (java.math.BigDecimal) r[2]));
        }
        return out;
    }

    private String sp() {
        String sp = TenantContext.get();
        if (sp == null || sp.isBlank()) {
            throw new IllegalStateException("SP tidak ditetapkan.");
        }
        return sp;
    }
}
