package com.monthley.payment.internal;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Menulis alokasi peringkat line — SATU tempat untuk SEMUA laluan
 * (bayaran, pelarasan, dan kelak guna-advance).
 *
 * Sama alasan dengan {@link AllocationGuard}: pengajaran CASE-001 ialah
 * logik yang disalin merentas laluan akan menyimpang — "satu laluan ingat,
 * satu lupa". Maka pemecahan line duduk di sini, dikongsi.
 *
 * Satu panggilan mungkin menghasilkan BEBERAPA baris alokasi (satu per line).
 * SUM(amount) per dokumen kekal sama seperti alokasi peringkat dokumen —
 * itulah sebabnya AllocationGuard dan semua query baki tidak terjejas.
 *
 * Rujukan: docs/decisions/0006-line-level-allocation-plan.md
 */
@Component
class LineAllocationWriter {

    private final LineAllocationResolver resolver;
    private final AllocationRepository allocations;
    private final AllocationGuard guard;

    LineAllocationWriter(LineAllocationResolver resolver, AllocationRepository allocations,
                         AllocationGuard guard) {
        this.resolver = resolver;
        this.allocations = allocations;
        this.guard = guard;
    }

    /**
     * Cipta alokasi bagi {@code amount} terhadap {@code debitDocumentId},
     * dipecahkan mengikut line (FIFO tempoh tertua dahulu).
     *
     * Dokumen tanpa line (DEBIT_NOTE/CREDIT_NOTE) menghasilkan satu baris
     * peringkat dokumen — tingkah laku sama seperti sebelum ini.
     *
     * PENTING: pemanggil mesti panggil {@code guard.checkAndLock} DAHULU.
     * Kaedah ini tidak menyemak invariant — pemisahan tanggungjawab.
     *
     * @return bilangan baris alokasi yang dicipta
     */
    int write(String spCode, Long accountId, Long debitDocumentId,
              Long creditDocumentId, BigDecimal amount) {

        LineFifoAllocator.Result r = resolver.resolve(debitDocumentId, amount);
        int rows = 0;

        for (LineFifoAllocator.LineAllocation la : r.allocations()) {
            // Invariant line — menangkap pepijat logik resolver (race sudah
            // tertutup oleh kunci dokumen dalam checkAndLock).
            guard.checkLine(la.lineId(), la.amount());
            allocations.save(new PaymentAllocation(
                    spCode, accountId, debitDocumentId, creditDocumentId,
                    la.amount(), la.lineId()));
            rows++;
        }

        // Baki tanpa line terbuka -> baris peringkat dokumen (line_id null).
        // Berlaku bagi dokumen tanpa line, atau bila line sudah lunas
        // sedangkan dokumen masih terbuka (patut jarang; guard yang menjaga).
        if (r.unallocated().signum() > 0) {
            allocations.save(new PaymentAllocation(
                    spCode, accountId, debitDocumentId, creditDocumentId,
                    r.unallocated(), null));
            rows++;
        }

        return rows;
    }
}
