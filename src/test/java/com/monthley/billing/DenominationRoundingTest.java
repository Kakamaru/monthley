package com.monthley.billing;

import com.monthley.billing.internal.ProrationCalculator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** set_curr_min_denom — bulat KE ATAS ke gandaan 5 sen. */
class DenominationRoundingTest {

    private static final BigDecimal FIVE_SEN = new BigDecimal("0.05");

    @Test @DisplayName(".31 → .35")
    void roundsUp31() {
        assertThat(ProrationCalculator.roundUpToDenom(new BigDecimal("67.31"), FIVE_SEN))
                .isEqualByComparingTo("67.35");
    }

    @Test @DisplayName(".37 → .40")
    void roundsUp37() {
        assertThat(ProrationCalculator.roundUpToDenom(new BigDecimal("67.37"), FIVE_SEN))
                .isEqualByComparingTo("67.40");
    }

    @Test @DisplayName("sudah genap → kekal")
    void alreadyMultipleStays() {
        assertThat(ProrationCalculator.roundUpToDenom(new BigDecimal("67.35"), FIVE_SEN))
                .isEqualByComparingTo("67.35");
        assertThat(ProrationCalculator.roundUpToDenom(new BigDecimal("80.00"), FIVE_SEN))
                .isEqualByComparingTo("80.00");
    }

    @Test @DisplayName("null denom → tiada pembundaran")
    void nullDenomNoRounding() {
        assertThat(ProrationCalculator.roundUpToDenom(new BigDecimal("67.31"), null))
                .isEqualByComparingTo("67.31");
    }

    @Test @DisplayName(".01 → .05 (bulat ke atas minimum)")
    void tinyRemainderRoundsUp() {
        assertThat(ProrationCalculator.roundUpToDenom(new BigDecimal("67.01"), FIVE_SEN))
                .isEqualByComparingTo("67.05");
    }
}
