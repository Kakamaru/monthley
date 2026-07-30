package com.monthley.statement.internal;

import com.monthley.statement.api.StatementMatch;
import com.monthley.statement.api.StatementModel;
import com.monthley.statement.api.StatementPort;
import com.monthley.statement.api.StatementRow;
import com.monthley.statement.api.StatementTextFormat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
class StatementService implements StatementPort {

    private final StatementQuery query;

    StatementService(StatementQuery query) {
        this.query = query;
    }

    @Override
    public StatementModel forYear(String spCode, long accountId, int year) {
        return forRange(spCode, accountId,
                LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
    }

    @Override
    public StatementModel forRange(String spCode, long accountId,
                                   LocalDate from, LocalDate to) {
        BigDecimal opening = query.openingBalance(spCode, accountId, from);
        // Kepala diambil SEKALI: pemformat memerlukan bahasa dan format
        // tarikh SP, dan model membawanya juga.
        var header = query.header(spCode, accountId);
        var fmt = new StatementFormatter(header.language(), header.dateFormat());

        // Padanan hanya pada baris KREDIT (resit, nota kredit): "invois mana
        // yang aku bayar".
        //
        // Arah bertentangan pernah dipaparkan juga — invois menunjukkan resit
        // yang membayarnya — kerana ia percuma secara teknikal. Hasilnya fakta
        // yang SAMA dicetak dua kali: baris invois berkata 'dibayar oleh
        // RCP008', baris resit berkata 'membayar DN001'. Pembaca terpaksa
        // menghubungkan satu bayaran dua kali. Dibuang.
        Map<Long, List<StatementQuery.AllocationMatch>> byCredit =
                query.matches(spCode, accountId, from, to).stream()
                        .collect(Collectors.groupingBy(
                                StatementQuery.AllocationMatch::creditDocumentId));

        // Baris dokumen untuk sisi debit. Sub-baris sentiasa menjawab satu
        // soalan: dokumen ini terdiri daripada apa. Invois menunjukkan
        // pecahan cajnya; resit menunjukkan invois yang dibayarnya.
        Map<Long, List<StatementQuery.DocumentLine>> byDoc =
                query.lines(spCode, accountId, from, to).stream()
                        .collect(Collectors.groupingBy(
                                StatementQuery.DocumentLine::documentId));

        List<StatementRow> rows = query.entries(spCode, accountId, from, to, opening)
                .stream()
                .map(e -> new StatementRow(
                        e.docDate(),
                        e.docType(),
                        e.docNo(),
                        keteranganFor(e, byDoc, fmt),
                        e.cancelReason(),
                        e.cancelled(),
                        e.signedAmount(),
                        e.runningBalance(),
                        matchesFor(e, byCredit, byDoc)))
                .toList();

        BigDecimal closing = rows.isEmpty()
                ? opening
                : rows.get(rows.size() - 1).runningBalance();

        // Tunggakan tidak boleh negatif; baki boleh (ADR 0010 keputusan 9)
        BigDecimal arrears = closing.max(BigDecimal.ZERO);

        return new StatementModel(header,
                spCode, accountId, from, to,
                opening, rows, closing, arrears);
    }

    @Override
    public com.monthley.statement.api.InvoiceModel invoice(String spCode, long invoiceDocumentId) {
        var head = query.invoiceHead(spCode, invoiceDocumentId);
        var header = query.header(spCode, head.accountId());

        var items = query.invoiceItems(spCode, invoiceDocumentId).stream()
                .map(l -> new com.monthley.statement.api.InvoiceItem(
                        l.description(), l.periodStart(), l.periodEnd(),
                        l.quantity(), l.unitPrice(), l.amount()))
                .toList();

        return new com.monthley.statement.api.InvoiceModel(
                header, spCode, head.accountId(),
                head.documentTitle(), head.invoiceNo(), head.invoiceDate(), head.dueDate(),
                head.issuedAt(), head.periodName(),
                head.balanceBefore(), head.newCharges(), head.taxAmount(),
                head.cancelled(), items);
    }

    @Override
    public com.monthley.statement.api.ReceiptModel receipt(String spCode, long receiptDocumentId) {
        var head = query.receiptHead(spCode, receiptDocumentId);
        var header = query.header(spCode, head.accountId());

        var items = query.receiptItems(spCode, receiptDocumentId).stream()
                .map(l -> {
                    // KUANTITI IALAH KADARAN, bukan sentiasa 1.00.
                    //
                    // Satu alokasi boleh menutup SEBAHAGIAN baris invois:
                    // bayar RM30 atas baris RM50 dan kuantiti ialah 0.60.
                    //
                    // Resit legacy menunjukkan ini dengan jelas:
                    //   Sewa Bulanan, December 2019   0.45   600.00   270.00
                    // 0.45 x 600 = 270. Percubaan pertama menetapkan 1.00
                    // dengan harga = amaun, yang memaparkan 'PARKING MOTOR
                    // 1.00 x 30.00' untuk baris yang harganya RM50.
                    java.math.BigDecimal harga = l.unitPrice();
                    java.math.BigDecimal kuantiti;
                    if (harga == null || harga.signum() == 0) {
                        // Baris tanpa harga seunit (nota debit/kredit).
                        harga = l.amount();
                        kuantiti = java.math.BigDecimal.ONE;
                    } else {
                        kuantiti = l.amount().divide(harga, 2,
                                java.math.RoundingMode.HALF_UP);
                    }
                    return new com.monthley.statement.api.ReceiptItem(
                            l.invoiceNo(), l.description(),
                            l.periodStart(), l.periodEnd(),
                            kuantiti, harga, l.amount());
                })
                .toList();

        return new com.monthley.statement.api.ReceiptModel(
                header, spCode, head.accountId(),
                head.receiptNo(), head.receiptDate(), head.issuedAt(),
                head.paymentMethod(), head.paymentRefNo(), head.remarks(),
                head.amountPaid(),
                head.advance() == null ? java.math.BigDecimal.ZERO : head.advance(),
                head.cancelled(), items);
    }

    @Override
    public com.monthley.statement.api.StatementTextFormat formatterFor(StatementModel m) {
        return new StatementFormatter(m.header().language(), m.header().dateFormat());
    }

    /**
     * Keterangan baris utama.
     *
     * Invois SATU baris tidak mendapat sub-baris (ia hanya akan mengulang
     * dirinya), jadi nama produk mesti muncul DI SINI — jika tidak
     * pelanggan melihat 'Invois M01' sahaja dan tidak tahu dia dicaj untuk
     * apa. Corak sama seperti manual-payment (commit c60d7a5).
     *
     * Invois berbilang baris kekal menunjukkan tajuk dokumen; pecahannya
     * ada dalam sub-baris.
     */
    private static String keteranganFor(
            StatementQuery.DocumentEntry e,
            Map<Long, List<StatementQuery.DocumentLine>> byDoc,
            StatementTextFormat fmt) {

        var lines = byDoc.getOrDefault(e.documentId(), List.of());
        if (lines.size() == 1) {
            var l = lines.get(0);
            String d = l.description();
            if (d != null && !d.isBlank()) {
                // Tempoh MESTI disertakan. Selepas split ikut tempoh (ADR 0011),
                // dua belas invois bulanan bagi produk yang sama menghasilkan dua
                // belas baris 'PARKING MOTOR' yang identik — pelanggan tidak dapat
                // membezakan bulan mana. Tiada sub-baris untuk membawanya kerana
                // invois satu baris tidak dipecahkan.
                String tempoh = fmt.period(l.periodStart(), l.periodEnd());
                return tempoh.isBlank() ? d : d + " \u00b7 " + tempoh;
            }
        }
        return e.title() != null ? e.title() : e.docType();
    }

    /**
     * Sub-baris padanan bagi satu dokumen.
     *
     * Dokumen batal tidak menunjukkan padanan: amaunnya sifar, jadi
     * memaparkan apa yang "dibayarnya" akan mengelirukan.
     */
    private static List<StatementMatch> matchesFor(
            StatementQuery.DocumentEntry e,
            Map<Long, List<StatementQuery.AllocationMatch>> byCredit,
            Map<Long, List<StatementQuery.DocumentLine>> byDoc) {

        if (e.cancelled()) {
            return List.of();
        }
        boolean kredit = "RECEIPT".equals(e.docType()) || "CREDIT_NOTE".equals(e.docType());

        if (kredit) {
            // Resit: invois mana yang aku bayar.
            return byCredit.getOrDefault(e.documentId(), List.of()).stream()
                    .map(m -> new StatementMatch(
                            m.debitDocNo(), m.description(),
                            m.periodStart(), m.periodEnd(), m.amount()))
                    .toList();
        }

        // Invois: aku terdiri daripada caj apa. Satu baris sahaja tidak
        // perlu dipecahkan — ia hanya mengulang keterangan dokumen.
        var lines = byDoc.getOrDefault(e.documentId(), List.of());
        if (lines.size() <= 1) {
            return List.of();
        }
        return lines.stream()
                .map(l -> new StatementMatch(
                        null, l.description(),
                        l.periodStart(), l.periodEnd(), l.amount()))
                .toList();
    }
}
