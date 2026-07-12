package com.monthley.billing.internal;

import com.monthley.shared.ChargeFrequency;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * Tentukan tempoh-tempoh yang perlu dijana untuk satu akaun.
 */
public final class PeriodResolver {

    public enum GenMode { CURRENT, PREPAID, POSTPAID }

    private PeriodResolver() {}

    public static YearMonth basePeriod(YearMonth runMonth, GenMode mode) {
        return switch (mode) {
            case CURRENT -> runMonth;
            case PREPAID -> runMonth.plusMonths(1);
            case POSTPAID -> runMonth.minusMonths(1);
        };
    }

    public static List<YearMonth> periodsFor(YearMonth base, ChargeFrequency accountFreq,
                                             YearMonth accountStart, YearMonth accountExpiry) {
        int count = switch (accountFreq == null ? ChargeFrequency.MONTHLY : accountFreq) {
            case MONTHLY -> 1;
            case QUARTERLY -> 3;
            case HALF_YEAR -> 6;
            case YEAR -> 12;
            default -> 1;
        };

        List<YearMonth> result = new ArrayList<>();
        YearMonth p = base;
        for (int i = 0; i < count; i++) {
            boolean afterStart = accountStart == null || !p.isBefore(accountStart);
            boolean beforeExpiry = accountExpiry == null || !p.isAfter(accountExpiry);
            if (afterStart && beforeExpiry) {
                result.add(p);
            }
            p = p.plusMonths(1);
        }
        return result;
    }
}
