package com.monthley.payment.internal;

import com.monthley.document.api.DocumentPort;
import jakarta.persistence.EntityManager;
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

    private final DocumentPort documents;

    AllocationGuard(DocumentPort documents) {
        this.documents = documents;
    }

    /**
     * Kunci dokumen sasaran (PESSIMISTIC_WRITE), semak invariant, lempar jika
     * over-allocate. Panggil SEBELUM cipta PaymentAllocation.
     *
     * @param documentId invois/dokumen debit yang di-allocate
     * @param amount     amaun alokasi baharu yang hendak ditambah
     */
    public void checkAndLock(Long documentId, BigDecimal amount) {
        // 1. Kunci pesimis melalui document::api — dokumen jadi sempadan
        //    agregat; alokasi bersiri. Kunci kekal di modul pemilik data.
        BigDecimal cap = documents.lockAndGetTotal(documentId);   // amount + tax

        // 2. Jumlah alokasi aktif sedia ada (native — elak isu enum status).
        BigDecimal allocated = sumActive(documentId);

        // 3. Invariant: allocated + amount <= cap.
        if (allocated.add(amount).compareTo(cap) > 0) {
            throw new OverAllocationException(documentId, cap, allocated, amount);
        }
    }

    /**
     * Invariant peringkat line (ADR 0006): SUM(alokasi ACTIVE line) + amt
     * <= (line.amount + line.tax_amount).
     *
     * Race sudah tertutup oleh kunci dokumen dalam {@link #checkAndLock} —
     * semua alokasi ke dokumen yang sama bersiri. Semakan ini menangkap
     * PEPIJAT LOGIK (resolver tersalah kira), bukan keadaan perlumbaan.
     *
     * @throws OverAllocationException jika line akan terlebih alokasi
     */
    public void checkLine(Long lineId, BigDecimal amount) {
        Object[] r = (Object[]) em.createNativeQuery("""
                SELECT (l.amount + l.tax_amount),
                       COALESCE((SELECT SUM(a.amount) FROM fi_allocation a
                                 WHERE a.debit_document_line_id = l.id
                                   AND a.status = 'ACTIVE'), 0)
                FROM financial_document_line l WHERE l.id = :line
                """).setParameter("line", lineId).getSingleResult();

        BigDecimal cap = new BigDecimal(r[0].toString());
        BigDecimal allocated = new BigDecimal(r[1].toString());

        if (allocated.add(amount).compareTo(cap) > 0) {
            throw new OverAllocationException(lineId, cap, allocated, amount);
        }
    }

    /**
     * Jumlah dokumen TANPA kunci — untuk mengira ruang sebelum memutuskan
     * berapa hendak dialokasi. Kunci berlaku sekali sahaja, dalam
     * {@link #checkAndLock}.
     */
    public BigDecimal docTotal(Long documentId) {
        Object v = em.createNativeQuery("""
                SELECT amount + tax_amount FROM financial_document WHERE id = :doc
                """).setParameter("doc", documentId).getSingleResult();
        if (v == null) throw new IllegalArgumentException("Dokumen tak wujud: " + documentId);
        return new BigDecimal(v.toString());
    }

    /** SUM alokasi ACTIVE menyasar dokumen (sebagai debit_document). */
    /**
     * Invariant sisi KREDIT: SUM(alokasi ACTIVE dari dokumen kredit) + amt
     * <= nilai dokumen kredit.
     *
     * Sebelum ini hanya sisi debit dijaga — invois tidak boleh menerima
     * alokasi melebihi nilainya, tetapi tiada apa menghalang satu resit
     * daripada mengalokasi lebih daripada nilainya sendiri.
     *
     * Ia tidak penting selagi setiap resit dialokasi SEKALI sahaja, ketika
     * bayaran. Sebaik advance di-knock automatik (ADR 0009 P3), satu resit
     * akan dialokasi merentas BANYAK invois sepanjang beberapa bulan — dan
     * lubang ini jadi berbahaya.
     *
     * Dikunci, bukan sekadar disemak: dua larian jana bil serentak untuk SP
     * yang sama boleh berlumba pada resit advance yang sama. Pengajaran
     * CASE-001 — semakan tanpa kunci memberi rasa selamat yang palsu.
     *
     * Urutan kunci sentiasa debit dahulu, kemudian kredit — tiada deadlock.
     *
     * @param creditDocumentId resit / kredit nota yang membayar
     * @param amount           amaun alokasi baharu
     */
    public void checkAndLockCredit(Long creditDocumentId, BigDecimal amount) {
        BigDecimal cap = documents.lockAndGetTotal(creditDocumentId);
        BigDecimal allocated = sumActiveFromCredit(creditDocumentId);

        if (allocated.add(amount).compareTo(cap) > 0) {
            throw new OverAllocationException("dokumen kredit", creditDocumentId,
                    cap, allocated, amount);
        }
    }

    /** SUM alokasi ACTIVE yang BERASAL daripada dokumen kredit ini. */
    public BigDecimal sumActiveFromCredit(Long creditDocumentId) {
        Object v = em.createNativeQuery("""
                SELECT COALESCE(SUM(amount), 0) FROM fi_allocation
                WHERE credit_document_id = :doc AND status = 'ACTIVE'
                """)
                .setParameter("doc", creditDocumentId)
                .getSingleResult();
        return v == null ? BigDecimal.ZERO : new BigDecimal(v.toString());
    }

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
            this("dokumen", docId, cap, allocated, attempted);
        }

        /** Label memberitahu sisi mana yang dilanggar — penting semasa debug. */
        public OverAllocationException(String label, Long docId, BigDecimal cap,
                                       BigDecimal allocated, BigDecimal attempted) {
            super("Over-allocation " + label + " " + docId + ": cap " + cap
                    + ", sedia " + allocated + ", cuba tambah " + attempted);
        }
    }
}
