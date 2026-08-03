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
 * menggerakkan lajur baki. originalAmount membawa nombor asalnya.
 */
public record StatementRow(
        LocalDate docDate,
        String docType,
        String docNo,
        String description,
        String remark,
        /**
         * Catatan baris — caj penggunaan sahaja.
         *
         * BERASINGAN daripada remark, yang memegang sebab pembatalan.
         * Berkongsi bermakna dokumen batal dengan caj penggunaan
         * menunjukkan salah satu sahaja, dan yang hilang tidak
         * kelihatan hilang.
         *
         * Hanya untuk dokumen SATU baris: invois berbilang baris
         * membawa catatan pada sub-barisnya.
         */
        String lineRemarks,
        boolean cancelled,
        /** Bila dibatalkan; null kalau tidak. */
        java.time.LocalDateTime cancelledAt,
        /** Siapa membatalkan — ID pengguna, bukan nama. */
        String cancelledBy,
        /**
         * Amaun ASAL dokumen (amount + tax).
         *
         * Untuk dokumen batal {@code amount} ialah sifar supaya lajur baki
         * tidak bergerak. Nombor asal tetap dipaparkan, dicoret — dokumen
         * bernombor tidak hilang, dan menyembunyikan berapa ia SEPATUTNYA
         * bermakna penyata tidak boleh diaudit.
         */
        BigDecimal originalAmount,
        BigDecimal amount,
        BigDecimal runningBalance,
        List<StatementMatch> matches) {
}
