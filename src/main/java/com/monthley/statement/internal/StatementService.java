package com.monthley.statement.internal;

import com.monthley.statement.api.StatementMatch;
import com.monthley.statement.api.StatementModel;
import com.monthley.statement.api.StatementPort;
import com.monthley.statement.api.StatementRow;
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
                        e.title() != null ? e.title() : e.docType(),
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

        return new StatementModel(query.header(spCode, accountId),
                spCode, accountId, from, to,
                opening, rows, closing, arrears);
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
