package com.monthley.ledger.internal;

import com.monthley.ledger.api.GlAccounts;
import com.monthley.ledger.internal.ChartOfAccount.AccountType;
import com.monthley.ledger.internal.ChartOfAccount.NormalSide;
import com.monthley.ledger.internal.ChartOfAccount.SubLedgerType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Cipta carta akaun standard bila SP baru di-onboard.
 * Dipanggil sekali per SP (idempotent — skip kalau dah ada).
 */
@Component
public class ChartOfAccountSeeder {

    private final ChartOfAccountRepository repo;

    ChartOfAccountSeeder(ChartOfAccountRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public void seedFor(String spCode) {
        if (repo.existsBySpCode(spCode)) {
            return;   // idempotent
        }

        List<ChartOfAccount> coa = List.of(
            acc(spCode, GlAccounts.BANK,               "Bank / Tunai",            AccountType.ASSET,     NormalSide.DEBIT,  false, null),
            acc(spCode, GlAccounts.MP_CLEARING,        "Monthley Pay Clearing",   AccountType.ASSET,     NormalSide.DEBIT,  false, null),
            acc(spCode, GlAccounts.ACCOUNTS_RECEIVABLE,"Accounts Receivable",     AccountType.ASSET,     NormalSide.DEBIT,  true,  SubLedgerType.AR),
            acc(spCode, GlAccounts.TAX_PAYABLE,        "SST Payable",             AccountType.LIABILITY, NormalSide.CREDIT, false, null),
            acc(spCode, GlAccounts.CUSTOMER_DEPOSIT,   "Customer Deposit",        AccountType.LIABILITY, NormalSide.CREDIT, true,  SubLedgerType.DEPOSIT),
            acc(spCode, GlAccounts.OPENING_EQUITY,     "Opening Balance Equity",  AccountType.EQUITY,    NormalSide.CREDIT, false, null),
            acc(spCode, GlAccounts.SERVICE_INCOME,     "Service Income",          AccountType.INCOME,    NormalSide.CREDIT, false, null),
            acc(spCode, GlAccounts.PENALTY_INCOME,     "Late Penalty Income",     AccountType.INCOME,    NormalSide.CREDIT, false, null),
            acc(spCode, GlAccounts.BAD_DEBT_EXPENSE,   "Bad Debt Expense",        AccountType.EXPENSE,   NormalSide.DEBIT,  false, null),

            // Perbelanjaan (modul Expenses, ADR 0017). GL diletak pada kategori
            // INDUK, bukan jenis: Untung Rugi menunjukkan tiga baris
            // perbelanjaan, bukan berpuluh. Pecahan sehingga 'Elektrik (TNB)'
            // datang dari laporan perbelanjaan, bukan dari lejar.
            acc(spCode, GlAccounts.ACCOUNTS_PAYABLE,   "Akaun Belum Bayar",       AccountType.LIABILITY, NormalSide.CREDIT, false, null),
            acc(spCode, GlAccounts.EXPENSE_UTILITY,    "Utiliti",                 AccountType.EXPENSE,   NormalSide.DEBIT,  false, null),
            acc(spCode, GlAccounts.EXPENSE_MAINT,      "Penyelenggaraan & Pembaikan", AccountType.EXPENSE, NormalSide.DEBIT, false, null),
            acc(spCode, GlAccounts.EXPENSE_ADMIN,      "Pentadbiran",             AccountType.EXPENSE,   NormalSide.DEBIT,  false, null),
            acc(spCode, GlAccounts.EXPENSE_GENERAL,    "Perbelanjaan Am",         AccountType.EXPENSE,   NormalSide.DEBIT,  false, null)
        );

        repo.saveAll(coa);
    }

    private ChartOfAccount acc(String sp, String code, String name,
                               AccountType type, NormalSide side,
                               boolean control, SubLedgerType sub) {
        return new ChartOfAccount(sp, code, name, type, side, control, sub);
    }
}
