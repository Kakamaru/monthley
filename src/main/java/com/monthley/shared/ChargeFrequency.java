package com.monthley.shared;

/** Kekerapan caj. NON_RECURRING sengaja tiada — tidak digunakan. */
public enum ChargeFrequency {
    ONE_TIME(0),
    MONTHLY(1),
    QUARTERLY(3),
    HALF_YEAR(6),
    YEAR(12),
    PER_USE(0);

    private final int months;

    ChargeFrequency(int months) { this.months = months; }

    /** Bilangan bulan satu kitaran. 0 = bukan kitaran kalendar. */
    public int months() { return months; }

    public boolean isRecurring() { return months > 0; }
}
