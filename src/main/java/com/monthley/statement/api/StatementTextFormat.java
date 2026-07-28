package com.monthley.statement.api;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Peraturan format teks penyata — satu tempat (guard 6).
 *
 * Templat PDF dan pembina JSON kedua-duanya menggunakannya, jadi tempoh
 * yang sama muncul serupa di skrin dan pada kertas.
 */
public interface StatementTextFormat {

    /** Bulan penuh dipendekkan; sebahagian bulan menunjukkan tarikh. */
    String period(LocalDate start, LocalDate end);

    String date(LocalDate d);

    /** Negatif dalam kurungan, ikut konvensyen perakaunan. */
    String money(BigDecimal v);

    /** Tarikh DAN masa — 'Date of Issue' pada resit. */
    String dateTime(java.time.LocalDateTime d);

    /**
     * Kaedah bayaran dalam bahasa SP.
     *
     * Enum disimpan sebagai CASH/TRANSFER/FPX; pelanggan tidak sepatutnya
     * melihat nama enum pada resit.
     */
    String paymentMethod(String kod);
}
