package com.monthley.billing.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

/**
 * Kiraan amaun baris invois:
 *  - proration ikut HARI SEBENAR bulan (Feb=28/29, Jan=31)
 *  - pembundaran ke atas ke denominasi (set_curr_min_denom), PER BARIS
 *
 * Semua wang BigDecimal, HALF_UP 2 titik perpuluhan (kecuali pembundaran denom = CEILING).
 */
public final class ProrationCalculator {

    private static final int SCALE = 2;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private ProrationCalculator() {}

    /**
     * Amaun baris, dengan proration + pembundaran denom (per baris).
     *
     * @param minDenom denominasi min (cth 0.05). null / 0 = tiada pembundaran.
     */
    public static BigDecimal lineAmount(BigDecimal rate,
                                        BigDecimal quantity,
                                        boolean prorated,
                                        LocalDate subStart,
                                        LocalDate subEnd,
                                        YearMonth period,
                                        BigDecimal minDenom) {

        BigDecimal base = proratedAmount(rate, quantity, prorated, subStart, subEnd, period);
        return roundUpToDenom(base, minDenom);
    }

    /** Overload tanpa pembundaran (kekal untuk keserasian). */
    public static BigDecimal lineAmount(BigDecimal rate, BigDecimal quantity, boolean prorated,
                                        LocalDate subStart, LocalDate subEnd, YearMonth period) {
        return lineAmount(rate, quantity, prorated, subStart, subEnd, period, null);
    }

    private static BigDecimal proratedAmount(BigDecimal rate, BigDecimal quantity, boolean prorated,
                                             LocalDate subStart, LocalDate subEnd, YearMonth period) {
        BigDecimal full = rate.multiply(quantity);
        if (!prorated) {
            return full.setScale(SCALE, ROUNDING);
        }

        LocalDate periodStart = period.atDay(1);
        LocalDate periodEnd = period.atEndOfMonth();
        LocalDate from = subStart.isAfter(periodStart) ? subStart : periodStart;
        LocalDate to = (subEnd != null && subEnd.isBefore(periodEnd)) ? subEnd : periodEnd;

        if (to.isBefore(from)) {
            return BigDecimal.ZERO.setScale(SCALE);
        }

        long billableDays = ChronoUnit.DAYS.between(from, to) + 1;
        int daysInMonth = period.lengthOfMonth();

        if (billableDays >= daysInMonth) {
            return full.setScale(SCALE, ROUNDING);
        }
        return full.multiply(BigDecimal.valueOf(billableDays))
                   .divide(BigDecimal.valueOf(daysInMonth), SCALE, ROUNDING);
    }

    /**
     * Bulatkan KE ATAS ke gandaan minDenom (cth 0.05).
     * 67.31 → 67.35, 67.37 → 67.40, 67.35 → 67.35 (kekal).
     */
    public static BigDecimal roundUpToDenom(BigDecimal amount, BigDecimal minDenom) {
        if (minDenom == null || minDenom.signum() <= 0) {
            return amount.setScale(SCALE, ROUNDING);
        }
        BigDecimal units = amount.divide(minDenom, 0, RoundingMode.CEILING);
        return units.multiply(minDenom).setScale(SCALE, ROUNDING);
    }

    /** Cukai per baris. */
    public static BigDecimal taxAmount(BigDecimal lineAmount, BigDecimal taxRate) {
        if (taxRate == null || taxRate.signum() == 0) {
            return BigDecimal.ZERO.setScale(SCALE);
        }
        return lineAmount.multiply(taxRate).setScale(SCALE, ROUNDING);
    }
}
