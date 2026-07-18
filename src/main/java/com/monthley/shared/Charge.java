package com.monthley.shared;

import java.time.LocalDate;

/**
 * Satu kitaran caj, dengan liputan sebenarnya.
 *
 * Perbezaan antara KITARAN dan LIPUTAN adalah teras enjin:
 *
 *   kitaran  — tempoh penuh yang period_id ini wakili (cth Jan–Dis 2026)
 *   liputan  — bahagian yang benar-benar dicaj (cth 15 Jun – 31 Dis 2026)
 *
 * Kitaran ialah PENYEBUT proration. Liputan ialah PENGANGKA.
 * Kedua-dua disimpan pada baris dokumen supaya amaun boleh dikira semula
 * dan diaudit — legacy tidak, dan itulah sebabnya jejak auditnya pecah.
 *
 * @param periodId       label period (lihat {@link PeriodIds})
 * @param cycleStart     mula kitaran penuh
 * @param cycleEnd       tamat kitaran penuh (inklusif)
 * @param coverageStart  mula liputan sebenar
 * @param coverageEnd    tamat liputan sebenar (inklusif)
 */
public record Charge(long periodId,
                     LocalDate cycleStart,
                     LocalDate cycleEnd,
                     LocalDate coverageStart,
                     LocalDate coverageEnd) {

    public Charge {
        if (cycleEnd.isBefore(cycleStart)) {
            throw new IllegalArgumentException(
                    "cycleEnd (%s) sebelum cycleStart (%s)".formatted(cycleEnd, cycleStart));
        }
        if (coverageEnd.isBefore(coverageStart)) {
            throw new IllegalArgumentException(
                    "coverageEnd (%s) sebelum coverageStart (%s)".formatted(coverageEnd, coverageStart));
        }
    }

    /** Benar kalau liputan meliputi kitaran penuh — tiada proration perlu. */
    public boolean isFullCycle() {
        return !coverageStart.isAfter(cycleStart) && !coverageEnd.isBefore(cycleEnd);
    }
}
