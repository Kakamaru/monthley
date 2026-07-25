package com.monthley.statement.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Satu baris penyata = SATU DOKUMEN (bukan satu baris ledger, bukan
 * satu alokasi).
 *
 * amount bertanda: positif menaikkan baki, negatif menurunkannya.
 * Dokumen batal mempunyai amount SIFAR — ia dipaparkan tetapi tidak
 * menggerakkan lajur baki.
 */
public record StatementRow(
        LocalDate docDate,
        String docType,
        String docNo,
        String description,
        String remark,
        boolean cancelled,
        BigDecimal amount,
        BigDecimal runningBalance,
        List<StatementMatch> matches) {
}
