package com.monthley.ledger.api;

import java.math.BigDecimal;

public class UnbalancedJournalException extends RuntimeException {
    public UnbalancedJournalException(BigDecimal debit, BigDecimal credit) {
        super("Journal tidak seimbang: debit=" + debit + " credit=" + credit);
    }
}
