package com.monthley.payment.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Agihan merentas line dalam satu invois (ADR 0006).
 * Susunan: period_start menaik, NULL belakang, seri ikut lineId.
 *
 * Rujukan: docs/decisions/0006-line-level-allocation-plan.md
 */
class LineFifoAllocatorTest {

    // ── Bantuan ──────────────────────────────────────────────────────

    private static LineFifoAllocator.OpenLine line(long id, String periodStart, String outstanding) {
        return new LineFifoAllocator.OpenLine(
                id,
                periodStart == null ? null : LocalDate.parse(periodStart),
                new BigDecimal(outstanding));
    }

    private static BigDecimal rm(String v) { return new BigDecimal(v); }

    private static BigDecimal totalOf(LineFifoAllocator.Result r) {
        return r.allocations().stream()
                .map(LineFifoAllocator.LineAllocation::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ── Kes asas ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Satu line, bayar penuh → satu agihan, tiada baki")
    void singleLineFullyPaid() {
        var r = LineFifoAllocator.allocate(rm("80.00"),
                List.of(line(1, "2026-01-01", "80.00")));

        assertThat(r.allocations()).hasSize(1);
        assertThat(r.allocations().get(0).lineId()).isEqualTo(1L);
        assertThat(r.allocations().get(0).amount()).isEqualByComparingTo("80.00");
        assertThat(r.unallocated()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("Dua line, bayar penuh → pecah ikut nilai line")
    void twoLinesSplit() {
        var r = LineFifoAllocator.allocate(rm("150.00"), List.of(
                line(1, "2026-01-01", "100.00"),   // Yuran
                line(2, "2026-01-01", "50.00")));  // Parking

        assertThat(r.allocations()).hasSize(2);
        assertThat(totalOf(r)).isEqualByComparingTo("150.00");
        assertThat(r.unallocated()).isEqualByComparingTo("0.00");
    }

    // ── Susunan FIFO ─────────────────────────────────────────────────

    @Test
    @DisplayName("Tempoh tertua dibayar dahulu, walaupun senarai tak tersusun")
    void oldestPeriodFirst() {
        var r = LineFifoAllocator.allocate(rm("100.00"), List.of(
                line(9, "2026-03-01", "100.00"),
                line(3, "2026-01-01", "100.00"),   // paling tua
                line(7, "2026-02-01", "100.00")));

        assertThat(r.allocations()).hasSize(1);
        assertThat(r.allocations().get(0).lineId()).isEqualTo(3L);
    }

    @Test
    @DisplayName("Tempoh sama → seri dipecah ikut lineId menaik")
    void tieBreakByLineId() {
        var r = LineFifoAllocator.allocate(rm("50.00"), List.of(
                line(8, "2026-01-01", "50.00"),
                line(2, "2026-01-01", "50.00")));   // id lebih kecil

        assertThat(r.allocations().get(0).lineId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("period_start NULL diletak di belakang")
    void nullPeriodLast() {
        var r = LineFifoAllocator.allocate(rm("50.00"), List.of(
                line(1, null, "50.00"),
                line(2, "2026-05-01", "50.00")));

        assertThat(r.allocations().get(0).lineId()).isEqualTo(2L);
    }

    // ── Bayaran separa ───────────────────────────────────────────────

    @Test
    @DisplayName("Bayaran separa → line tertua lunas, line berikut separa")
    void partialPayment() {
        var r = LineFifoAllocator.allocate(rm("120.00"), List.of(
                line(1, "2026-01-01", "100.00"),
                line(2, "2026-02-01", "100.00")));

        assertThat(r.allocations()).hasSize(2);
        assertThat(r.allocations().get(0).amount()).isEqualByComparingTo("100.00");
        assertThat(r.allocations().get(1).amount()).isEqualByComparingTo("20.00");
        assertThat(r.unallocated()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("Line sudah separa dibayar → hanya baki terbuka diagih")
    void respectsExistingAllocation() {
        // Line 1 asalnya 100, sudah dibayar 60 → baki terbuka 40.
        var r = LineFifoAllocator.allocate(rm("40.00"),
                List.of(line(1, "2026-01-01", "40.00")));

        assertThat(totalOf(r)).isEqualByComparingTo("40.00");
        assertThat(r.unallocated()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("Line lunas (baki 0) dilangkau")
    void skipsSettledLines() {
        var r = LineFifoAllocator.allocate(rm("50.00"), List.of(
                line(1, "2026-01-01", "0.00"),      // sudah lunas
                line(2, "2026-02-01", "50.00")));

        assertThat(r.allocations()).hasSize(1);
        assertThat(r.allocations().get(0).lineId()).isEqualTo(2L);
    }

    // ── Baki tak dapat diagih ────────────────────────────────────────

    @Test
    @DisplayName("Tiada line (cth DEBIT_NOTE) → semua jadi unallocated")
    void noLinesLeavesUnallocated() {
        var r = LineFifoAllocator.allocate(rm("100.00"), List.of());

        assertThat(r.allocations()).isEmpty();
        assertThat(r.unallocated()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("Amaun melebihi jumlah line → lebihan jadi unallocated")
    void excessBecomesUnallocated() {
        var r = LineFifoAllocator.allocate(rm("200.00"),
                List.of(line(1, "2026-01-01", "150.00")));

        assertThat(totalOf(r)).isEqualByComparingTo("150.00");
        assertThat(r.unallocated()).isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("Amaun sifar → tiada agihan")
    void zeroAmount() {
        var r = LineFifoAllocator.allocate(BigDecimal.ZERO,
                List.of(line(1, "2026-01-01", "100.00")));

        assertThat(r.allocations()).isEmpty();
        assertThat(r.unallocated()).isEqualByComparingTo("0.00");
    }

    // ── Invariant ────────────────────────────────────────────────────

    @Test
    @DisplayName("INVARIANT: jumlah agihan + unallocated == amaun asal")
    void conservationOfAmount() {
        BigDecimal amount = rm("237.55");
        var r = LineFifoAllocator.allocate(amount, List.of(
                line(1, "2026-01-01", "100.00"),
                line(2, "2026-02-01", "80.55"),
                line(3, "2026-03-01", "20.00")));

        assertThat(totalOf(r).add(r.unallocated())).isEqualByComparingTo(amount);
    }
}
