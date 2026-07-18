package com.monthley.billing.internal;

import com.monthley.shared.Charge;
import com.monthley.shared.ChargeFrequency;
import com.monthley.shared.GenMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ujian ini menyematkan kelakuan pada data production sebenar.
 *
 * Rujukan: docs/domain/billing-rules.md
 *          docs/domain/legacy-generator-analysis.md
 *
 * Ujian bertanda DIVERGENSI sengaja berbeza dari legacy — lihat komen.
 */
class PeriodResolverTest {

    private static final LocalDate JUN_15 = LocalDate.of(2026, 6, 15);
    private static final LocalDate SEP_15 = LocalDate.of(2026, 9, 15);

    // ── Period asas ──────────────────────────────────────────────────

    @Nested
    @DisplayName("basePeriod — anjak pada aras akaun")
    class BasePeriod {

        @Test
        @DisplayName("PROD: akaun MONTHLY, POSTPAID, larian Julai → Jun (2026120600)")
        void postpaidMonthly() {
            Charge base = PeriodResolver.basePeriod(
                    YearMonth.of(2026, 7), GenMode.POSTPAID, ChargeFrequency.MONTHLY);

            assertEquals(2026120600L, base.periodId());
            assertEquals(LocalDate.of(2026, 6, 1), base.cycleStart());
            assertEquals(LocalDate.of(2026, 6, 30), base.cycleEnd());
        }

        @Test
        @DisplayName("PROD: akaun MONTHLY, CURRENT, larian Julai → Julai (2026230700)")
        void currentMonthly() {
            Charge base = PeriodResolver.basePeriod(
                    YearMonth.of(2026, 7), GenMode.CURRENT, ChargeFrequency.MONTHLY);

            assertEquals(2026230700L, base.periodId());
        }

        @Test
        @DisplayName("PREPAID larian Julai → Ogos")
        void prepaidMonthly() {
            Charge base = PeriodResolver.basePeriod(
                    YearMonth.of(2026, 7), GenMode.PREPAID, ChargeFrequency.MONTHLY);

            assertEquals(2026230800L, base.periodId());
        }

        @Test
        @DisplayName("Akaun YEARLY POSTPAID → tahun lepas, bukan bulan lepas")
        void postpaidYearly() {
            Charge base = PeriodResolver.basePeriod(
                    YearMonth.of(2026, 7), GenMode.POSTPAID, ChargeFrequency.YEAR);

            assertEquals(2025000000L, base.periodId());
            assertEquals(LocalDate.of(2025, 1, 1), base.cycleStart());
            assertEquals(LocalDate.of(2025, 12, 31), base.cycleEnd());
        }

        @Test
        @DisplayName("Akaun QUARTERLY CURRENT, larian Julai → Q3 (2026230000)")
        void currentQuarterly() {
            Charge base = PeriodResolver.basePeriod(
                    YearMonth.of(2026, 7), GenMode.CURRENT, ChargeFrequency.QUARTERLY);

            assertEquals(2026230000L, base.periodId());
            assertEquals(LocalDate.of(2026, 7, 1), base.cycleStart());
            assertEquals(LocalDate.of(2026, 9, 30), base.cycleEnd());
        }

        @Test
        @DisplayName("Akaun HALF_YEAR CURRENT, larian Julai → H2 (2026200000)")
        void currentHalfYear() {
            Charge base = PeriodResolver.basePeriod(
                    YearMonth.of(2026, 7), GenMode.CURRENT, ChargeFrequency.HALF_YEAR);

            assertEquals(2026200000L, base.periodId());
            assertEquals(LocalDate.of(2026, 7, 1), base.cycleStart());
            assertEquals(LocalDate.of(2026, 12, 31), base.cycleEnd());
        }

        @Test
        @DisplayName("Period akaun sentiasa sejajar kalendar — Q1 sentiasa Jan-Mac")
        void accountPeriodsAreCalendarAligned() {
            Charge base = PeriodResolver.basePeriod(
                    YearMonth.of(2026, 2), GenMode.CURRENT, ChargeFrequency.QUARTERLY);

            assertEquals(2026110000L, base.periodId());
            assertEquals(LocalDate.of(2026, 1, 1), base.cycleStart());
        }
    }

    // ── Ufuk × aras ──────────────────────────────────────────────────

    @Nested
    @DisplayName("chargesFor — ufuk akaun kembang ikut aras produk")
    class HorizonTimesLevel {

        @Test
        @DisplayName("Akaun YEARLY + produk MONTHLY → 12 caj, Jan hingga Dis")
        void yearlyAccountMonthlyProduct() {
            Charge base = PeriodResolver.basePeriod(
                    YearMonth.of(2026, 6), GenMode.CURRENT, ChargeFrequency.YEAR);

            List<Charge> charges = PeriodResolver.chargesFor(
                    base, ChargeFrequency.MONTHLY, null, null, null);

            assertEquals(12, charges.size());
            assertEquals(2026110100L, charges.get(0).periodId());    // Januari
            assertEquals(2026241200L, charges.get(11).periodId());   // Disember
            assertTrue(charges.stream().allMatch(Charge::isFullCycle));
        }

        @Test
        @DisplayName("PROD: akaun YEARLY + produk YEAR → 1 caj, period 2026000000")
        void yearlyAccountYearlyProduct() {
            Charge base = PeriodResolver.basePeriod(
                    YearMonth.of(2026, 6), GenMode.CURRENT, ChargeFrequency.YEAR);

            List<Charge> charges = PeriodResolver.chargesFor(
                    base, ChargeFrequency.YEAR, null, null, null);

            assertEquals(1, charges.size());
            assertEquals(2026000000L, charges.get(0).periodId());
        }

        @Test
        @DisplayName("Akaun QUARTERLY + produk MONTHLY → 3 caj (Jul, Ogos, Sep)")
        void quarterlyAccountMonthlyProduct() {
            Charge base = PeriodResolver.basePeriod(
                    YearMonth.of(2026, 7), GenMode.CURRENT, ChargeFrequency.QUARTERLY);

            List<Charge> charges = PeriodResolver.chargesFor(
                    base, ChargeFrequency.MONTHLY, null, null, null);

            assertEquals(3, charges.size());
            assertEquals(2026230700L, charges.get(0).periodId());
            assertEquals(2026230800L, charges.get(1).periodId());
            assertEquals(2026230900L, charges.get(2).periodId());
        }

        @Test
        @DisplayName("Akaun YEARLY + produk QUARTERLY → 4 caj (Q1-Q4)")
        void yearlyAccountQuarterlyProduct() {
            Charge base = PeriodResolver.basePeriod(
                    YearMonth.of(2026, 6), GenMode.CURRENT, ChargeFrequency.YEAR);

            List<Charge> charges = PeriodResolver.chargesFor(
                    base, ChargeFrequency.QUARTERLY, null, null, null);

            assertEquals(4, charges.size());
            assertEquals(2026110000L, charges.get(0).periodId());
            assertEquals(2026240000L, charges.get(3).periodId());
        }
    }

    // ── Anchor ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("anchor_month — bulan kitaran bermula")
    class Anchor {

        @Test
        @DisplayName("Akaun MONTHLY + produk YR anchor Ogos → dicaj HANYA dalam Ogos")
        void yearlyProductChargesOnlyAtAnchor() {
            Charge august = PeriodResolver.basePeriod(
                    YearMonth.of(2026, 8), GenMode.CURRENT, ChargeFrequency.MONTHLY);

            List<Charge> charges = PeriodResolver.chargesFor(
                    august, ChargeFrequency.YEAR, 8, null, null);

            assertEquals(1, charges.size());
            assertEquals(2026000000L, charges.get(0).periodId());
            assertEquals(LocalDate.of(2026, 8, 1), charges.get(0).cycleStart());
            assertEquals(LocalDate.of(2027, 7, 31), charges.get(0).cycleEnd());
        }

        @Test
        @DisplayName("Bug yearly-setiap-Januari LARUT: bulan bukan-anchor → tiada caj")
        void yearlyProductNotChargedOutsideAnchor() {
            for (int m = 1; m <= 12; m++) {
                if (m == 8) continue;
                Charge base = PeriodResolver.basePeriod(
                        YearMonth.of(2026, m), GenMode.CURRENT, ChargeFrequency.MONTHLY);

                List<Charge> charges = PeriodResolver.chargesFor(
                        base, ChargeFrequency.YEAR, 8, null, null);

                assertTrue(charges.isEmpty(), "Bulan " + m + " tidak sepatutnya caj produk anchor-Ogos");
            }
        }

        @Test
        @DisplayName("anchor null = Januari, sejajar kalendar")
        void nullAnchorMeansJanuary() {
            Charge jan = PeriodResolver.basePeriod(
                    YearMonth.of(2026, 1), GenMode.CURRENT, ChargeFrequency.MONTHLY);

            List<Charge> charges = PeriodResolver.chargesFor(
                    jan, ChargeFrequency.YEAR, null, null, null);

            assertEquals(1, charges.size());
            assertEquals(LocalDate.of(2026, 1, 1), charges.get(0).cycleStart());
            assertEquals(LocalDate.of(2026, 12, 31), charges.get(0).cycleEnd());
        }

        @Test
        @DisplayName("Anchor terapung QR: anchor Feb → Feb, Mei, Ogos, Nov")
        void quarterlyAnchorFloats() {
            Charge feb = PeriodResolver.basePeriod(
                    YearMonth.of(2026, 2), GenMode.CURRENT, ChargeFrequency.MONTHLY);

            List<Charge> charges = PeriodResolver.chargesFor(
                    feb, ChargeFrequency.QUARTERLY, 2, null, null);

            assertEquals(1, charges.size());
            assertEquals(LocalDate.of(2026, 2, 1), charges.get(0).cycleStart());
            assertEquals(LocalDate.of(2026, 4, 30), charges.get(0).cycleEnd());
            // period_id = baldi kalendar tempat kitaran BERMULA = Q1
            assertEquals(2026110000L, charges.get(0).periodId());
        }

        @Test
        @DisplayName("Anchor tidak relevan untuk MONTHLY")
        void anchorIrrelevantForMonthly() {
            Charge jul = PeriodResolver.basePeriod(
                    YearMonth.of(2026, 7), GenMode.CURRENT, ChargeFrequency.MONTHLY);

            List<Charge> withAnchor = PeriodResolver.chargesFor(
                    jul, ChargeFrequency.MONTHLY, 8, null, null);
            List<Charge> without = PeriodResolver.chargesFor(
                    jul, ChargeFrequency.MONTHLY, null, null, null);

            assertEquals(without, withAnchor);
        }

        @Test
        @DisplayName("anchor_month di luar 1-12 → tolak")
        void invalidAnchorRejected() {
            Charge base = PeriodResolver.basePeriod(
                    YearMonth.of(2026, 7), GenMode.CURRENT, ChargeFrequency.MONTHLY);

            assertThrows(IllegalArgumentException.class, () ->
                    PeriodResolver.chargesFor(base, ChargeFrequency.YEAR, 13, null, null));
            assertThrows(IllegalArgumentException.class, () ->
                    PeriodResolver.chargesFor(base, ChargeFrequency.YEAR, 0, null, null));
        }
    }

    // ── Tarikh berkesan ──────────────────────────────────────────────

    @Nested
    @DisplayName("effectiveStart — tiga kes billing-rules.md §6")
    class EffectiveDates {

        @Test
        @DisplayName("NORMAL: anchor Ogos, akaun mula 15 Jun → Ogos, kitaran penuh")
        void normalCase() {
            Charge august = PeriodResolver.basePeriod(
                    YearMonth.of(2026, 8), GenMode.CURRENT, ChargeFrequency.MONTHLY);

            List<Charge> charges = PeriodResolver.chargesFor(
                    august, ChargeFrequency.YEAR, 8, JUN_15, null);

            assertEquals(1, charges.size());
            assertTrue(charges.get(0).isFullCycle(), "Akaun mula sebelum kitaran → caj penuh");
            assertEquals(LocalDate.of(2026, 8, 1), charges.get(0).coverageStart());
        }

        @Test
        @DisplayName("NORMAL: Jun → prorate baki kitaran BERJALAN (Ogos25–Jul26)")
        void normalCaseProratesRunningCycle() {
            // start_charging diisi (15 Jun) → prorate baki kitaran yang sedang
            // berjalan. 47 hari dari 365 → 350 x 47/365 = RM45.07.
            // Kemudian kitaran Ogos26 bermula → RM350 penuh.
            Charge jun = PeriodResolver.basePeriod(
                    YearMonth.of(2026, 6), GenMode.CURRENT, ChargeFrequency.MONTHLY);

            List<Charge> charges = PeriodResolver.chargesFor(
                    jun, ChargeFrequency.YEAR, 8, JUN_15, null);

            assertEquals(1, charges.size());
            Charge c = charges.get(0);
            assertEquals(LocalDate.of(2025, 8, 1), c.cycleStart(), "Kitaran berjalan");
            assertEquals(LocalDate.of(2026, 7, 31), c.cycleEnd());
            assertEquals(JUN_15, c.coverageStart());
            assertFalse(c.isFullCycle(), "47 hari baki → prorate");
        }

        @Test
        @DisplayName("NORMAL: Julai → tiada caj, kitaran berjalan sudah dicaj pada Jun")
        void normalCaseNoDoubleChargeInJuly() {
            Charge jul = PeriodResolver.basePeriod(
                    YearMonth.of(2026, 7), GenMode.CURRENT, ChargeFrequency.MONTHLY);

            List<Charge> charges = PeriodResolver.chargesFor(
                    jul, ChargeFrequency.YEAR, 8, JUN_15, null);

            assertTrue(charges.isEmpty());
        }

        @Test
        @DisplayName("PREPAID: anjakan mod sudah dikendali oleh basePeriod")
        void prepaidChargesNextCycle() {
            // Larian Julai, PREPAID → base = Ogos. Kitaran Ogos26 bermula
            // dalam base → caj penuh. Tiada anjakan tambahan aras produk.
            Charge base = PeriodResolver.basePeriod(
                    YearMonth.of(2026, 7), GenMode.PREPAID, ChargeFrequency.MONTHLY);

            List<Charge> charges = PeriodResolver.chargesFor(
                    base, ChargeFrequency.YEAR, 8, JUN_15, null);

            assertEquals(1, charges.size());
            assertTrue(charges.get(0).isFullCycle());
            assertEquals(LocalDate.of(2026, 8, 1), charges.get(0).cycleStart());
        }

        @Test
        @DisplayName("LEWAT: anchor Ogos, akaun mula 15 Sep → caj Sep, prorate baki")
        void lateCase() {
            Charge sep = PeriodResolver.basePeriod(
                    YearMonth.of(2026, 9), GenMode.CURRENT, ChargeFrequency.MONTHLY);

            List<Charge> charges = PeriodResolver.chargesFor(
                    sep, ChargeFrequency.YEAR, 8, SEP_15, null);

            assertEquals(1, charges.size());
            Charge c = charges.get(0);
            assertFalse(c.isFullCycle(), "Masuk tengah kitaran → prorate");
            assertEquals(SEP_15, c.coverageStart());
            assertEquals(LocalDate.of(2027, 7, 31), c.coverageEnd());
            assertEquals(LocalDate.of(2026, 8, 1), c.cycleStart(), "Penyebut = kitaran penuh");
        }

        @Test
        @DisplayName("KALENDAR: anchor null, akaun mula 15 Jun → caj Jun, prorate Jun15-Dis31")
        void calendarCase() {
            Charge jun = PeriodResolver.basePeriod(
                    YearMonth.of(2026, 6), GenMode.CURRENT, ChargeFrequency.MONTHLY);

            List<Charge> charges = PeriodResolver.chargesFor(
                    jun, ChargeFrequency.YEAR, null, JUN_15, null);

            assertEquals(1, charges.size());
            Charge c = charges.get(0);
            assertEquals(JUN_15, c.coverageStart());
            assertEquals(LocalDate.of(2026, 12, 31), c.coverageEnd());
            assertEquals(LocalDate.of(2026, 1, 1), c.cycleStart());
            assertEquals(2026000000L, c.periodId());
        }

        @Test
        @DisplayName("KALENDAR: tahun berikut → kitaran penuh pada Januari")
        void calendarCaseNextYear() {
            Charge jan = PeriodResolver.basePeriod(
                    YearMonth.of(2027, 1), GenMode.CURRENT, ChargeFrequency.MONTHLY);

            List<Charge> charges = PeriodResolver.chargesFor(
                    jan, ChargeFrequency.YEAR, null, JUN_15, null);

            assertEquals(1, charges.size());
            assertTrue(charges.get(0).isFullCycle());
            assertEquals(2027000000L, charges.get(0).periodId());
        }

        @Test
        @DisplayName("Tiada caj berganda: kitaran tengah dicaj sekali sahaja")
        void midCycleChargedOnce() {
            // Jun: dicaj (masuk tengah kitaran)
            Charge jun = PeriodResolver.basePeriod(
                    YearMonth.of(2026, 6), GenMode.CURRENT, ChargeFrequency.MONTHLY);
            assertEquals(1, PeriodResolver.chargesFor(
                    jun, ChargeFrequency.YEAR, null, JUN_15, null).size());

            // Julai–Disember: tiada
            for (int m = 7; m <= 12; m++) {
                Charge base = PeriodResolver.basePeriod(
                        YearMonth.of(2026, m), GenMode.CURRENT, ChargeFrequency.MONTHLY);
                assertTrue(PeriodResolver.chargesFor(
                        base, ChargeFrequency.YEAR, null, JUN_15, null).isEmpty(),
                        "Bulan " + m + " tidak sepatutnya caj semula");
            }
        }

        @Test
        @DisplayName("effectiveEnd memotong liputan")
        void effectiveEndTruncates() {
            Charge jan = PeriodResolver.basePeriod(
                    YearMonth.of(2026, 1), GenMode.CURRENT, ChargeFrequency.MONTHLY);

            List<Charge> charges = PeriodResolver.chargesFor(
                    jan, ChargeFrequency.YEAR, null, null, LocalDate.of(2026, 3, 31));

            assertEquals(1, charges.size());
            assertEquals(LocalDate.of(2026, 3, 31), charges.get(0).coverageEnd());
            assertFalse(charges.get(0).isFullCycle());
        }

        @Test
        @DisplayName("Langganan tamat sebelum kitaran → tiada caj")
        void endedBeforeCycle() {
            Charge jun = PeriodResolver.basePeriod(
                    YearMonth.of(2026, 6), GenMode.CURRENT, ChargeFrequency.MONTHLY);

            List<Charge> charges = PeriodResolver.chargesFor(
                    jun, ChargeFrequency.MONTHLY, null, null, LocalDate.of(2026, 5, 31));

            assertTrue(charges.isEmpty());
        }

        @Test
        @DisplayName("Akaun YEARLY + MONTHLY, mula 15 Jun → 7 caj (Jun-Dis), Jun diprorate")
        void yearlyHorizonWithMidYearStart() {
            Charge base = PeriodResolver.basePeriod(
                    YearMonth.of(2026, 3), GenMode.CURRENT, ChargeFrequency.YEAR);

            List<Charge> charges = PeriodResolver.chargesFor(
                    base, ChargeFrequency.MONTHLY, null, JUN_15, null);

            assertEquals(7, charges.size(), "Jun hingga Disember");
            assertEquals(2026120600L, charges.get(0).periodId());
            assertEquals(JUN_15, charges.get(0).coverageStart());
            assertFalse(charges.get(0).isFullCycle(), "Jun separa");
            assertTrue(charges.get(1).isFullCycle(), "Julai penuh");
        }
    }

    // ── Divergensi sengaja dari legacy ───────────────────────────────

    @Nested
    @DisplayName("DIVERGENSI — sengaja berbeza dari legacy")
    class Divergence {

        @Test
        @DisplayName("Tiada caj untuk period sebelum akaun wujud")
        void noChargeBeforeAccountExists() {
            // PROD 17 Julai: akaun MY00006000041 dicipta 17 Jul, POSTPAID → base Jun.
            // Legacy caj RM80 untuk Jun (sebelum akaun wujud).
            // Kita tidak: effectiveStart = created_at bila start_charging NULL.
            Charge jun = PeriodResolver.basePeriod(
                    YearMonth.of(2026, 7), GenMode.POSTPAID, ChargeFrequency.MONTHLY);

            List<Charge> charges = PeriodResolver.chargesFor(
                    jun, ChargeFrequency.MONTHLY, null, LocalDate.of(2026, 7, 17), null);

            assertTrue(charges.isEmpty(), "Akaun tidak wujud dalam Jun");
        }

        @Test
        @DisplayName("start_charging awal → Jun TETAP dibil walau akaun didaftar lewat")
        void backdatedStartChargingStillBills() {
            // SP set start_charging = 13 Mei walaupun daftar 17 Julai.
            // Itulah sebab medan ini wujud berasingan dari created_at.
            Charge jun = PeriodResolver.basePeriod(
                    YearMonth.of(2026, 7), GenMode.POSTPAID, ChargeFrequency.MONTHLY);

            List<Charge> charges = PeriodResolver.chargesFor(
                    jun, ChargeFrequency.MONTHLY, null, LocalDate.of(2026, 5, 13), null);

            assertEquals(1, charges.size());
            assertTrue(charges.get(0).isFullCycle());
        }
    }
}
