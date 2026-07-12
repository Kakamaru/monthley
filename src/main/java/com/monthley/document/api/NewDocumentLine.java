package com.monthley.document.api;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Baris untuk dokumen baru — dihantar oleh billing. */
public record NewDocumentLine(
        Long productId,
        Long accountId,
        String description,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal amount,
        BigDecimal taxAmount,
        LocalDate periodStart,
        LocalDate periodEnd) {
}
