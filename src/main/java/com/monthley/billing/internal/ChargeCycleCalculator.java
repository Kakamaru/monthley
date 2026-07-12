package com.monthley.billing.internal;

import com.monthley.shared.ChargeFrequency;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

/**
 * Menentukan sama ada produk berulang patut dicaj pada tempoh tertentu.
 *
 * STATELESS — tiada {@code last_gen_dt} yang di-mutate.
 * Boleh dijalankan berulang kali, hasil sentiasa sama (idempotent).
 *
 * Ini membetulkan bug production: produk YEARLY dicaj setiap Januari
 * tanpa mengira bulan sebenar kitaran.
 */
public final class ChargeCycleCalculator {

    private ChargeCycleCalculator() {}

    /**
     * @param frequency         kekerapan produk
     * @param anchorMonth       1-12, atau null = ikut bulan mula langganan
     * @param subscriptionStart tarikh mula langganan
     * @param period            tempoh invois yang sedang dinilai
     */
    public static boolean shouldCharge(ChargeFrequency frequency,
                                       Integer anchorMonth,
                                       LocalDate subscriptionStart,
                                       YearMonth period) {

        // ONE_TIME & PER_USE dikendalikan berasingan (bukan kitaran kalendar)
        if (!frequency.isRecurring()) {
            return false;
        }

        YearMonth anchor = resolveAnchor(anchorMonth, subscriptionStart);
        long offset = ChronoUnit.MONTHS.between(anchor, period);

        return offset >= 0 && offset % frequency.months() == 0;
    }

    /**
     * Anchor = titik rujukan tetap kitaran.
     *
     * anchorMonth null  → bulan mula langganan (ulang tahun pelanggan)
     * anchorMonth = 8   → Ogos, tahun pertama pada/selepas mula langganan
     */
    static YearMonth resolveAnchor(Integer anchorMonth, LocalDate subscriptionStart) {
        YearMonth start = YearMonth.from(subscriptionStart);

        if (anchorMonth == null) {
            return start;
        }
        if (anchorMonth < 1 || anchorMonth > 12) {
            throw new IllegalArgumentException("anchor_month mesti 1-12, bukan: " + anchorMonth);
        }

        YearMonth candidate = YearMonth.of(start.getYear(), anchorMonth);
        return candidate.isBefore(start) ? candidate.plusYears(1) : candidate;
    }
}
