package com.monthley.payment.api;

import java.util.List;

public interface PaymentPort {

    /** Invois tertunggak untuk satu akaun (FIFO order: due_date, period, doc_no). */
    List<OutstandingInvoice> outstandingFor(Long accountId);

    /** Terima bayaran, peruntuk FIFO, post ke ledger. */
    PaymentResult receivePayment(NewPayment payment);

    /** Batalkan resit — contra di ledger, buka semula invois. */
    /**
     * @param receiptId payment.id — BUKAN financial_document.id.
     * @param cancelledBy app_user.id kerani; null jika tiada konteks.
     */
    void cancelReceipt(Long receiptId, String reason, Long cancelledBy);

    /** @deprecated siapa yang membatalkan hilang. */
    @Deprecated
    void cancelReceipt(Long receiptId, String reason);

    /**
     * Batal INVOIS atau nota debit.
     *
     * Alokasi yang membayarnya DILEPASKAN — duit kembali menjadi advance,
     * bukan hilang:
     *
     *   Invois RM500, dibayar RM300 melalui satu resit
     *   Batalkan invois
     *     -> alokasi RM300 menjadi REVERSED
     *     -> invois signed_amount = 0 (dokumen CANCELLED)
     *     -> resit signed_amount kekal -300
     *     -> baki = -300, iaitu kredit RM300
     *
     * Duit masih ada dan bayaran seterusnya akan menggunakannya. Kalau
     * alokasi TIDAK dilepaskan, resit kekal 'digunakan' pada invois yang
     * tidak lagi wujud, dan advance itu tidak boleh dicapai.
     *
     * Catatan ledger dibalikkan sebagai contra (SourceType.CANCELLATION),
     * bukan dipadam — jejak audit kekal.
     *
     * @param invoiceDocumentId financial_document.id
     */
    void cancelInvoice(Long invoiceDocumentId, String reason, Long cancelledBy);
}
