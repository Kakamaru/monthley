package com.monthley.billing.internal;

import com.monthley.shared.Charge;
import com.monthley.shared.ChargeFrequency;
import com.monthley.shared.GenMode;
import com.monthley.shared.PeriodIds;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tentukan kitaran caj untuk satu langganan dalam satu larian bil.
 *
 * STATELESS — tiada penunjuk boleh-ubah. Larian berulang hasilkan keputusan
 * yang sama. Idempotency dijaga oleh financial_document_line.idem_key (UNIQUE),
 * bukan oleh kelas ini.
 *
 * ── Model empat paksi ────────────────────────────────────────────────
 *
 *   Mod     sp_billing_setting.gen_mode   anjak period asas
 *   Ufuk    account.charge_frequency      aras + julat period asas
 *   Aras    product.charge_frequency      granulariti setiap caj
 *   Anchor  product.anchor_month          bulan kitaran bermula (null = Jan)
 *
 * ── Peraturan teras ──────────────────────────────────────────────────
 *
 *   Caj bila chargePoint jatuh dalam period asas.
 *   chargePoint = MAX(cycleStart, effectiveStart)
 *
 * Satu peraturan menangani setiap kombinasi:
 *
 *   akaun YEARLY   + produk MONTHLY  → 12 cycleStart dalam tahun     → 12 caj
 *   akaun QUARTERLY+ produk MONTHLY  → 3 cycleStart dalam suku       → 3 caj
 *   akaun MONTHLY  + produk YR/Ogos  → cycleStart Ogos dalam Ogos    → 1 caj
 *   akaun MONTHLY  + produk YR/Ogos  → tiada cycleStart dalam Julai  → tiada
 *   akaun MONTHLY  + produk YR/Jan,
 *         akaun mula 15 Jun          → MAX(1 Jan, 15 Jun) dalam Jun  → 1 caj, prorate
 *
 * Baris terakhir ialah sebab modulo tidak memadai: akaun yang masuk tengah
 * kitaran mesti dicaj serta-merta untuk baki kitaran, bukan menunggu sempadan
 * kitaran berikutnya.
 *
 * Rujukan: docs/domain/billing-rules.md §2, §6
 */
public final class PeriodResolver {

    private PeriodResolver() {}

    // ── Period asas ──────────────────────────────────────────────────

    /**
     * Period asas larian — pada aras charge_frequency AKAUN, sejajar kalendar.
     *
     * Anchor TIDAK terpakai di sini: data production mengesahkan period akaun
     * sentiasa sejajar kalendar (Q1 sentiasa Jan–Mac, H1 sentiasa Jan–Jun).
     * Anchor ialah konsep PRODUK.
     *
     * @param runMonth    bulan larian dijalankan
     * @param mode        CURRENT / POSTPAID / PREPAID
     * @param accountFreq charge_frequency akaun; null → MONTHLY
     */
    public static Charge basePeriod(YearMonth runMonth, GenMode mode, ChargeFrequency accountFreq) {
        ChargeFrequency freq = recurringOrMonthly(accountFreq);

        Charge current = calendarCycle(runMonth.atDay(1), freq);

        return switch (mode) {
            case CURRENT  -> current;
            case PREPAID  -> calendarCycle(current.cycleEnd().plusDays(1), freq);
            case POSTPAID -> calendarCycle(current.cycleStart().minusDays(1), freq);
        };
    }

    // ── Kitaran caj ──────────────────────────────────────────────────

    /**
     * Kitaran yang perlu dicaj untuk satu langganan dalam period asas ini.
     *
     * Pemanggil bertanggungjawab untuk:
     *   - menapis langganan dengan parent_subscription_id (anak tidak dicaj)
     *   - mengendali ONE_TIME dan PER_USE (bukan kitaran kalendar)
     *   - menyemak exclude period
     *   - menentukan harga berkesan
     *
     * @param base           dari {@link #basePeriod}
     * @param productFreq    charge_frequency produk
     * @param anchorMonth    1–12, atau null = Januari (sejajar kalendar)
     * @param effectiveStart MAX(account.start_charging, subscription.start_date);
     *                       null bermakna tiada had bawah
     * @param effectiveEnd   MIN(account.expiry, subscription.end_date); null = tiada had atas
     * @return kitaran untuk dicaj, tersusun ikut masa; kosong kalau tiada
     */
    public static List<Charge> chargesFor(Charge base,
                                          ChargeFrequency productFreq,
                                          Integer anchorMonth,
                                          LocalDate effectiveStart,
                                          LocalDate effectiveEnd) {

        if (productFreq == null || !productFreq.isRecurring()) {
            return List.of();   // ONE_TIME / PER_USE dikendali di tempat lain
        }
        validateAnchor(anchorMonth);

        // Guna LinkedHashMap: nyahduplikasi ikut cycleStart, kekalkan susunan.
        Map<LocalDate, Charge> found = new LinkedHashMap<>();

        // (a) Kitaran yang BERMULA dalam period asas.
        LocalDate cursor = cycleStartFor(base.cycleStart(), productFreq, anchorMonth);
        if (cursor.isBefore(base.cycleStart())) {
            cursor = cursor.plusMonths(productFreq.months());
        }
        while (!cursor.isAfter(base.cycleEnd())) {
            emit(found, cursor, productFreq, base, effectiveStart, effectiveEnd);
            cursor = cursor.plusMonths(productFreq.months());
        }

        // (b) Akaun masuk tengah kitaran — kitaran yang MENGANDUNGI effectiveStart.
        //     Hanya satu kitaran boleh layak, dan hanya kalau effectiveStart
        //     sendiri jatuh dalam period asas.
        if (effectiveStart != null && within(effectiveStart, base)) {
            LocalDate midCycle = cycleStartFor(effectiveStart, productFreq, anchorMonth);
            emit(found, midCycle, productFreq, base, effectiveStart, effectiveEnd);
        }

        List<Charge> out = new ArrayList<>(found.values());
        out.sort((a, b) -> a.cycleStart().compareTo(b.cycleStart()));
        return out;
    }

    // ── Dalaman ──────────────────────────────────────────────────────

    private static void emit(Map<LocalDate, Charge> found,
                             LocalDate cycleStart,
                             ChargeFrequency freq,
                             Charge base,
                             LocalDate effectiveStart,
                             LocalDate effectiveEnd) {

        if (found.containsKey(cycleStart)) return;

        LocalDate cycleEnd = cycleStart.plusMonths(freq.months()).minusDays(1);

        LocalDate chargePoint = (effectiveStart == null || effectiveStart.isBefore(cycleStart))
                ? cycleStart
                : effectiveStart;

        // Peraturan teras.
        if (!within(chargePoint, base)) return;

        // Langganan sudah tamat sebelum kitaran ini bermula.
        if (effectiveEnd != null && effectiveEnd.isBefore(chargePoint)) return;

        LocalDate coverageEnd = (effectiveEnd != null && effectiveEnd.isBefore(cycleEnd))
                ? effectiveEnd
                : cycleEnd;

        if (coverageEnd.isBefore(chargePoint)) return;

        found.put(cycleStart, new Charge(
                PeriodIds.of(cycleStart, freq),
                cycleStart, cycleEnd,
                chargePoint, coverageEnd));
    }

    /** Kitaran sejajar kalendar yang mengandungi {@code date}. */
    private static Charge calendarCycle(LocalDate date, ChargeFrequency freq) {
        LocalDate start = cycleStartFor(date, freq, null);
        LocalDate end = start.plusMonths(freq.months()).minusDays(1);
        return new Charge(PeriodIds.of(start, freq), start, end, start, end);
    }

    /**
     * Mula kitaran yang mengandungi {@code date}, untuk {@code freq} dengan {@code anchorMonth}.
     *
     * Kitaran bermula pada anchorMonth dan setiap freq.months() selepas itu.
     * Indeks bulan mutlak menjadikan ini aritmetik tulen — tiada gelung, tiada state.
     *
     *   anchor Ogos, YEAR    → …, Ogos 2025, Ogos 2026, Ogos 2027, …
     *   anchor Feb,  QUARTER → Feb, Mei, Ogos, Nov
     *   anchor apa-apa, MONTHLY → setiap bulan (anchor tidak relevan)
     *   anchor null → Januari → sejajar kalendar
     */
    static LocalDate cycleStartFor(LocalDate date, ChargeFrequency freq, Integer anchorMonth) {
        int len = freq.months();
        int anchor = (anchorMonth == null) ? 1 : anchorMonth;

        long dateIdx = monthIndex(date.getYear(), date.getMonthValue());
        long anchorIdx = anchor - 1L;   // rujukan dalam tahun 0

        long offset = Math.floorMod(dateIdx - anchorIdx, len);
        return date.withDayOfMonth(1).minusMonths(offset);
    }

    private static long monthIndex(int year, int month) {
        return year * 12L + (month - 1);
    }

    private static boolean within(LocalDate d, Charge base) {
        return !d.isBefore(base.cycleStart()) && !d.isAfter(base.cycleEnd());
    }

    private static ChargeFrequency recurringOrMonthly(ChargeFrequency f) {
        return (f == null || !f.isRecurring()) ? ChargeFrequency.MONTHLY : f;
    }

    private static void validateAnchor(Integer anchorMonth) {
        if (anchorMonth != null && (anchorMonth < 1 || anchorMonth > 12)) {
            throw new IllegalArgumentException("anchor_month mesti 1-12, bukan: " + anchorMonth);
        }
    }
}
