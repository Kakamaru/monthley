package com.monthley.document.api;

import java.util.Optional;

/** Permukaan awam document — billing & payment cipta dokumen melalui ni. */
public interface DocumentPort {

    /**
     * Cipta invois. Idempotent per (akaun, produk, period).
     * @return id dokumen, atau empty jika sudah wujud.
     */
    Optional<Long> createInvoice(NewInvoice invoice);

    /**
     * Cipta resit (type RECEIPT) sebagai dokumen.
     * @return id dokumen resit.
     */
    Long createReceipt(NewReceipt receipt);

    /**
     * Cipta dokumen adjustment (CREDIT_NOTE / DEBIT_NOTE).
     * Idempotent per (sp, sourceRef) — submit kedua ditolak, return id sedia ada.
     * @return id dokumen adjustment.
     */
    Long createAdjustment(NewAdjustmentDoc adj);

    /**
     * Kunci dokumen (PESSIMISTIC_WRITE) dan pulangkan jumlahnya (amount + tax).
     *
     * Kunci dan bacaan digabung dalam satu operasi kerana memisahkannya
     * memusnahkan tujuan kunci. Dokumen ialah sempadan agregat bagi alokasi —
     * modul payment mengunci melalui port ini, bukan menyentuh entiti dalaman.
     *
     * @return jumlah dokumen
     * @throws IllegalArgumentException jika dokumen tak wujud
     */
    java.math.BigDecimal lockAndGetTotal(Long documentId);

    /** Tandakan dokumen sebagai dibatalkan. */
    void cancelDocument(Long documentId);
}
