package com.monthley.billing.internal;

import java.math.BigDecimal;
import java.util.Set;

/**
 * Tetapan billing untuk satu SP semasa larian.
 *
 * @param taxRate            kadar cukai aras SP (legacy: sp.sales_tax_rate)
 * @param minDenom           denominasi terkecil (cth 0.05); null = tiada pembundaran
 * @param allowPriceOverride kalau false, subscription.effectiveUnitPrice DIABAIKAN.
 *                           Legacy: sp.allow_price_override == 'Y'
 * @param termDays           tempoh bayaran — due date = tarikh dokumen + termDays.
 *                           Legacy: sp.term_days
 * @param excludedPeriodIds  period_id aras BULAN yang dikecualikan untuk SP ini.
 *                           Baris diprorate ikut bilangan bulan dikecualikan
 *                           dalam kitarannya. Rujuk legacy-generator-analysis.md §5.1
 */
public record BillingContext(
        String spCode,
        BigDecimal taxRate,
        BigDecimal minDenom,
        boolean allowPriceOverride,
        int termDays,
        Set<Long> excludedPeriodIds,
        String arGlCode,
        String taxGlCode,
        String defaultIncomeGlCode
) {

    public BillingContext {
        excludedPeriodIds = excludedPeriodIds == null ? Set.of() : Set.copyOf(excludedPeriodIds);
    }

    /** Kilang ringkas untuk ujian: tiada pembundaran, tiada exclude, override dibenarkan. */
    public static BillingContext of(String spCode, BigDecimal taxRate,
                                    String ar, String tax, String income) {
        return new BillingContext(spCode, taxRate, null, true, 14, Set.of(), ar, tax, income);
    }
}
