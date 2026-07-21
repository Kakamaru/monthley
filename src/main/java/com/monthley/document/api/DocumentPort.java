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

    /** Tandakan dokumen sebagai dibatalkan. */
    void cancelDocument(Long documentId);
}
