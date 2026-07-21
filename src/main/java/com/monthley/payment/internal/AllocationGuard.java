package com.monthley.payment.internal;

import com.monthley.document.internal.FinancialDocument;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Penjaga invariant allocation — SATU tempat untuk SEMUA laluan
 * (bayaran, knock manual, auto-knock, adjustment reduction).
 *
 * Invariant (accounting-invariants.md §3):
 *   SUM(alokasi aktif) + amt <= document.amount
 *
 * Dikuatkuasa SEMASA WRITE dengan KUNCI PESIMIS pada dokumen sasaran —
 * bukan dikesan selepas bocor. Kunci menjadikan semua alokasi ke dokumen
 * itu bersiri, menutup lubang race (semak-lepas-baca tanpa kunci).
 *
 * Pengajaran CASE-001: "disiplin kunci per-laluan gagal — satu laluan ingat,
 * satu lupa". Maka invariant duduk di sini, dikongsi, bukan disalin.
 */
@Component
public class AllocationGuard {

    @PersistenceContext
    private EntityManager em;

    /**
     * Kunci dokumen sasaran (PESSIMISTIC_WRITE), semak invariant, lempar jika
     * over-allocate. Panggil SEBELUM cipta PaymentAllocation.
     *
     * @param documentId invois/dokumen debit yang di-allocate
     * @param amount     amaun alokasi baharu yang hendak ditambah
     */
    public void checkAndLock(Long documentId, BigDecimal amount) {
        // 1. Kunci pesimis — dokumen jadi sempadan agregat; alokasi bersiri.
        FinancialDocument doc = em.find(
                FinancialDocument.class, documentId, LockModeType.PESSIMISTIC_WRITE);
        if (doc == null) {
            throw new IllegalArgumentException("Dokumen tak wujud: " + documentId);
        }

        // 2. Jumlah alokasi aktif sedia ada (native — elak isu enum status).
        BigDecimal allocated = sumActive(documentId);

        // 3. Invariant: allocated + amount <= doc.amount (guna total amount+tax).
        BigDecimal cap = doc.getTotal();   // amount + tax_amount
        if (allocated.add(amount).compareTo(cap) > 0) {
            throw new OverAllocationException(documentId, cap, allocated, amount);
        }
    }

    /** SUM alokasi ACTIVE menyasar dokumen (sebagai debit_document). */
    public BigDecimal sumActive(Long documentId) {
        Object v = em.createNativeQuery("""
                SELECT COALESCE(SUM(amount), 0) FROM fi_allocation
                WHERE debit_document_id = :doc AND status = 'ACTIVE'
                """)
                .setParameter("doc", documentId)
                .getSingleResult();
        return v == null ? BigDecimal.ZERO : new BigDecimal(v.toString());
    }

    /** Over-allocation — invariant SUM(alokasi) <= amount dilanggar. */
    public static class OverAllocationException extends RuntimeException {
        public OverAllocationException(Long docId, BigDecimal cap,
                                       BigDecimal allocated, BigDecimal attempted) {
            super("Over-allocation dokumen " + docId + ": cap " + cap
                    + ", sedia " + allocated + ", cuba tambah " + attempted);
        }
    }
}
