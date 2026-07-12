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

    /** Tandakan dokumen sebagai dibatalkan. */
    void cancelDocument(Long documentId);
}
