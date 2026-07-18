package com.monthley.billing.internal;

import com.monthley.shared.Charge;
import com.monthley.shared.PeriodIds;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Set;

/**
 * Kiraan amaun baris invois.
 *
 *   amount = ROUND(unit_price x quantity x proration_ratio, 2)
 *
 * Ratio disimpan pada baris (8 t.p.) supaya amaun boleh dikira semula dan
 * diaudit. Kuantiti kekal ASAL — "2 unit" ialah 2, bukan 1.3334.
 *
 * DUA mekanisme proration, penyebut BERBEZA:
 *
 *   Tarikh mula/tamat  -> HARI SEBENAR kitaran (Feb=28/29, Jan=31)
 *   Exclude period     -> BILANGAN BULAN dalam kitaran
 *
 * Bukan pilihan gaya. Exclude prorate ikut bulan: QR RM240 dengan Julai
 * dikecualikan = 240 / 3 x 2 = RM160, bukan 240 x 61/92 = RM159.13.
 *
 * Beroperasi atas {@link Charge}, bukan YearMonth — jadi boleh handle baris
 * aras QR/HF/YR. Production ada 6,204 baris begitu.
 *
 * Rujukan: docs/domain/billing-rules.md §7
 *          docs/domain/legacy-generator-analysis.md §3.2, §4.3, §5.1
 */
public final class ProrationCalculator {

    /** Skala wang. */
    private static final int SCALE = 2;

    /** Skala ratio tersimpan — padan financial_document_line.proration_ratio. */
    public static final int RATIO_SCALE = 8;

    /** Skala kerja untuk pembahagian perantaraan. */
    private static final int WORK_SCALE = 12;

    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private ProrationCalculator() {}

    // ── Ratio ────────────────────────────────────────────────────────

    /**
     * Ratio proration untuk satu baris = ratio tarikh x ratio exclude.
     *
     * Kedua-dua boleh &lt; 1 serentak (akaun masuk tengah kitaran DAN ada bulan
     * dikecualikan). Penyebutnya berbeza, jadi hasil darab adalah anggaran —
     * diterima secara sedar. Kes jarang.
     *
     * @return 0 hingga 1, pada {@link #RATIO_SCALE} t.p.
     */
    public static BigDecimal prorationRatio(boolean prorated,
                                            Charge charge,
                                            Set<Long> excludedPeriodIds) {
        return dateRatio(prorated, charge)
                .multiply(excludeRatio(charge, excludedPeriodIds))
                .setScale(RATIO_SCALE, ROUNDING);
    }

    /**
     * Ratio proration tarikh — hari liputan / hari kitaran.
     *
     * prorated = false -> sentiasa 1 (caj penuh walaupun masuk tengah kitaran).
     * 99% produk production adalah false.
     */
    static BigDecimal dateRatio(boolean prorated, Charge charge) {
        if (!prorated || charge.isFullCycle()) {
            return BigDecimal.ONE;
        }
        long coverage = ChronoUnit.DAYS.between(charge.coverageStart(), charge.coverageEnd()) + 1;
        long cycle = ChronoUnit.DAYS.between(charge.cycleStart(), charge.cycleEnd()) + 1;

        if (coverage >= cycle) return BigDecimal.ONE;
        if (coverage <= 0) return BigDecimal.ZERO;

        return BigDecimal.valueOf(coverage)
                .divide(BigDecimal.valueOf(cycle), WORK_SCALE, ROUNDING);
    }

    /**
     * Ratio exclude — (bulan kitaran - bulan dikecualikan) / bulan kitaran.
     *
     * Seragam untuk semua aras:
     *   MO,  Julai dikecualikan -> (1-1)/1  = 0     -> baris gugur
     *   QR Q3, Julai            -> (3-1)/3  = 2/3   -> RM240 -> RM160
     *   YR 2026, Julai          -> (12-1)/12        -> RM350 -> RM320.83
     *
     * Terpakai TIDAK KIRA bendera prorated — exclude ialah keputusan SP
     * (bulan cuti, tempoh percuma), bukan kemasukan tengah-kitaran.
     */
    static BigDecimal excludeRatio(Charge charge, Set<Long> excludedPeriodIds) {
        if (excludedPeriodIds == null || excludedPeriodIds.isEmpty()) {
            return BigDecimal.ONE;
        }
        int total = 0;
        int excluded = 0;

        YearMonth ym = YearMonth.from(charge.cycleStart());
        YearMonth end = YearMonth.from(charge.cycleEnd());
        while (!ym.isAfter(end)) {
            total++;
            if (excludedPeriodIds.contains(PeriodIds.ofMonth(ym))) excluded++;
            ym = ym.plusMonths(1);
        }

        if (excluded == 0) return BigDecimal.ONE;
        if (excluded >= total) return BigDecimal.ZERO;

        return BigDecimal.valueOf(total - excluded)
                .divide(BigDecimal.valueOf(total), WORK_SCALE, ROUNDING);
    }

    // ── Amaun ────────────────────────────────────────────────────────

    /**
     * amount = ROUND(rate x quantity x ratio, 2), kemudian dibundar ke atas
     * ke denominasi terkecil.
     *
     * Pembundaran 2 t.p. berlaku DAHULU: 240 x 0.66666667 = 160.0000008, dan
     * CEILING terus atas nilai itu akan bagi 160.05 dengan denom 0.05.
     */
    public static BigDecimal lineAmount(BigDecimal rate,
                                        BigDecimal quantity,
                                        BigDecimal ratio,
                                        BigDecimal minDenom) {
        BigDecimal raw = rate.multiply(quantity).multiply(ratio).setScale(SCALE, ROUNDING);
        return roundUpToDenom(raw, minDenom);
    }

    /**
     * Bundar KE ATAS ke gandaan minDenom.
     * 67.31 -> 67.35, 67.37 -> 67.40, 67.35 -> 67.35 (kekal).
     */
    public static BigDecimal roundUpToDenom(BigDecimal amount, BigDecimal minDenom) {
        if (minDenom == null || minDenom.signum() <= 0) {
            return amount.setScale(SCALE, ROUNDING);
        }
        BigDecimal units = amount.divide(minDenom, 0, RoundingMode.CEILING);
        return units.multiply(minDenom).setScale(SCALE, ROUNDING);
    }

    /** Cukai per baris, atas amaun yang sudah dibundar. */
    public static BigDecimal taxAmount(BigDecimal lineAmount, BigDecimal taxRate) {
        if (taxRate == null || taxRate.signum() == 0) {
            return BigDecimal.ZERO.setScale(SCALE);
        }
        return lineAmount.multiply(taxRate).setScale(SCALE, ROUNDING);
    }
}
