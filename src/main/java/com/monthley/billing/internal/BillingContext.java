package com.monthley.billing.internal;

import java.math.BigDecimal;

/**
 * Tetapan billing untuk satu SP semasa larian.
 */
public record BillingContext(
        String spCode,
        BigDecimal taxRate,
        BigDecimal minDenom,         // set_curr_min_denom (cth 0.05); null = tiada
        String arGlCode,
        String taxGlCode,
        String defaultIncomeGlCode
) {
    /** Kilang ringkas tanpa pembundaran (untuk keserasian ujian sedia ada). */
    public static BillingContext of(String spCode, BigDecimal taxRate,
                                    String ar, String tax, String income) {
        return new BillingContext(spCode, taxRate, null, ar, tax, income);
    }
}
