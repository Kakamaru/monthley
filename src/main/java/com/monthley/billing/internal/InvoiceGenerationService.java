package com.monthley.billing.internal;

import com.monthley.account.api.AccountPort;
import com.monthley.account.api.AccountView;
import com.monthley.account.api.SubscriptionView;
import com.monthley.document.api.*;
import com.monthley.ledger.api.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Orchestrator jana invois. Menyatukan account + catalog + document + ledger.
 *
 * Aliran per akaun/tempoh:
 *   1. kira baris (cycle + proration)
 *   2. cipta dokumen invois (idempotent — skip jika sudah ada)
 *   3. post journal ke ledger (Dr AR / Cr Income + Tax)
 * Semua dalam satu transaction.
 */
@Service
public class InvoiceGenerationService {

    private final AccountPort accounts;
    private final InvoiceCalculator calculator;
    private final DocumentPort documents;
    private final LedgerPort ledger;

    InvoiceGenerationService(AccountPort accounts, InvoiceCalculator calculator,
                             DocumentPort documents, LedgerPort ledger) {
        this.accounts = accounts;
        this.calculator = calculator;
        this.documents = documents;
        this.ledger = ledger;
    }

    @Transactional
    public int generateForSp(String spCode, YearMonth runMonth,
                             PeriodResolver.GenMode mode, BillingContext ctx) {

        YearMonth base = PeriodResolver.basePeriod(runMonth, mode);
        int posted = 0;

        for (AccountView account : accounts.activeAccountsFor(spCode)) {
            List<SubscriptionView> subs = accounts.activeSubscriptions(account.id());
            if (subs.isEmpty()) continue;

            List<YearMonth> periods = PeriodResolver.periodsFor(
                    base, account.chargeFrequency(),
                    ym(account.startDate()), ym(account.expiryDate()));

            for (YearMonth period : periods) {
                List<CalculatedLine> calc = calculator.linesFor(account.id(), subs, period, ctx);
                if (calc.isEmpty()) continue;

                if (createAndPost(spCode, account, period, calc, ctx)) {
                    posted++;
                }
            }
        }
        return posted;
    }

    /** @return true jika invois dicipta & di-post; false jika diskip (idempotent). */
    private boolean createAndPost(String spCode, AccountView account, YearMonth period,
                                  List<CalculatedLine> calc, BillingContext ctx) {

        // 1. Cipta dokumen invois (idempotent)
        List<NewDocumentLine> docLines = new ArrayList<>();
        for (CalculatedLine l : calc) {
            docLines.add(new NewDocumentLine(
                    l.productId(), l.accountId(), l.description(),
                    l.quantity(), l.unitRate(), l.amount(), l.taxAmount(),
                    period.atDay(1), period.atEndOfMonth()));
        }

        NewInvoice inv = new NewInvoice(
                spCode, account.id(), period.toString(),
                LocalDate.now(), LocalDate.now().plusDays(14),
                "Invois " + account.accountNo() + " " + period, docLines);

        Optional<Long> docId = documents.createInvoice(inv);
        if (docId.isEmpty()) {
            return false;   // sudah dijana untuk tempoh ni — skip
        }

        // 2. Post journal ke ledger
        BigDecimal net = calc.stream().map(CalculatedLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal tax = calc.stream().map(CalculatedLine::taxAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal gross = net.add(tax);

        List<PostingLine> pl = new ArrayList<>();
        pl.add(PostingLine.debit(ctx.arGlCode(), gross, account.id()));
        pl.add(PostingLine.credit(ctx.defaultIncomeGlCode(), net, null));
        if (tax.signum() > 0) {
            pl.add(PostingLine.credit(ctx.taxGlCode(), tax, null));
        }

        ledger.post(new PostingRequest(
                spCode, LocalDate.now(), SourceType.INVOICE, docId.get(),
                "Invois " + account.accountNo() + " " + period, pl, null));

        return true;
    }

    private YearMonth ym(LocalDate d) { return d == null ? null : YearMonth.from(d); }
}
