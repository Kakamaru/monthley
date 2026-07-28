package com.monthley.payment.api;

import java.math.BigDecimal;

/**
 * Hasil bayaran.
 *
 * DUA ID, dan ia BUKAN perkara yang sama:
 *   paymentId         — payment.id, untuk cancelReceipt
 *   receiptDocumentId — financial_document.id, untuk resit PDF
 *
 * Medan asal bernama 'receiptId' tetapi mengembalikan payment.id. Nama itu
 * menyesatkan penulis ujian dan penulis butang cetak resit, kedua-duanya
 * pada hari yang sama.
 */
public record PaymentResult(
        Long paymentId,
        Long receiptDocumentId,
        String receiptNo,
        BigDecimal allocated,
        BigDecimal deposit) {   // lebihan → customer deposit
}
