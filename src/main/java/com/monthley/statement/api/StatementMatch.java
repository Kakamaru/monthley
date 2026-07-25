package com.monthley.statement.api;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Padanan alokasi — dokumen ini dipadankan dengan dokumen itu.
 *
 * documentNo ialah SISI BERTENTANGAN: pada baris resit ia nombor invois,
 * pada baris invois ia nombor resit.
 *
 * Tempoh dibawa sebagai TARIKH, bukan nama. fi_period.name_ ialah teks
 * yang ditaip manusia — perangkap yang sama seperti prod_descr legacy
 * ("July, 2026" bersebelahan "2026"; "As At" bersebelahan "As at").
 * Penulis memformat daripada tarikh, jadi ia boleh disetempatkan dan
 * tidak pernah bergantung pada ejaan orang lain.
 *
 * TIDAK menyentuh lajur baki: alokasi ialah padanan, bukan pergerakan
 * baki (ADR 0009).
 */
public record StatementMatch(
        String documentNo,
        String productName,
        LocalDate periodStart,
        LocalDate periodEnd,
        BigDecimal amount) {
}
