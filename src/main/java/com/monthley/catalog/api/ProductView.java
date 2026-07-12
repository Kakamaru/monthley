package com.monthley.catalog.api;

import com.monthley.shared.ChargeFrequency;
import java.math.BigDecimal;

/**
 * Pandangan produk untuk modul lain (billing). Read-only snapshot —
 * modul luar tak sentuh entiti dalaman catalog.
 */
public record ProductView(
        Long id,
        String spCode,
        String code,
        String name,
        ChargeFrequency chargeFrequency,
        Integer anchorMonth,          // null = ikut start_date langganan
        BigDecimal unitRate,
        Long incomeGlAccountId,
        boolean prorated,
        boolean latePenalty,
        boolean mandatory) {
}
