package com.monthley.payment.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * Permintaan bayaran. targetDocumentIds = invois yang pembayar pilih (ikut urutan).
 * Kosong = auto FIFO semua invois tertunggak akaun.
 */
public record NewPayment(
        String spCode,
        Long payerAccountId,
        BigDecimal amount,
        PaymentMethod method,
        String paymentRefNo,       // rujukan mpay/FPX
        List<Long> targetDocumentIds,
        String idempotencyKey) {   // token elak double-entry (ADR 0004); null = tanpa
}
