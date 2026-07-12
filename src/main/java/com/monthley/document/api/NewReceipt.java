package com.monthley.document.api;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Resit sebagai dokumen (type RECEIPT). */
public record NewReceipt(
        String spCode,
        Long accountId,
        LocalDate docDate,
        String title,
        BigDecimal amount) {
}
