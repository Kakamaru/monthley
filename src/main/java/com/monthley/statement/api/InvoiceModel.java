package com.monthley.statement.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Model invois — satu struktur, banyak penulis (sama seperti penyata
 * dan resit).
 *
 * RINGKASAN TIGA LAJUR, bukan lima seperti legacy:
 *
 *   Baki Sebelum + Caj Baharu = Jumlah Perlu Dibayar
 *
 * Legacy mempunyai 'Adjustments' dan 'New Charges Due' juga, tetapi
 * pelarasan ialah DOKUMEN berasingan (nota kredit/debit) dan bukan
 * sesuatu yang diubah pada invois. Ruangan itu tidak pernah boleh
 * berisi — ketiga-tiga sampel produksi menunjukkan 0.00, dan
 * 'New Charges Due' sentiasa sama dengan 'New Charges'.
 *
 * balanceBefore ialah baki tepat SEBELUM dokumen ini, bukan baki awal
 * bulan. Dua invois split pada tarikh yang sama menunjukkan nombor
 * berbeza: yang kedua memasukkan yang pertama.
 */
public record InvoiceModel(
        StatementHeader header,
        String spCode,
        long accountId,
        /**
         * Tajuk daripada sp_document_setting.invoice_title.
         *
         * BUKAN StatementHeader.statementTitle — itu tajuk PENYATA, dan
         * menggunakannya memaparkan 'Statement of Account' pada invois.
         */
        String documentTitle,
        String invoiceNo,
        LocalDate invoiceDate,
        LocalDate dueDate,
        LocalDateTime issuedAt,
        String periodName,
        BigDecimal balanceBefore,
        BigDecimal newCharges,
        BigDecimal taxAmount,
        /**
         * Invois adhoc — akaun ialah ADHOC-SALES teknikal, bukan
         * pelanggan. Butiran penerima ada pada dokumen (V49/V51).
         */
        boolean adhoc,
        String issuedToName,
        String issuedToEmail,
        String issuedToPhone,
        String remarks,
        boolean cancelled,
        List<InvoiceItem> items) {

    /**
     * Baki Sebelum + Caj Baharu. Satu takrifan, bukan dikira di templat.
     *
     * Untuk ADHOC, baki sebelum diabaikan: akaun dikongsi, jadi bakinya
     * ialah jumlah gabungan pembeli lain. Menunjukkannya kepada seorang
     * pembeli buku bermakna dia melihat hutang orang asing.
     */
    public BigDecimal totalDue() {
        return adhoc ? newCharges : balanceBefore.add(newCharges);
    }

    /** Nama pada BILL TO — penerima untuk adhoc, akaun untuk yang lain. */
    public String billToName() {
        if (adhoc && issuedToName != null && !issuedToName.isBlank()) {
            return issuedToName;
        }
        return header().billtoName() != null && !header().billtoName().isBlank()
                ? header().billtoName() : header().accountName();
    }

    /**
     * Tempoh dipaparkan pada baris item hanya apabila baris mempunyai
     * tempoh BERBEZA.
     *
     * Invois sebulan tidak memerlukannya — tempoh sudah ada di kepala,
     * dan mengulanginya pada setiap baris ialah bunyi. Invois yang
     * merangkumi dua belas bulan memerlukannya, jika tidak dua belas
     * baris 'PARKING MOTOR' kelihatan sama (masalah yang sama seperti
     * penyata, ADR 0011).
     */
    public boolean showItemPeriods() {
        return items.stream()
                .map(InvoiceItem::periodStart)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .count() > 1;
    }
}
