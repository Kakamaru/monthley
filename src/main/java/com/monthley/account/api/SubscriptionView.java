package com.monthley.account.api;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Langganan produk oleh akaun — snapshot untuk billing engine.
 *
 * @param effectiveUnitPrice harga khusus-akaun; null = guna product.unitRate.
 *                           Hanya dihormati kalau sp_billing_setting.allow_price_override.
 * @param parentSubscriptionId kalau bukan null, langganan ini ialah komponen pakej.
 *                             Anak TIDAK dicaj — hanya parent. Rujuk
 *                             docs/domain/legacy-generator-analysis.md §3.3
 */
public record SubscriptionView(
        Long id,
        Long accountId,
        Long productId,
        BigDecimal quantity,
        BigDecimal effectiveUnitPrice,
        LocalDate startDate,
        LocalDate endDate,
        Long parentSubscriptionId) {
}
