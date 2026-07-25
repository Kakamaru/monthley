package com.monthley.statement.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Model penyata — SATU struktur, dua penulis (PDF, XLSX).
 *
 * INVARIAN:
 *   closingBalance == openingBalance + SUM(rows.amount)
 *   untuk julat penuh, closingBalance == account_balance.balance
 *   penutup tahun N == pembukaan tahun N+1
 *
 * arrears ialah TUNGGAKAN: tidak boleh negatif. balance boleh.
 * Legacy memaparkan kedua-duanya bersebelahan tanpa membezakannya.
 */
public record StatementModel(
        String spCode,
        long accountId,
        LocalDate from,
        LocalDate to,
        BigDecimal openingBalance,
        List<StatementRow> rows,
        BigDecimal closingBalance,
        BigDecimal arrears) {
}
