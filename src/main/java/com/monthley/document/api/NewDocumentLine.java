package com.monthley.document.api;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Baris untuk dokumen baru — dihantar oleh billing.
 *
 * @param periodId       period LIPUTAN — aras charge_frequency PRODUK
 * @param quantity       kuantiti ASAL (bukan diprorate)
 * @param prorationRatio 0..1; amount = ROUND(unitPrice x quantity x ratio, 2).
 *                       1 = tiada proration. Rujuk V20.
 * @param periodStart    mula liputan sebenar
 * @param periodEnd      tamat liputan sebenar (inklusif)
 * @param onceOnly       true untuk produk ONE_TIME. Menyebabkan idem_key jadi
 *                       (account, product, 'ONCE') — sekali seumur hidup.
 *                       Rujuk V18.
 */
public record NewDocumentLine(
        Long productId,
        Long accountId,
        long periodId,
        String description,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal prorationRatio,
        BigDecimal amount,
        BigDecimal taxAmount,
        LocalDate periodStart,
        LocalDate periodEnd,
        boolean onceOnly) {
}
