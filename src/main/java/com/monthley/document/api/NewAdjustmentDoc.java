package com.monthley.document.api;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Cipta dokumen adjustment: CREDIT_NOTE (reduction) atau DEBIT_NOTE (additional).
 * sourceRef = token idempotency dari klien (elak double-submit).
 * Rujuk docs/decisions/0003-account-adjustment.md.
 */
public record NewAdjustmentDoc(
        String spCode,
        Long accountId,
        DocumentType docType,   // CREDIT_NOTE atau DEBIT_NOTE
        LocalDate docDate,
        String title,
        BigDecimal amount,
        String sourceRef) {
}
