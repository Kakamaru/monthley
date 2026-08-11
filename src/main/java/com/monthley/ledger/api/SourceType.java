package com.monthley.ledger.api;

public enum SourceType {
    INVOICE, PAYMENT, PENALTY, CANCELLATION, WRITEOFF, ADJUSTMENT, OPENING,

    /**
     * Modul Perbelanjaan. Jenis berasingan daripada INVOICE/PAYMENT kerana
     * invois jualan dan invois belian bertentangan arah — laporan yang
     * menapis source_type='INVOICE' akan mencampurkan keduanya.
     */
    EXP_INVOICE, EXP_PAYMENT, EXP_CASH
}
