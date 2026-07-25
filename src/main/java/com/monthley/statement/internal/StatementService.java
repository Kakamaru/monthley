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

        // Padanan diindeks DUA KALI daripada satu query. Baris kredit (resit,
        // nota kredit) melihat sisi kredit — "invois mana yang aku bayar".
        // Baris debit (invois, nota debit) melihat sisi debit — "resit mana
        // yang membayar aku". Legacy hanya boleh yang pertama.
        var all = query.matches(spCode, accountId, from, to);
        Map<Long, List<StatementQuery.AllocationMatch>> byCredit = all.stream()
                .collect(Collectors.groupingBy(StatementQuery.AllocationMatch::creditDocumentId));
        Map<Long, List<StatementQuery.AllocationMatch>> byDebit = all.stream()
                .collect(Collectors.groupingBy(StatementQuery.AllocationMatch::debitDocumentId));

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
                        matchesFor(e, byCredit, byDebit)))
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
            Map<Long, List<StatementQuery.AllocationMatch>> byDebit) {

        if (e.cancelled()) {
            return List.of();
        }
        boolean kredit = "RECEIPT".equals(e.docType()) || "CREDIT_NOTE".equals(e.docType());
        var sumber = kredit
                ? byCredit.getOrDefault(e.documentId(), List.of())
                : byDebit.getOrDefault(e.documentId(), List.of());

        return sumber.stream()
                .map(m -> new StatementMatch(
                        kredit ? m.debitDocNo() : m.creditDocNo(),
                        m.description(),
                        m.periodStart(),
                        m.periodEnd(),
                        m.amount()))
                .toList();
    }
}
