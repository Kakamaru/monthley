package com.monthley.statement.api;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Satu baris pada resit — apa yang bayaran ini tutup.
 *
 * Datang daripada alokasi: setiap baris invois yang diknock oleh resit
 * ini menjadi satu item. Legacy memaparkannya sama.
 */
public record ReceiptItem(
        String invoiceNo,
        String description,
        LocalDate periodStart,
        LocalDate periodEnd,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal amount) {
}
