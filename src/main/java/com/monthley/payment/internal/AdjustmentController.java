package com.monthley.payment.internal;

import com.monthley.shared.Access;
import com.monthley.shared.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

/**
 * Account Adjustment — Credit Note (Reduction) / Debit Note (Additional).
 * Peranan: SP_ADMIN (adjustment sensitif — pengasingan tugas).
 * Rujuk docs/decisions/0003-account-adjustment.md.
 */
@RestController
@RequestMapping("/api/v1/payments")
class AdjustmentController {

    private final AdjustmentService adjustments;

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

    private String sp() {
        String sp = TenantContext.get();
        if (sp == null || sp.isBlank()) {
            throw new IllegalStateException("SP tidak ditetapkan.");
        }
        return sp;
    }
}
