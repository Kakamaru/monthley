package com.monthley.statement.api;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Satu baris invois — produk, tempoh, kuantiti, harga. */
public record InvoiceItem(
        String description,
        LocalDate periodStart,
        LocalDate periodEnd,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal amount) {
}
