package com.monthley.document.api;

import java.time.LocalDate;
import java.util.List;

/**
 * Permintaan cipta invois.
 *
 * @param periodId period LARIAN — aras charge_frequency AKAUN.
 *                 Berbeza dari NewDocumentLine.periodId, yang ialah period
 *                 LIPUTAN pada aras charge_frequency PRODUK.
 *                 Rujuk docs/domain/billing-rules.md §3
 */
public record NewInvoice(
        String spCode,
        Long accountId,
        long periodId,
        LocalDate docDate,
        LocalDate dueDate,
        String title,
        List<NewDocumentLine> lines) {
}
