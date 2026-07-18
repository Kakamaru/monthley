package com.monthley.billing.internal;

import com.monthley.shared.Charge;

import java.math.BigDecimal;

/**
 * Satu baris invois yang telah dikira (belum disimpan).
 *
 * Setiap baris bawa {@link Charge} SENDIRI — bukan period dokumen.
 * Satu invois boleh ada baris pada aras berbeza: produk bulanan pada period
 * bulanan, produk tahunan pada period tahunan, dalam dokumen yang sama.
 * Disahkan lawan production (13 baris, 12 aras MO + 1 aras YR).
 *
 * @param quantity        kuantiti ASAL — "2 unit" ialah 2
 * @param prorationRatio  0..1; amount = ROUND(unitRate x quantity x ratio, 2).
 *                        Disimpan supaya amaun boleh dikira semula & diaudit.
 */
public record CalculatedLine(
        Long productId,
        Long accountId,
        Charge charge,
        String description,
        BigDecimal quantity,
        BigDecimal unitRate,
        BigDecimal prorationRatio,
        BigDecimal amount,
        BigDecimal taxAmount,
        Long incomeGlAccountId,
        boolean onceOnly) {
}
