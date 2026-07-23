package com.monthley.payment.internal;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Backfill alokasi sedia ada kepada peringkat line (ADR 0006 P4).
 *
 * Alokasi lama menunjuk dokumen sahaja (line_id NULL). Setiap satu dipecah
 * mengikut FIFO line yang SAMA seperti bayaran baharu — jadi logik tunggal,
 * dan boleh diguna semula untuk migrasi data legacy.
 *
 * Jaminan: SUM(amount) per debit_document_id KEKAL sama. Jika tidak, seluruh
 * transaksi digulung semula.
 *
 * Idempoten — hanya memproses baris yang line_id masih NULL.
 */
@Service
class AllocationBackfillService {

    private static final Logger log = LoggerFactory.getLogger(AllocationBackfillService.class);

    @PersistenceContext
    private EntityManager em;

    private final LineAllocationResolver resolver;

    AllocationBackfillService(LineAllocationResolver resolver) {
        this.resolver = resolver;
    }

    public record Report(int allocationsProcessed, int rowsCreated,
                         BigDecimal sumBefore, BigDecimal sumAfter,
                         List<String> skipped) {}

    /**
     * Backfill untuk SATU SP. Berskop supaya boleh dijalankan berperingkat —
     * sahkan satu SP dahulu sebelum yang lain. Migrasi legacy juga per SP.
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public Report backfill(String spCode) {
        BigDecimal sumBefore = sumActive(spCode);

        // Calon: ACTIVE, belum ada line, dan dokumen debitnya MEMANG ada line.
        List<Object[]> candidates = em.createNativeQuery("""
                SELECT a.id, a.debit_document_id, a.amount
                FROM fi_allocation a
                WHERE a.sp_code = :sp
                  AND a.status = 'ACTIVE'
                  AND a.debit_document_line_id IS NULL
                  AND EXISTS (SELECT 1 FROM financial_document_line l
                              WHERE l.document_id = a.debit_document_id AND l.active = 1)
                ORDER BY a.id
                """).setParameter("sp", spCode).getResultList();

        int processed = 0, created = 0;
        List<String> skipped = new ArrayList<>();

        for (Object[] row : candidates) {
            Long allocId = ((Number) row[0]).longValue();
            Long docId = ((Number) row[1]).longValue();
            BigDecimal amount = new BigDecimal(row[2].toString());

            // FIFO line — nampak agihan yang sudah dibuat oleh lelaran sebelum ini.
            LineFifoAllocator.Result r = resolver.resolve(docId, amount);

            if (r.allocations().isEmpty()) {
                skipped.add("alokasi " + allocId + " (dok " + docId + "): tiada line terbuka");
                continue;
            }
            if (r.unallocated().signum() > 0) {
                skipped.add("alokasi " + allocId + " (dok " + docId + "): baki "
                        + r.unallocated() + " tiada line terbuka");
            }

            // Baris asal -> agihan pertama (jejak audit kekal).
            LineFifoAllocator.LineAllocation first = r.allocations().get(0);
            em.createNativeQuery("""
                    UPDATE fi_allocation
                    SET debit_document_line_id = :line, amount = :amt, updated_at = NOW()
                    WHERE id = :id
                    """)
                    .setParameter("line", first.lineId())
                    .setParameter("amt", first.amount())
                    .setParameter("id", allocId)
                    .executeUpdate();

            // Agihan selebihnya -> baris baharu, menyalin baris asal.
            for (int i = 1; i < r.allocations().size(); i++) {
                LineFifoAllocator.LineAllocation la = r.allocations().get(i);
                em.createNativeQuery("""
                        INSERT INTO fi_allocation
                            (sp_code, account_id, debit_document_id, credit_document_id,
                             debit_document_line_id, amount, status, reverses_allocation_id,
                             journal_entry_id, created_at, created_by, updated_at, updated_by, version)
                        SELECT sp_code, account_id, debit_document_id, credit_document_id,
                               :line, :amt, status, reverses_allocation_id,
                               journal_entry_id, created_at, created_by, NOW(), updated_by, 0
                        FROM fi_allocation WHERE id = :orig
                        """)
                        .setParameter("line", la.lineId())
                        .setParameter("amt", la.amount())
                        .setParameter("orig", allocId)
                        .executeUpdate();
                created++;
            }

            // Baki yang tiada line -> kekal sebagai baris peringkat dokumen.
            if (r.unallocated().signum() > 0) {
                em.createNativeQuery("""
                        INSERT INTO fi_allocation
                            (sp_code, account_id, debit_document_id, credit_document_id,
                             debit_document_line_id, amount, status, reverses_allocation_id,
                             journal_entry_id, created_at, created_by, updated_at, updated_by, version)
                        SELECT sp_code, account_id, debit_document_id, credit_document_id,
                               NULL, :amt, status, reverses_allocation_id,
                               journal_entry_id, created_at, created_by, NOW(), updated_by, 0
                        FROM fi_allocation WHERE id = :orig
                        """)
                        .setParameter("amt", r.unallocated())
                        .setParameter("orig", allocId)
                        .executeUpdate();
                created++;
            }

            em.flush();   // lelaran seterusnya mesti nampak agihan ini
            processed++;
        }

        BigDecimal sumAfter = sumActive(spCode);
        if (sumBefore.compareTo(sumAfter) != 0) {
            throw new IllegalStateException(
                    "Backfill mengubah jumlah alokasi SP " + spCode + ": sebelum " + sumBefore
                            + ", selepas " + sumAfter + " — digulung semula.");
        }

        log.info("Backfill line-level SP {}: {} alokasi diproses, {} baris baharu, jumlah kekal {}",
                spCode, processed, created, sumAfter);
        return new Report(processed, created, sumBefore, sumAfter, skipped);
    }

    private BigDecimal sumActive(String spCode) {
        Object v = em.createNativeQuery(
                "SELECT COALESCE(SUM(amount), 0) FROM fi_allocation "
                        + "WHERE sp_code = :sp AND status = 'ACTIVE'")
                .setParameter("sp", spCode)
                .getSingleResult();
        return v == null ? BigDecimal.ZERO : new BigDecimal(v.toString());
    }
}
