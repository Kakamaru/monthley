package com.monthley.billing.internal;

import java.math.BigDecimal;

/** Satu baris invois yang telah dikira (belum disimpan). */
public record CalculatedLine(
        Long productId,
        Long accountId,
        String description,
        BigDecimal quantity,
        BigDecimal unitRate,
        BigDecimal amount,
        BigDecimal taxAmount,
        Long incomeGlAccountId) {
}
