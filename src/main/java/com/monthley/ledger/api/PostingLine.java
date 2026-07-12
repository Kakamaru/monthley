package com.monthley.ledger.api;

import java.math.BigDecimal;

/**
 * Satu leg journal. Tepat SATU daripada debit/credit mesti > 0.
 *
 * @param glAccountCode        kod dalam chart_of_accounts (cth "1100")
 * @param debit               amaun debit (0 jika ini leg kredit)
 * @param credit              amaun kredit (0 jika ini leg debit)
 * @param subLedgerAccountId  dimensi AR/Deposit — pelanggan mana (nullable)
 * @param productId           untuk pecahan income (nullable)
 */
public record PostingLine(
        String glAccountCode,
        BigDecimal debit,
        BigDecimal credit,
        Long subLedgerAccountId,
        Long productId,
        String description) {

    public static PostingLine debit(String glAccountCode, BigDecimal amount, Long subLedgerAccountId) {
        return new PostingLine(glAccountCode, amount, BigDecimal.ZERO, subLedgerAccountId, null, null);
    }

    public static PostingLine credit(String glAccountCode, BigDecimal amount, Long productId) {
        return new PostingLine(glAccountCode, BigDecimal.ZERO, amount, null, productId, null);
    }
}
