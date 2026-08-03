package com.monthley.billing.internal;

import com.monthley.account.api.AccountPort;
import com.monthley.account.api.AccountView;
import com.monthley.account.api.SubscriptionView;
import com.monthley.document.api.*;
import com.monthley.payment.api.AdvancePort;
import com.monthley.ledger.api.*;
import com.monthley.shared.Charge;
import com.monthley.shared.GenMode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final UsageChargeQuery usageCharges;
    private final DocumentPort documents;
    private final LedgerPort ledger;
    private final AdvancePort advance;

    InvoiceGenerationService(AccountPort accounts, InvoiceCalculator calculator,
                             DocumentPort documents, LedgerPort ledger,
                             AdvancePort advance, UsageChargeQuery usageCharges) {
        this.accounts = accounts;
        this.calculator = calculator;
        this.documents = documents;
        this.ledger = ledger;
        this.advance = advance;
        this.usageCharges = usageCharges;
    }

    /**
     * Hasil penjanaan dengan SEBAB bila tiada invois dicipta.
     *
     * posted == 0 boleh berlaku atas tiga sebab yang sangat berbeza, dan UI
     * tidak boleh menekanya. Sebelum ini UI sentiasa melaporkan "sudah dijana
     * sebelum ini" — tidak benar bagi akaun baharu yang langganannya bermula
     * selepas tempoh bil.
     */
    public record GenerationOutcome(
            int invoicesPosted,
            int accountsScanned,
            int skippedNoSubscription,     // akaun tiada produk langsung
            int skippedNothingToCharge,    // tiada baris bagi tempoh ini
            int skippedAlreadyGenerated,   // memang pendua
            java.util.Set<Long> billedPeriodIds   // tempoh yang BENAR-BENAR dibilkan
    ) {}

    @Transactional
    public GenerationOutcome generateDetailed(String spCode, YearMonth runMonth,
                                              GenMode mode, BillingContext ctx) {
        int posted = 0, scanned = 0, noSub = 0, nothing = 0, already = 0;
        java.util.Set<Long> periods = new java.util.LinkedHashSet<>();

        for (AccountView account : accounts.activeAccountsFor(spCode)) {
            scanned++;

            // Tempoh LIPUTAN dikumpul di dalam janaSatuAkaun: akaun YEAR
            // dengan produk MONTHLY menghasilkan dua belas invois Jan-Dis
            // dalam satu larian, dan merekod base.periodId() melaporkan
            // SATU tempoh sambil menyembunyikan sebelas yang lain.
            int created = janaSatuAkaun(spCode, account, runMonth, mode, ctx, periods);

            if (created == 0) {
                // Tiada langganan DAN tiada caj penggunaan, atau
                // idem_key menolak. Dibezakan supaya laporan penjanaan
                // bermakna.
                if (accounts.activeSubscriptions(account.id()).isEmpty()) noSub++;
                else nothing++;
            }
            posted += created;
        }
        return new GenerationOutcome(posted, scanned, noSub, nothing, already, periods);
    }

    /** Pembalut nipis — 32 pemanggil sedia ada kekal tidak berubah. */
    @Transactional
    public int generateForSp(String spCode, YearMonth runMonth,
                             GenMode mode, BillingContext ctx) {
        return generateDetailed(spCode, runMonth, mode, ctx).invoicesPosted();
    }

    /**
     * Satu akaun, dengan butiran tempoh.
     *
     * generateForAccount kekal sebagai pembalut nipis supaya pemanggil
     * sedia ada tidak berubah — corak yang sama seperti generateForSp.
     */
    @Transactional
    public GenerationOutcome generateForAccountDetailed(
            String spCode, Long accountId, YearMonth runMonth,
            GenMode mode, BillingContext ctx) {

        AccountView account = accounts.activeAccountsFor(spCode).stream()
                .filter(a -> a.id().equals(accountId))
                .findFirst().orElse(null);
        if (account == null) {
            return new GenerationOutcome(0, 0, 0, 0, 0, java.util.Set.of());
        }

        // Laluan SAMA seperti jana pukal, termasuk caj penggunaan.
        // Kerani yang memuat naik Excel dan menekan Generate Single
        // Invoice mesti mendapat caj itu — dokumen penggunaan menunjukkan
        // tepat aliran tersebut.
        java.util.Set<Long> periods = new java.util.LinkedHashSet<>();
        int created = janaSatuAkaun(spCode, account, runMonth, mode, ctx, periods);

        boolean tiadaLanggan = accounts.activeSubscriptions(account.id()).isEmpty();
        return new GenerationOutcome(created, 1,
                created == 0 && tiadaLanggan ? 1 : 0,
                created == 0 && !tiadaLanggan ? 1 : 0,
                0, periods);
    }

    /**
     * Jana invois untuk SATU akaun sahaja (Generate Single Invoice).
     * Logik sama dengan generateForSp — cuma tapis satu akaun (WHERE), bukan loop semua.
     * createAndPost kekal idempotent (skip kalau period sudah dijana).
     */
    public int generateForAccount(String spCode, Long accountId, YearMonth runMonth,
                                  GenMode mode, BillingContext ctx) {
        return generateForAccountDetailed(spCode, accountId, runMonth, mode, ctx)
                .invoicesPosted();
    }

    /**
     * Cipta satu atau beberapa dokumen mengikut tetapan split (ADR 0008,
     * dipinda oleh ADR 0011).
     *
     * split = 0 -> SATU dokumen mengandungi semua baris
     * split = 1 -> SATU dokumen per PRODUK per TEMPOH
     *
     * Tempoh dalam kunci, bukan produk sahaja. Sebabnya pembatalan:
     * apabila kadar berubah selepas AGM pertengahan tahun, SP mesti boleh
     * membatalkan Ogos hingga Disember tanpa menyentuh Januari hingga
     * Julai yang mungkin sudah dibayar. Dengan satu dokumen bagi dua belas
     * bulan, itu mustahil.
     *
     * Legacy sudah berbuat demikian — Pandan Mewah 11/01/2020 09:24:47
     * menghasilkan EMPAT invois dalam satu cap masa (2 produk x 2 bulan),
     * bukan dua.
     *
     * Kesan pada operasi biasa: TIADA. invoice_gen_freq MONTHLY bermakna
     * satu larian = satu tempoh, jadi produk x tempoh = produk x 1.
     * Perbezaan hanya muncul semasa penjanaan pukal beberapa tempoh.
     *
     * split = 0 kekal SATU dokumen untuk seluruh larian — SP yang memilih
     * itu menerima had pembatalan tersebut (dijelaskan semasa onboarding
     * dan pada skrin tetapan).
     *
     * Baris transaksi sentiasa lengkap dalam kedua-dua kes; hanya bilangan
     * dokumen berbeza. SUM(baris) kekal sama, jadi ledger seimbang.
     *
     * @return bilangan dokumen yang benar-benar dicipta (0 kalau semua diskip)
     */
    /**
     * Satu akaun: baris langganan DAN caj penggunaan, dicipta bersama.
     *
     * Dipanggil oleh kedua-dua laluan penjanaan — pukal dan tunggal.
     * Menyalin logik ini bermakna satu laluan mengambil caj penggunaan
     * dan satu lagi tidak, dan tiada apa yang memberitahu kerani
     * mengapa invoisnya berbeza.
     *
     * TIADA LANGGANAN BUKAN PENGHALANG. Caj penggunaan tidak memerlukan
     * langganan — kerani memuat naik Excel untuk mana-mana akaun di
     * bawah SP. Akaun yang hanya mempunyai caj penggunaan tetap
     * mendapat invois.
     *
     * @return bilangan dokumen dicipta; 0 bermakna tiada apa untuk dibil
     *         ATAU idem_key menolak
     */
    private int janaSatuAkaun(String spCode, AccountView account, YearMonth runMonth,
                              GenMode mode, BillingContext ctx,
                              java.util.Set<Long> periodsOut) {

        List<SubscriptionView> subs = accounts.activeSubscriptions(account.id());
        Charge base = PeriodResolver.basePeriod(runMonth, mode, account.chargeFrequency());

        List<CalculatedLine> lines = new ArrayList<>();
        if (!subs.isEmpty()) {
            lines.addAll(calculator.linesFor(account, subs, base, runMonth, mode, ctx));
        }

        // Caj penggunaan membawa tempoh SENDIRI, dipilih semasa muat
        // naik — bukan tempoh yang mod bil akan kira. Dua muat naik
        // untuk produk yang sama (Jun dan Julai) menghasilkan DUA baris
        // dalam larian yang sama.
        var usage = usageCharges.pendingFor(spCode, account.id());
        usage.forEach(u -> lines.add(u.line()));

        if (lines.isEmpty()) {
            return 0;
        }

        List<Long> docIds = new ArrayList<>();
        int created = createGrouped(spCode, account, base, lines, ctx, docIds);

        if (created > 0) {
            lines.forEach(l -> periodsOut.add(l.charge().periodId()));

            // Tandakan caj penggunaan sebagai sudah dibil. Transaksi
            // SAMA: kalau penciptaan invois digulung, tandaan hilang
            // bersamanya dan caj kekal PENDING untuk larian seterusnya.
            if (!usage.isEmpty() && !docIds.isEmpty()) {
                usageCharges.tandaInvois(
                        usage.stream().map(UsageChargeQuery.Baris::id).toList(),
                        docIds.get(0));
            }
        }
        return created;
    }

    private int createGrouped(String spCode, AccountView account, Charge base,
                              List<CalculatedLine> lines, BillingContext ctx,
                              List<Long> docIdsOut) {
        if (!ctx.splitByProduct()) {
            var id = createAndPost(spCode, account, base, lines, ctx);
            id.ifPresent(docIdsOut::add);
            return id.isPresent() ? 1 : 0;
        }

        // Kunci = (tempoh liputan, produk). LinkedHashMap mengekalkan susunan
        // asal supaya nombor dokumen boleh diramal.
        record Kunci(long periodId, Long productId) {}
        Map<Kunci, List<CalculatedLine>> kumpulan = new LinkedHashMap<>();
        for (CalculatedLine l : lines) {
            kumpulan.computeIfAbsent(
                    new Kunci(l.charge().periodId(), l.productId()),
                    k -> new ArrayList<>()).add(l);
        }

        int created = 0;
        for (Map.Entry<Kunci, List<CalculatedLine>> e : kumpulan.entrySet()) {
            // Dokumen membawa tempoh LIPUTANnya, bukan tempoh larian.
            // ADR 0008 menetapkan tempoh larian kerana satu dokumen boleh
            // merangkumi beberapa tempoh; setelah dipecah ikut tempoh,
            // liputan dan dokumen adalah satu perkara yang sama. Tanpa ini
            // dua belas invois bulanan semuanya bertanda '2025' dan tidak
            // boleh dibezakan dalam senarai.
            Charge tempohDok = e.getValue().get(0).charge();
            var id = createAndPost(spCode, account, tempohDok, e.getValue(), ctx);
            if (id.isPresent()) {
                docIdsOut.add(id.get());
                created++;
            }
        }
        return created;
    }

    /** @return true kalau invois dicipta & di-post; false kalau diskip (idempotent). */
    /**
     * Pulang id dokumen, bukan boolean.
     *
     * docId sudah wujud di dalam; membuangnya bermakna pemanggil tidak
     * boleh menandakan caj penggunaan sebagai sudah dibil. Kosong =
     * idem_key menolak (sudah dijana).
     */
    private Optional<Long> createAndPost(String spCode, AccountView account, Charge base,
                                         List<CalculatedLine> lines, BillingContext ctx) {

        LocalDate docDate = LocalDate.now();

        List<NewDocumentLine> docLines = new ArrayList<>();
        for (CalculatedLine l : lines) {
            docLines.add(new NewDocumentLine(
                    l.productId(), l.accountId(), l.charge().periodId(),
                    l.description(), l.remarks(), l.quantity(), l.unitRate(), l.prorationRatio(),
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
            return Optional.empty();   // sudah dijana — idem_key menolak
        }

        ledger.post(new PostingRequest(
                spCode, docDate, SourceType.INVOICE, docId.get(),
                "Invois " + account.accountNo(),
                postingLines(account, lines, ctx), null));

        // Guna advance sedia ada (ADR 0009 P3). Transaksi SAMA — kesan
        // kewangan segerak, accounting-invariants.md §7.
        //
        // Baki akaun sudah betul tanpa langkah ini (resit dikira sebagai
        // dokumen kredit). Ini menambah PADANAN: tanpanya invois yang sudah
        // ditampung advance masih kelihatan belum dibayar dalam Manual
        // Payment, dan kerani boleh menerima bayaran KEDUA.
        advance.applyAdvance(spCode, account.id(), docId.get());

        return docId;
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
            // NULL = SP tak tetapkan GL produk -> default (pilihan sah).
            // ID tergantung (akaun dipadam) -> glCodeFor campak (data rosak).
            String gl = (l.incomeGlAccountId() == null)
                    ? ctx.defaultIncomeGlCode()
                    : ledger.glCodeFor(account.spCode(), l.incomeGlAccountId());
            pl.add(PostingLine.credit(gl, l.amount(), l.productId()));
        }

        if (tax.signum() > 0) {
            pl.add(PostingLine.credit(ctx.taxGlCode(), tax, null));
        }
        return pl;
    }
}
