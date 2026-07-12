package com.monthley.billing;

import com.monthley.billing.internal.ChargeCycleCalculator;
import com.monthley.shared.ChargeFrequency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

class ChargeCycleCalculatorTest {

    private static final LocalDate MAC_2026 = LocalDate.of(2026, 3, 1);

    // ---- BUG PRODUCTION #4: produk YEARLY dicaj setiap Januari ----

    @Test
    @DisplayName("Insurance tahunan anchor Ogos — TIDAK dicaj pada Januari")
    void yearlyAnchorAugust_notChargedInJanuary() {
        boolean charge = ChargeCycleCalculator.shouldCharge(
                ChargeFrequency.YEAR, 8, MAC_2026, YearMonth.of(2027, 1));

        assertThat(charge).isFalse();
    }

    @Test
    @DisplayName("Insurance tahunan anchor Ogos — dicaj pada Ogos")
    void yearlyAnchorAugust_chargedInAugust() {
        assertThat(ChargeCycleCalculator.shouldCharge(
                ChargeFrequency.YEAR, 8, MAC_2026, YearMonth.of(2026, 8))).isTrue();

        assertThat(ChargeCycleCalculator.shouldCharge(
                ChargeFrequency.YEAR, 8, MAC_2026, YearMonth.of(2027, 8))).isTrue();
    }

    @Test
    @DisplayName("Sebelum anchor — tiada caj")
    void beforeAnchor_noCharge() {
        assertThat(ChargeCycleCalculator.shouldCharge(
                ChargeFrequency.YEAR, 8, MAC_2026, YearMonth.of(2026, 5))).isFalse();
    }

    // ---- anchor null = ulang tahun langganan ----

    @Test
    @DisplayName("anchor NULL — ikut bulan mula langganan")
    void nullAnchor_followsSubscriptionStart() {
        LocalDate start = LocalDate.of(2026, 3, 15);

        assertThat(ChargeCycleCalculator.shouldCharge(
                ChargeFrequency.YEAR, null, start, YearMonth.of(2026, 3))).isTrue();
        assertThat(ChargeCycleCalculator.shouldCharge(
                ChargeFrequency.YEAR, null, start, YearMonth.of(2027, 3))).isTrue();
        assertThat(ChargeCycleCalculator.shouldCharge(
                ChargeFrequency.YEAR, null, start, YearMonth.of(2027, 1))).isFalse();
    }

    // ---- quarterly ----

    @Test
    @DisplayName("Suku tahun anchor Feb — Feb, Mei, Ogos, Nov")
    void quarterlyAnchorFebruary() {
        LocalDate start = LocalDate.of(2026, 1, 1);

        assertThat(ChargeCycleCalculator.shouldCharge(
                ChargeFrequency.QUARTERLY, 2, start, YearMonth.of(2026, 2))).isTrue();
        assertThat(ChargeCycleCalculator.shouldCharge(
                ChargeFrequency.QUARTERLY, 2, start, YearMonth.of(2026, 5))).isTrue();
        assertThat(ChargeCycleCalculator.shouldCharge(
                ChargeFrequency.QUARTERLY, 2, start, YearMonth.of(2026, 8))).isTrue();
        assertThat(ChargeCycleCalculator.shouldCharge(
                ChargeFrequency.QUARTERLY, 2, start, YearMonth.of(2026, 11))).isTrue();

        assertThat(ChargeCycleCalculator.shouldCharge(
                ChargeFrequency.QUARTERLY, 2, start, YearMonth.of(2026, 3))).isFalse();
    }

    // ---- monthly ----

    @Test
    @DisplayName("Bulanan — setiap bulan selepas mula")
    void monthly_everyMonth() {
        LocalDate start = LocalDate.of(2026, 3, 1);
        for (int m = 3; m <= 12; m++) {
            assertThat(ChargeCycleCalculator.shouldCharge(
                    ChargeFrequency.MONTHLY, null, start, YearMonth.of(2026, m)))
                    .as("bulan %d", m).isTrue();
        }
    }

    // ---- idempotency ----

    @Test
    @DisplayName("Stateless — panggil berulang, hasil sama")
    void idempotent() {
        for (int i = 0; i < 5; i++) {
            assertThat(ChargeCycleCalculator.shouldCharge(
                    ChargeFrequency.YEAR, 8, MAC_2026, YearMonth.of(2026, 8))).isTrue();
        }
    }
}
