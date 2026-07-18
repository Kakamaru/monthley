package com.monthley.tenancy.api;

import java.math.BigDecimal;

/**
 * Tetapan SP yang diperlukan enjin bil, dikumpul dari tiga table:
 *   sp_document_setting  — invoice_gen_mode, split, allow_price_override
 *   sp_billing_setting   — tax_rate, payment_term_days, GL id, smallest_denomination
 *
 * GL dikembalikan sebagai ID (bigint chart_of_accounts). Pemanggil terjemah
 * ke kod via LedgerPort.glCodeFor — tenancy tidak tahu tentang kod GL.
 */
public interface BillingSettingsPort {

    BillingSettings forSp(String spCode);

    record BillingSettings(
            String genMode,              // POSTPAID | CURRENT | PREPAID
            BigDecimal taxRate,          // null -> 0
            int termDays,
            Long arGlAccountId,          // nullable
            Long incomeGlAccountId,      // nullable
            BigDecimal smallestDenomination,  // 0 -> tiada pembundaran
            boolean allowPriceOverride
    ) {}
}
