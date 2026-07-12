package com.monthley.billing;

import com.monthley.billing.internal.ProrationCalculator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

class ProrationCalculatorTest {

    private static final BigDecimal RATE = new BigDecimal("100.00");
    private static final BigDecimal ONE  = BigDecimal.ONE;

    @Test
    @DisplayName("Tiada proration — harga penuh")
    void notProrated_fullAmount() {
        BigDecimal amt = ProrationCalculator.lineAmount(
                RATE, ONE, false, LocalDate.of(2026, 2, 15), null, YearMonth.of(2026, 2));

        assertThat(amt).isEqualByComparingTo("100.00");
    }

    // ---- KES UJIAN #6: hari SEBENAR bulan, bukan 30 tetap ----

    @Test
    @DisplayName("Februari 2026 (28 hari) — mula 15hb → 14/28")
    void proratedFebruary_actualDays() {
        BigDecimal amt = ProrationCalculator.lineAmount(
                RATE, ONE, true, LocalDate.of(2026, 2, 15), null, YearMonth.of(2026, 2));

        // 100 x 14/28 = 50.00   (kalau guna /30 → 46.67, SALAH)
        assertThat(amt).isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("Januari (31 hari) — mula 15hb → 17/31")
    void proratedJanuary_actualDays() {
        BigDecimal amt = ProrationCalculator.lineAmount(
                RATE, ONE, true, LocalDate.of(2026, 1, 15), null, YearMonth.of(2026, 1));

        // 100 x 17/31 = 54.8387... → 54.84
        assertThat(amt).isEqualByComparingTo("54.84");
    }

    @Test
    @DisplayName("Februari tahun lompat 2028 (29 hari)")
    void proratedLeapFebruary() {
        BigDecimal amt = ProrationCalculator.lineAmount(
                RATE, ONE, true, LocalDate.of(2028, 2, 15), null, YearMonth.of(2028, 2));

        // 100 x 15/29 = 51.7241... → 51.72
        assertThat(amt).isEqualByComparingTo("51.72");
    }

    @Test
    @DisplayName("Langganan liputi penuh bulan — harga penuh")
    void fullMonth_noProration() {
        BigDecimal amt = ProrationCalculator.lineAmount(
                RATE, ONE, true, LocalDate.of(2025, 1, 1), null, YearMonth.of(2026, 6));

        assertThat(amt).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("Langganan tamat tengah bulan")
    void endsMidMonth() {
        BigDecimal amt = ProrationCalculator.lineAmount(
                RATE, ONE, true,
                LocalDate.of(2025, 1, 1), LocalDate.of(2026, 4, 10), YearMonth.of(2026, 4));

        // 1-10 Apr = 10 hari / 30 → 33.33
        assertThat(amt).isEqualByComparingTo("33.33");
    }

    @Test
    @DisplayName("Kuantiti > 1")
    void withQuantity() {
        BigDecimal amt = ProrationCalculator.lineAmount(
                new BigDecimal("100.00"), new BigDecimal("2"), false,
                LocalDate.of(2026, 1, 1), null, YearMonth.of(2026, 7));

        assertThat(amt).isEqualByComparingTo("200.00");
    }

    @Test
    @DisplayName("Cukai dikira per baris")
    void taxPerLine() {
        BigDecimal tax = ProrationCalculator.taxAmount(
                new BigDecimal("100.00"), new BigDecimal("0.06"));

        assertThat(tax).isEqualByComparingTo("6.00");
    }
}
