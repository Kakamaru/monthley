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
}
