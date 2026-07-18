package com.monthley.catalog.api;

import com.monthley.shared.ChargeFrequency;
import java.math.BigDecimal;

/**
 * Pandangan produk untuk modul lain (billing). Read-only snapshot —
 * modul luar tak sentuh entiti dalaman catalog.
 *
 * @param anchorMonth 1–12 = bulan kitaran bermula (cth 8 = Ogos → kitaran
 *                    tahunan Ogos–Julai). null = Januari, sejajar kalendar.
 *                    Tidak relevan untuk MONTHLY. Rujuk billing-rules.md §6
 * @param prorated    false = caj penuh walaupun masuk tengah kitaran.
 *                    99% produk production adalah false.
 */
public record ProductView(
        Long id,
        String spCode,
        String code,
        String name,
        ChargeFrequency chargeFrequency,
        Integer anchorMonth,
        BigDecimal unitRate,
        Long incomeGlAccountId,
        boolean prorated,
        boolean latePenalty,
        boolean mandatory) {
}
