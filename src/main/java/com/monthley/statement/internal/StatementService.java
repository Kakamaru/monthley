package com.monthley.statement.internal;

import com.monthley.statement.api.StatementModel;
import com.monthley.statement.api.StatementPort;
import com.monthley.statement.api.StatementRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

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
                        // P3: sub-baris padanan daripada VIEW alokasi
                        List.of()))
                .toList();

        BigDecimal closing = rows.isEmpty()
                ? opening
                : rows.get(rows.size() - 1).runningBalance();

        // Tunggakan tidak boleh negatif; baki boleh (ADR 0010 keputusan 9)
        BigDecimal arrears = closing.max(BigDecimal.ZERO);

        return new StatementModel(spCode, accountId, from, to,
                opening, rows, closing, arrears);
    }
}
