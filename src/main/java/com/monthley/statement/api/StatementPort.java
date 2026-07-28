package com.monthley.statement.api;

import java.time.LocalDate;

/**
 * SATU perkhidmatan, TIGA pemanggil: ikon akaun, portal pelanggan, tab
 * Laporan. Tiada query penyata di luar modul ini (ADR 0010 keputusan 1).
 */
public interface StatementPort {

    /** Penyata bagi satu tahun kalendar, dengan baki bawa ke hadapan. */
    StatementModel forYear(String spCode, long accountId, int year);

    /** Penyata bagi julat sebarang. Julat penuh = "semua rekod". */
    StatementModel forRange(String spCode, long accountId, LocalDate from, LocalDate to);

    /**
     * Pemformat mengikut tetapan SP model ini (bahasa, format tarikh).
     *
     * Pemanggil yang membina JSON menggunakannya supaya peraturan format
     * hidup di SATU tempat sahaja. Frontend memaparkan rentetan yang
     * diterima; ia tidak memformat tarikh sendiri.
     */
    StatementTextFormat formatterFor(StatementModel model);

    /**
     * Resit tunggal.
     *
     * @param receiptDocumentId id DOKUMEN resit (financial_document.id),
     *        bukan payment.id — PaymentResult.receiptId() mengembalikan
     *        payment.id, yang menyesatkan (soalan terbuka 16).
     */
    ReceiptModel receipt(String spCode, long receiptDocumentId);
}
