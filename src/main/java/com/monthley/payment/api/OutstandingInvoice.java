package com.monthley.payment.api;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Invois tertunggak — untuk dipapar & diperuntuk. */
public record OutstandingInvoice(
        Long documentId,
        String docNo,
        Long accountId,
        String period,
        LocalDate docDate,
        LocalDate dueDate,
        BigDecimal total,
        BigDecimal paid,
        BigDecimal outstanding) {
}
