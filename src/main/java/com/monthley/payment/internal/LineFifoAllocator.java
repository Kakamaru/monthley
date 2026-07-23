package com.monthley.payment.internal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Agih satu amaun merentas LINE dalam SATU dokumen (ADR 0006).
 *
 * Berpasangan dengan {@link FifoAllocator}:
 *   FifoAllocator      -> agih merentas invois (tertua dahulu)
 *   LineFifoAllocator  -> agih dalam invois, merentas line
 *
 * Susunan: period_start menaik (tempoh tertua dahulu), NULL di belakang,
 * seri dipecah ikut lineId menaik.
 *
 * Logik murni — tiada DB, mudah diuji.
 * Rujukan: docs/decisions/0006-line-level-allocation-plan.md
 */
final class LineFifoAllocator {

    private LineFifoAllocator() {}

    /** Line yang masih terbuka. outstanding = (amount + tax) - alokasi aktif. */
    record OpenLine(Long lineId, LocalDate periodStart, BigDecimal outstanding) {}

    record LineAllocation(Long lineId, BigDecimal amount) {}

    /**
     * @param allocations agihan per line
     * @param unallocated baki yang tidak dapat diletak pada mana-mana line —
     *                    berlaku bila dokumen tiada line (cth DEBIT_NOTE) atau
     *                    semua line sudah lunas. Pemanggil merekodkannya sebagai
     *                    alokasi peringkat dokumen (lineId null).
     */
    record Result(List<LineAllocation> allocations, BigDecimal unallocated) {}

    /** Susunan FIFO line: tempoh tertua dahulu, NULL belakang, seri ikut id. */
    private static final Comparator<OpenLine> FIFO =
            Comparator.comparing(OpenLine::periodStart,
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(OpenLine::lineId,
                            Comparator.nullsLast(Comparator.naturalOrder()));

    /**
     * Agih {@code amount} merentas {@code lines}. Senarai disusun di dalam —
     * pemanggil tidak perlu susun dahulu.
     */
    static Result allocate(BigDecimal amount, List<OpenLine> lines) {
        List<LineAllocation> out = new ArrayList<>();
        BigDecimal remaining = amount == null ? BigDecimal.ZERO : amount;

        if (remaining.signum() <= 0 || lines == null || lines.isEmpty()) {
            return new Result(out, remaining.max(BigDecimal.ZERO));
        }

        List<OpenLine> sorted = new ArrayList<>(lines);
        sorted.sort(FIFO);

        for (OpenLine line : sorted) {
            if (remaining.signum() <= 0) break;

            BigDecimal owed = line.outstanding();
            if (owed == null || owed.signum() <= 0) continue;

            BigDecimal pay = remaining.min(owed);   // lunas penuh atau separa
            out.add(new LineAllocation(line.lineId(), pay));
            remaining = remaining.subtract(pay);
        }

        return new Result(out, remaining.max(BigDecimal.ZERO));
    }
}
