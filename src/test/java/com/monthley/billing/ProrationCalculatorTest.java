package com.monthley.billing;

import com.monthley.billing.internal.ProrationCalculator;
import com.monthley.shared.Charge;
import com.monthley.shared.PeriodIds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Beroperasi atas Charge, bukan YearMonth — jadi kes QR/HF/YR boleh diuji.
 *
 *   amount = ROUND(unit_price x quantity x proration_ratio, 2)
 *
 * Rujukan: docs/domain/billing-rules.md §7
 */
class ProrationCalculatorTest {

    private static final BigDecimal RATE = new BigDecimal("100.00");
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final Set<BigDecimal> UNUSED = Set.of();

    // ── Bantuan ──────────────────────────────────────────────────────

    /** Charge bulanan, liputan dipotong oleh tarikh langganan. */
    private static Charge month(YearMonth ym, LocalDate subStart, LocalDate subEnd) {
        LocalDate cs = ym.atDay(1);
        LocalDate ce = ym.atEndOfMonth();
        LocalDate covS = (subStart != null && subStart.isAfter(cs)) ? subStart : cs;
        LocalDate covE = (subEnd != null && subEnd.isBefore(ce)) ? subEnd : ce;
        return new Charge(PeriodIds.ofMonth(ym), cs, ce, covS, covE);
    }

    private static Charge quarter(int year, int q) {
        LocalDate cs = LocalDate.of(year, (q - 1) * 3 + 1, 1);
        LocalDate ce = cs.plusMonths(3).minusDays(1);
        return new Charge(PeriodIds.ofQuarter(year, q), cs, ce, cs, ce);
    }

    private static Charge year(int year) {
        LocalDate cs = LocalDate.of(year, 1, 1);
        LocalDate ce = LocalDate.of(year, 12, 31);
        return new Charge(PeriodIds.ofYear(year), cs, ce, cs, ce);
    }

    private static BigDecimal amount(BigDecimal rate, BigDecimal qty,
                                     boolean prorated, Charge c, Set<Long> excluded) {
        BigDecimal ratio = ProrationCalculator.prorationRatio(prorated, c, excluded);
        return ProrationCalculator.lineAmount(rate, qty, ratio, null);
    }

    // ── Proration tarikh — hari sebenar ──────────────────────────────

    @Nested
    @DisplayName("Proration tarikh — HARI SEBENAR bulan, bukan 30 tetap")
    class DateProration {

        @Test
        @DisplayName("Tiada proration — harga penuh walaupun masuk tengah bulan")
        void notProrated_fullAmount() {
            Charge c = month(YearMonth.of(2026, 2), LocalDate.of(2026, 2, 15), null);
            assertThat(amount(RATE, ONE, false, c, Set.of())).isEqualByComparingTo("100.00");
        }

        @Test
        @DisplayName("Februari 2026 (28 hari) — mula 15hb → 14/28 = 50.00")
        void proratedFebruary_actualDays() {
            Charge c = month(YearMonth.of(2026, 2), LocalDate.of(2026, 2, 15), null);
            // 100 x 14/28 = 50.00   (kalau guna /30 → 46.67, SALAH)
            assertThat(amount(RATE, ONE, true, c, Set.of())).isEqualByComparingTo("50.00");
        }

        @Test
        @DisplayName("Januari (31 hari) — mula 15hb → 17/31 = 54.84")
        void proratedJanuary_actualDays() {
            Charge c = month(YearMonth.of(2026, 1), LocalDate.of(2026, 1, 15), null);
            assertThat(amount(RATE, ONE, true, c, Set.of())).isEqualByComparingTo("54.84");
        }

        @Test
        @DisplayName("Februari tahun lompat 2028 (29 hari) → 15/29 = 51.72")
        void proratedLeapFebruary() {
            Charge c = month(YearMonth.of(2028, 2), LocalDate.of(2028, 2, 15), null);
            assertThat(amount(RATE, ONE, true, c, Set.of())).isEqualByComparingTo("51.72");
        }

        @Test
        @DisplayName("Langganan liputi penuh bulan — harga penuh")
        void fullMonth_noProration() {
            Charge c = month(YearMonth.of(2026, 6), LocalDate.of(2025, 1, 1), null);
            assertThat(amount(RATE, ONE, true, c, Set.of())).isEqualByComparingTo("100.00");
        }

        @Test
        @DisplayName("Langganan tamat tengah bulan → 10/30 = 33.33")
        void endsMidMonth() {
            Charge c = month(YearMonth.of(2026, 4),
                    LocalDate.of(2025, 1, 1), LocalDate.of(2026, 4, 10));
            assertThat(amount(RATE, ONE, true, c, Set.of())).isEqualByComparingTo("33.33");
        }

        @Test
        @DisplayName("Kuantiti > 1")
        void withQuantity() {
            Charge c = month(YearMonth.of(2026, 7), LocalDate.of(2026, 1, 1), null);
            assertThat(amount(RATE, new BigDecimal("2"), false, c, Set.of()))
                    .isEqualByComparingTo("200.00");
        }

        @Test
        @DisplayName("Proration atas kitaran TAHUNAN — Jun 15 hingga Dis 31 = 200/365")
        void yearlyCycleProration() {
            Charge c = new Charge(PeriodIds.ofYear(2026),
                    LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                    LocalDate.of(2026, 6, 15), LocalDate.of(2026, 12, 31));
            // 350 x 200/365 = 191.7808 → 191.78 (billing-rules.md §6 kes "Kalendar")
            assertThat(amount(new BigDecimal("350.00"), ONE, true, c, Set.of()))
                    .isEqualByComparingTo("191.78");
        }
    }

    // ── Proration exclude — bilangan bulan ───────────────────────────

    @Nested
    @DisplayName("Proration exclude — BILANGAN BULAN, bukan hari")
    class ExcludeProration {

        private static final long JULAI_2026 = 2026230700L;

        @Test
        @DisplayName("MO Julai dikecualikan → ratio 0, baris gugur")
        void monthlyExcluded_zero() {
            Charge c = month(YearMonth.of(2026, 7), null, null);
            BigDecimal ratio = ProrationCalculator.prorationRatio(false, c, Set.of(JULAI_2026));
            assertThat(ratio).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("QR Q3 RM240, Julai dikecualikan → 240 ÷ 3 × 2 = 160.00")
        void quarterlyExcluded_twoThirds() {
            Charge c = quarter(2026, 3);
            // Ikut BULAN. Ikut hari akan bagi 240 x 61/92 = 159.13 — SALAH.
            assertThat(amount(new BigDecimal("240.00"), ONE, false, c, Set.of(JULAI_2026)))
                    .isEqualByComparingTo("160.00");
        }

        @Test
        @DisplayName("YR 2026 RM350, Julai dikecualikan → 350 ÷ 12 × 11 = 320.83")
        void yearlyExcluded_elevenTwelfths() {
            assertThat(amount(new BigDecimal("350.00"), ONE, false, year(2026), Set.of(JULAI_2026)))
                    .isEqualByComparingTo("320.83");
        }

        @Test
        @DisplayName("Exclude terpakai walaupun prorated = false")
        void excludeIgnoresProratedFlag() {
            Charge c = quarter(2026, 3);
            BigDecimal off = ProrationCalculator.prorationRatio(false, c, Set.of(JULAI_2026));
            BigDecimal on = ProrationCalculator.prorationRatio(true, c, Set.of(JULAI_2026));
            assertThat(off).isEqualByComparingTo(on);
        }

        @Test
        @DisplayName("Dua bulan dikecualikan → 240 ÷ 3 × 1 = 80.00")
        void twoMonthsExcluded() {
            Charge c = quarter(2026, 3);
            Set<Long> excl = Set.of(JULAI_2026, 2026230800L);   // Julai + Ogos
            assertThat(amount(new BigDecimal("240.00"), ONE, false, c, excl))
                    .isEqualByComparingTo("80.00");
        }

        @Test
        @DisplayName("Bulan dikecualikan di luar kitaran → tiada kesan")
        void excludeOutsideCycle_noEffect() {
            Charge c = quarter(2026, 1);   // Jan-Mac
            assertThat(amount(new BigDecimal("240.00"), ONE, false, c, Set.of(JULAI_2026)))
                    .isEqualByComparingTo("240.00");
        }

        @Test
        @DisplayName("Kedua-dua ratio digabung — mula tengah kitaran DAN bulan dikecualikan")
        void bothRatiosCompound() {
            Charge c = new Charge(PeriodIds.ofYear(2026),
                    LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                    LocalDate.of(2026, 6, 15), LocalDate.of(2026, 12, 31));
            // 350 x (200/365) x (11/12) = 175.7994 → 175.80
            // Penyebut berbeza (hari vs bulan) — anggaran, diterima secara sedar.
            assertThat(amount(new BigDecimal("350.00"), ONE, true, c, Set.of(JULAI_2026)))
                    .isEqualByComparingTo("175.80");
        }
    }

    // ── Pembundaran denominasi ───────────────────────────────────────

    @Nested
    @DisplayName("Pembundaran denominasi — KE ATAS, per baris")
    class DenomRounding {

        @Test
        @DisplayName("Ratio tidak menyebabkan pembundaran palsu")
        void ratioDoesNotTriggerFalseRounding() {
            // 240 x 0.66666667 = 160.0000008. CEILING terus atas nilai itu
            // akan bagi 160.05. Bundar 2 t.p. DAHULU.
            Charge c = quarter(2026, 3);
            BigDecimal ratio = ProrationCalculator.prorationRatio(false, c, Set.of(2026230700L));
            BigDecimal amt = ProrationCalculator.lineAmount(
                    new BigDecimal("240.00"), ONE, ratio, new BigDecimal("0.05"));
            assertThat(amt).isEqualByComparingTo("160.00");
        }

        @Test
        @DisplayName("67.31 → 67.35 dengan denom 0.05")
        void roundsUp() {
            assertThat(ProrationCalculator.roundUpToDenom(
                    new BigDecimal("67.31"), new BigDecimal("0.05")))
                    .isEqualByComparingTo("67.35");
        }

        @Test
        @DisplayName("Sudah atas denominasi — kekal")
        void alreadyOnDenom() {
            assertThat(ProrationCalculator.roundUpToDenom(
                    new BigDecimal("67.35"), new BigDecimal("0.05")))
                    .isEqualByComparingTo("67.35");
        }

        @Test
        @DisplayName("denom null → 2 t.p. biasa")
        void nullDenom() {
            assertThat(ProrationCalculator.roundUpToDenom(new BigDecimal("67.316"), null))
                    .isEqualByComparingTo("67.32");
        }
    }

    // ── Cukai ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Cukai dikira per baris")
    void taxPerLine() {
        assertThat(ProrationCalculator.taxAmount(new BigDecimal("100.00"), new BigDecimal("0.06")))
                .isEqualByComparingTo("6.00");
    }

    @Test
    @DisplayName("Kadar cukai null → sifar")
    void nullTaxRate() {
        assertThat(ProrationCalculator.taxAmount(new BigDecimal("100.00"), null))
                .isEqualByComparingTo("0.00");
    }
}
