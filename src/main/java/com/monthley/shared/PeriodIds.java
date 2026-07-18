package com.monthley.shared;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * Penukar period_id — skema Monthley asal:
 *   period_id = tahun*1000000 + H*100000 + Q*10000 + bulan*100
 *
 * Fungsi TULEN. Enjin bil tak baca jadual fi_period; fi_period hanya
 * rujukan (dropdown, join laporan, pemetaan migrasi). Kalau data
 * fi_period rosak, bil tetap betul.
 */
public final class PeriodIds {

    private PeriodIds() {}

    public static long ofYear(int year)            { return year * 1_000_000L; }
    public static long ofHalf(int year, int half)  { return year * 1_000_000L + half * 100_000L; }

    public static long ofQuarter(int year, int q) {
        return year * 1_000_000L + (q <= 2 ? 1 : 2) * 100_000L + q * 10_000L;
    }

    public static long ofMonth(int year, int month) {
        return year * 1_000_000L
             + (month <= 6 ? 1 : 2) * 100_000L
             + ((month + 2) / 3) * 10_000L
             + month * 100L;
    }

    public static long ofMonth(YearMonth ym) { return ofMonth(ym.getYear(), ym.getMonthValue()); }

    /**
     * period_id untuk kitaran bermula {@code cycleStart}, pada aras {@code freq}.
     * Untuk kitaran ber-anchor (cth tahunan mula Ogos), id ikut TAHUN MULA —
     * tarikh liputan sebenar dibawa oleh coverage_start/coverage_end pada baris.
     */
    public static long of(LocalDate cycleStart, ChargeFrequency freq) {
        int y = cycleStart.getYear(), m = cycleStart.getMonthValue();
        return switch (freq == null ? ChargeFrequency.MONTHLY : freq) {
            case YEAR      -> ofYear(y);
            case HALF_YEAR -> ofHalf(y, m <= 6 ? 1 : 2);
            case QUARTERLY -> ofQuarter(y, (m + 2) / 3);
            default        -> ofMonth(y, m);
        };
    }

    public static YearMonth toYearMonth(long periodId) {
        int month = (int) ((periodId % 10_000) / 100);
        return YearMonth.of((int) (periodId / 1_000_000), month == 0 ? 1 : month);
    }

    public static int cycleMonths(ChargeFrequency freq) {
        return switch (freq == null ? ChargeFrequency.MONTHLY : freq) {
            case QUARTERLY -> 3;
            case HALF_YEAR -> 6;
            case YEAR      -> 12;
            default        -> 1;
        };
    }
}
