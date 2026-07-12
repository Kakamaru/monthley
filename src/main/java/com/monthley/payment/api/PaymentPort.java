package com.monthley.payment.api;

import java.util.List;

public interface PaymentPort {

    /** Invois tertunggak untuk satu akaun (FIFO order: due_date, period, doc_no). */
    List<OutstandingInvoice> outstandingFor(Long accountId);

    /** Terima bayaran, peruntuk FIFO, post ke ledger. */
    PaymentResult receivePayment(NewPayment payment);

    /** Batalkan resit — contra di ledger, buka semula invois. */
    void cancelReceipt(Long receiptId, String reason);
}
