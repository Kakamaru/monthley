package com.monthley.account.api;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Langganan produk oleh akaun — snapshot untuk billing engine. */
public record SubscriptionView(
        Long id,
        Long accountId,
        Long productId,
        BigDecimal quantity,
        BigDecimal effectiveUnitPrice,   // null = guna product.unitRate
        LocalDate startDate,
        LocalDate endDate) {
}
