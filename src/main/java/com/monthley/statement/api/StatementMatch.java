package com.monthley.statement.api;

import java.math.BigDecimal;

/**
 * Padanan alokasi — resit ini membayar invois itu.
 *
 * Dirender sebagai sub-baris berinden. TIDAK menyentuh lajur baki:
 * alokasi tidak menggerakkan baki (ADR 0009).
 */
public record StatementMatch(
        String invoiceNo,
        String productName,
        String period,
        BigDecimal amount) {
}
