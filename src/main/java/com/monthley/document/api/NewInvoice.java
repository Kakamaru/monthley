package com.monthley.document.api;

import java.time.LocalDate;
import java.util.List;

public record NewInvoice(
        String spCode,
        Long accountId,
        String period,          // 'YYYY-MM'
        LocalDate docDate,
        LocalDate dueDate,
        String title,
        List<NewDocumentLine> lines) {
}
