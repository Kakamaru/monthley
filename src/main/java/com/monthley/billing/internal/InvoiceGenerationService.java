package com.monthley.billing.internal;

import com.monthley.account.api.AccountPort;
import com.monthley.account.api.AccountView;
import com.monthley.account.api.SubscriptionView;
import com.monthley.document.api.*;
import com.monthley.ledger.api.*;
import com.monthley.shared.Charge;
import com.monthley.shared.GenMode;
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
 * Aliran per akaun:
 *   1. period asas = anjak mod pada aras charge_frequency AKAUN
 *   2. kira baris — setiap baris bawa period LIPUTAN sendiri (aras produk)
 *   3. cipta SATU dokumen invois (idempotent via idem_key)
 *   4. post journal ke ledger
 *
 * SATU invois per akaun per larian — bukan satu per period. Akaun tahunan
 * dengan produk bulanan = 1 invois, 12 baris. Disahkan lawan production.
 *
 * Posting ledger SEGERAK dalam transaction yang sama. JANGAN tukar jadi
 * event Modulith: @ApplicationModuleListener ialah @Async + REQUIRES_NEW,
 * yang akan mencipta semula bug family 3 sebagai seni bina.
 * Rujuk docs/domain/accounting-invariants.md §7
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
                             GenMode mode, BillingContext ctx) {
        int posted = 0;

        for (AccountView account : accounts.activeAccountsFor(spCode)) {
            List<SubscriptionView> subs = accounts.activeSubscriptions(account.id());
            if (subs.isEmpty()) continue;

            Charge base = PeriodResolver.basePeriod(runMonth, mode, account.chargeFrequency());

            List<CalculatedLine> lines = calculator.linesFor(account, subs, base, ctx);
            if (lines.isEmpty()) continue;

            if (createAndPost(spCode, account, base, lines, ctx)) {
                posted++;
            }
        }
        return posted;
    }

    /** @return true kalau invois dicipta & di-post; false kalau diskip (idempotent). */
    private boolean createAndPost(String spCode, AccountView account, Charge base,
                                  List<CalculatedLine> lines, BillingContext ctx) {

        LocalDate docDate = LocalDate.now();

        List<NewDocumentLine> docLines = new ArrayList<>();
        for (CalculatedLine l : lines) {
            docLines.add(new NewDocumentLine(
                    l.productId(), l.accountId(), l.charge().periodId(),
                    l.description(), l.quantity(), l.unitRate(), l.prorationRatio(),
                    l.amount(), l.taxAmount(),
                    l.charge().coverageStart(), l.charge().coverageEnd(),
                    l.onceOnly()));
        }

        NewInvoice inv = new NewInvoice(
                spCode, account.id(), base.periodId(),
                docDate, docDate.plusDays(ctx.termDays()),
                "Invois " + account.accountNo(), docLines);

        Optional<Long> docId = documents.createInvoice(inv);
        if (docId.isEmpty()) {
            return false;   // sudah dijana — idem_key menolak
        }

        ledger.post(new PostingRequest(
                spCode, docDate, SourceType.INVOICE, docId.get(),
                "Invois " + account.accountNo(),
                postingLines(account, lines, ctx), null));

        return true;
    }

    /**
     * Dr AR (gross) / Cr Income per baris / Cr Tax.
     *
     * Kredit hasil ikut GL PRODUK, bukan satu GL lalai — kalau tidak, sewa,
     * maintenance, sinking fund dan insurance semua bercampur dalam satu akaun
     * dan chart of accounts tidak berfungsi.
     */
    private List<PostingLine> postingLines(AccountView account,
                                           List<CalculatedLine> lines,
                                           BillingContext ctx) {

        BigDecimal net = lines.stream().map(CalculatedLine::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal tax = lines.stream().map(CalculatedLine::taxAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<PostingLine> pl = new ArrayList<>();
        pl.add(PostingLine.debit(ctx.arGlCode(), net.add(tax), account.id()));

        for (CalculatedLine l : lines) {
            if (l.amount().signum() == 0) continue;
            String gl = l.incomeGlAccountId() != null
                    ? String.valueOf(l.incomeGlAccountId())
                    : ctx.defaultIncomeGlCode();
            pl.add(PostingLine.credit(gl, l.amount(), null));
        }

        if (tax.signum() > 0) {
            pl.add(PostingLine.credit(ctx.taxGlCode(), tax, null));
        }
        return pl;
    }
}
