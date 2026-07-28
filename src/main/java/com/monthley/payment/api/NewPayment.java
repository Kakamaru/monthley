package com.monthley.payment.api;

import java.math.BigDecimal;
import java.time.LocalDate;
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
        String idempotencyKey,     // token elak double-entry (ADR 0004); null = tanpa
        /**
         * Tarikh bayaran DITERIMA — bukan tarikh resit dicipta.
         *
         * Kerani boleh merekod bayaran yang diterima dua hari lepas. Tarikh
         * itu mesti muncul pada resit, dalam penyata, dan dalam ledger; jika
         * tidak baki berjalan salah untuk hari-hari antara, dan rekonsiliasi
         * bank tidak tally.
         *
         * Legacy membezakan kedua-duanya: 'Receipt Date' ialah bila bayaran
         * diterima, 'Date of Issue' ialah bila resit dicetak.
         *
         * null = hari ini.
         */
        LocalDate paymentDate) {
}
