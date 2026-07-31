package com.monthley.statement.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Model resit — satu struktur, banyak penulis (sama seperti StatementModel).
 *
 * DUA TARIKH, dua maksud:
 *   receiptDate — bila bayaran DITERIMA (kerani boleh merekod ke belakang)
 *   issuedAt    — bila resit DICETAK
 *
 * Legacy membezakannya: 'Receipt Date' lawan 'Date of Issue'.
 *
 * INVARIAN: SUM(items.amount) + advance = amountPaid.
 * Bayaran lebihan menghasilkan advance; item sahaja tidak akan berjumlah.
 */
public record ReceiptModel(
        StatementHeader header,
        String spCode,
        long accountId,
        String receiptNo,
        LocalDate receiptDate,
        LocalDateTime issuedAt,
        String paymentMethod,
        String paymentRefNo,
        /** Catatan kerani — 'Payment Notes' pada resit legacy. */
        String remarks,
        BigDecimal amountPaid,
        BigDecimal advance,
        /**
         * Penerima resit adhoc — null untuk pelanggan berdaftar.
         *
         * Akaun ADHOC-SALES dikongsi (V50) dan tidak membawa nama
         * sesiapa, jadi 'Terima Daripada' yang dibaca daripada akaun
         * kekal kosong. Resit diserahkan kepada orang awam.
         */
        String issuedToName,
        boolean cancelled,
        List<ReceiptItem> items) {
}
