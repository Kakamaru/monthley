package com.monthley.ledger.api;

/**
 * Kod akaun GL standard yang dirujuk oleh modul lain semasa posting.
 * Setiap SP mula dengan carta akaun ni (lihat ChartOfAccountSeeder).
 */
public final class GlAccounts {

    private GlAccounts() {}

    public static final String BANK              = "1000";
    public static final String MP_CLEARING       = "1050";
    public static final String ACCOUNTS_RECEIVABLE = "1100";   // control, sub-ledger = AR
    public static final String TAX_PAYABLE       = "2100";
    public static final String CUSTOMER_DEPOSIT  = "2200";     // control, sub-ledger = DEPOSIT
    public static final String SERVICE_INCOME    = "4000";
    public static final String PENALTY_INCOME    = "4100";
    /** Hasil derma — berasingan daripada 4000 supaya penyata
     *  pendapatan boleh menjawab 'berapa daripada derma'. */
    public static final String DONATION_INCOME   = "4200";
    public static final String ACCOUNTS_PAYABLE  = "2000";    // control, invois pembekal
    public static final String BAD_DEBT_EXPENSE  = "5000";
    public static final String EXPENSE_UTILITY   = "5100";
    public static final String EXPENSE_MAINT     = "5200";
    public static final String EXPENSE_ADMIN     = "5300";
    public static final String EXPENSE_GENERAL   = "5900";    // lalai bila kategori tiada GL
    public static final String OPENING_EQUITY    = "3000";
}
