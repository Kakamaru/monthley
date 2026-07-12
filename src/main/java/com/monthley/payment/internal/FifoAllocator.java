package com.monthley.payment.internal;

import com.monthley.payment.api.OutstandingInvoice;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Peruntukan FIFO: bayar invois tertua dahulu.
 * Logik murni — tiada DB, mudah diuji.
 */
final class FifoAllocator {

    private FifoAllocator() {}

    record Allocation(Long documentId, BigDecimal amount) {}

    record Result(List<Allocation> allocations, BigDecimal deposit) {}

    /**
     * Agih {@code amount} merentas invois (dianggap sudah tersusun FIFO).
     * Lebihan selepas semua invois dilunaskan → deposit.
     */
    static Result allocate(BigDecimal amount, List<OutstandingInvoice> invoices) {
        List<Allocation> allocations = new ArrayList<>();
        BigDecimal remaining = amount;

        for (OutstandingInvoice inv : invoices) {
            if (remaining.signum() <= 0) break;

            BigDecimal owed = inv.outstanding();
            if (owed.signum() <= 0) continue;

            BigDecimal pay = remaining.min(owed);   // knock penuh atau separa
            allocations.add(new Allocation(inv.documentId(), pay));
            remaining = remaining.subtract(pay);
        }

        // Lebihan → deposit pelanggan
        return new Result(allocations, remaining.max(BigDecimal.ZERO));
    }
}
