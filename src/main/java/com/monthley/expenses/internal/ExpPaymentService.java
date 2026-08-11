package com.monthley.expenses.internal;

import com.monthley.document.api.DocumentNumberPort;
import com.monthley.ledger.api.GlAccounts;
import com.monthley.ledger.api.LedgerPort;
import com.monthley.ledger.api.PostingLine;
import com.monthley.ledger.api.PostingRequest;
import com.monthley.ledger.api.SourceType;
import com.monthley.shared.ModuleGuard;
import com.monthley.shared.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * Baucar bayaran (PV) dan bayaran terus.
 *
 * PV membayar invois pembekal: Dr AP / Cr Bank.
 * Bayaran terus tiada invois: Dr Belanja / Cr Bank.
 *
 * Baki invois DIBACA dari VIEW exp_invoice_balance — tiada baki disimpan
 * untuk menyimpang (ADR 0009 corak yang sama).
 */
@Service
class ExpPaymentService {

    private final ExpPaymentRepository payments;
    private final ExpCashEntryRepository cashEntries;
    private final ExpInvoiceRepository invoices;
    private final ExpCategoryRepository categories;
    private final ExpSettingRepository settings;
    private final LedgerPort ledger;
    private final DocumentNumberPort numbers;
    private final ModuleGuard modules;

    @PersistenceContext
    private EntityManager em;

    ExpPaymentService(ExpPaymentRepository payments, ExpCashEntryRepository cashEntries,
                      ExpInvoiceRepository invoices, ExpCategoryRepository categories,
                      ExpSettingRepository settings, LedgerPort ledger,
                      DocumentNumberPort numbers, ModuleGuard modules) {
        this.payments = payments;
        this.cashEntries = cashEntries;
        this.invoices = invoices;
        this.categories = categories;
        this.settings = settings;
        this.ledger = ledger;
        this.numbers = numbers;
        this.modules = modules;
    }

    record NewPv(Long invoiceId, LocalDate payDate, BigDecimal amount,
                 String method, String refNo, String note) {}

    record NewCashEntry(LocalDate entryDate, Long categoryId, String payee,
                        String description, BigDecimal amount, String method, String refNo) {}

    @Transactional
    Long payInvoice(NewPv req) {
        modules.require(ModuleGuard.PERBELANJAAN, "merekod bayaran pembekal");
        String sp = sp();

        ExpInvoice inv = invoices.findByIdAndSpCode(req.invoiceId(), sp).orElseThrow(
                () -> new IllegalStateException("Invois tidak wujud: " + req.invoiceId()));
        if (inv.getStatus() == ExpInvoice.Status.CANCELLED) {
            throw new IllegalStateException("Invois " + inv.getInvNo() + " telah dibatalkan.");
        }

        BigDecimal amaun = req.amount() == null ? BigDecimal.ZERO
                : req.amount().setScale(2, RoundingMode.HALF_UP);
        if (amaun.signum() <= 0) {
            throw new IllegalStateException("Amaun bayaran mesti lebih daripada sifar.");
        }

        // Kunci invois SEBELUM membaca baki. Tanpa kunci, dua PV serentak
        // membaca baki yang sama dan kedua-duanya lulus — invois terlebih
        // bayar dan AP menjadi negatif. Corak sama seperti AllocationGuard.
        BigDecimal baki = lockAndGetBalance(inv.getId());
        if (amaun.compareTo(baki) > 0) {
            throw new IllegalStateException(
                    "Amaun melebihi baki invois. Baki: " + baki.toPlainString());
        }

        ExpSetting setting = settings.findById(sp).orElseGet(() -> settings.save(new ExpSetting(sp)));
        String pvNo = numbers.next(sp, "EXP_PV", setting.getPvPrefix(),
                setting.getPvNoSize(), setting.getPvNoStart());

        ExpPayment pv = new ExpPayment(sp, pvNo, inv.getId(),
                req.payDate() == null ? LocalDate.now() : req.payDate(),
                amaun, req.method() == null ? "TUNAI" : req.method());
        pv.setRefNo(req.refNo());
        pv.setNote(req.note());
        ExpPayment saved = payments.save(pv);

        // Dr AP / Cr Bank
        Long journalId = ledger.post(new PostingRequest(
                sp, saved.getPayDate(), SourceType.EXP_PAYMENT, saved.getId(),
                "Bayaran " + pvNo + " — invois " + inv.getInvNo(),
                List.of(PostingLine.debit(GlAccounts.ACCOUNTS_PAYABLE, amaun, null),
                        PostingLine.credit(bankGl(setting, sp), amaun, null)),
                null));
        saved.setJournalEntryId(journalId);

        return saved.getId();
    }

    @Transactional
    Long recordCashEntry(NewCashEntry req) {
        modules.require(ModuleGuard.PERBELANJAAN, "merekod bayaran terus");
        String sp = sp();

        if (req.categoryId() == null) {
            throw new IllegalStateException("Kategori wajib dipilih.");
        }
        ExpCategory catg = categories.findByIdAndSpCode(req.categoryId(), sp).orElseThrow(
                () -> new IllegalStateException("Kategori tidak wujud: " + req.categoryId()));

        String payee = req.payee() == null ? "" : req.payee().trim();
        if (payee.isBlank()) {
            throw new IllegalStateException("Penerima wajib diisi.");
        }

        BigDecimal amaun = req.amount() == null ? BigDecimal.ZERO
                : req.amount().setScale(2, RoundingMode.HALF_UP);
        if (amaun.signum() <= 0) {
            throw new IllegalStateException("Amaun mesti lebih daripada sifar.");
        }

        ExpSetting setting = settings.findById(sp).orElseGet(() -> settings.save(new ExpSetting(sp)));
        String voucherNo = numbers.next(sp, "EXP_CASH", setting.getCashPrefix(),
                setting.getCashNoSize(), setting.getCashNoStart());

        ExpCashEntry entry = new ExpCashEntry(sp, voucherNo,
                req.entryDate() == null ? LocalDate.now() : req.entryDate(),
                catg.getId(), payee, amaun,
                req.method() == null ? "TUNAI" : req.method());
        entry.setDescription(req.description());
        entry.setRefNo(req.refNo());
        ExpCashEntry saved = cashEntries.save(entry);

        // Dr Belanja / Cr Bank — tiada AP, kerana tiada invois
        Long journalId = ledger.post(new PostingRequest(
                sp, saved.getEntryDate(), SourceType.EXP_CASH, saved.getId(),
                voucherNo + " — " + payee,
                List.of(PostingLine.debit(glUntuk(catg, sp), amaun, null),
                        PostingLine.credit(bankGl(setting, sp), amaun, null)),
                null));
        saved.setJournalEntryId(journalId);

        return saved.getId();
    }

    @Transactional
    void cancelPayment(Long paymentId, String reason, Long by) {
        modules.require(ModuleGuard.PERBELANJAAN, "membatalkan bayaran");
        ExpPayment pv = payments.findByIdAndSpCode(paymentId, sp()).orElseThrow(
                () -> new IllegalStateException("Bayaran tidak wujud: " + paymentId));
        if (pv.getStatus() == ExpPayment.Status.CANCELLED) {
            throw new IllegalStateException("Bayaran " + pv.getPvNo() + " sudah dibatalkan.");
        }
        if (pv.getJournalEntryId() != null) {
            ledger.reverse(pv.getJournalEntryId(), "Batal " + pv.getPvNo() + ": " + reason);
        }
        pv.cancel(reason, by);
    }

    @Transactional
    void cancelCashEntry(Long entryId, String reason, Long by) {
        modules.require(ModuleGuard.PERBELANJAAN, "membatalkan bayaran terus");
        ExpCashEntry e = cashEntries.findByIdAndSpCode(entryId, sp()).orElseThrow(
                () -> new IllegalStateException("Rekod tidak wujud: " + entryId));
        if (e.getStatus() == ExpCashEntry.Status.CANCELLED) {
            throw new IllegalStateException("Rekod " + e.getVoucherNo() + " sudah dibatalkan.");
        }
        if (e.getJournalEntryId() != null) {
            ledger.reverse(e.getJournalEntryId(), "Batal " + e.getVoucherNo() + ": " + reason);
        }
        e.cancel(reason, by);
    }

    /**
     * Kunci invois, kemudian baca baki dari VIEW.
     *
     * Kunci pada baris invois (bukan VIEW — VIEW tidak boleh dikunci)
     * mensirikan PV untuk invois yang sama. AllocationGuard mengajar
     * pelajaran ini: menyemak tanpa mengunci membenarkan dua bayaran
     * serentak melepasi semakan yang sama.
     */
    private BigDecimal lockAndGetBalance(Long invoiceId) {
        em.createNativeQuery("SELECT id FROM exp_invoice WHERE id = :id FOR UPDATE")
                .setParameter("id", invoiceId)
                .getSingleResult();

        Object v = em.createNativeQuery(
                "SELECT balance FROM exp_invoice_balance WHERE invoice_id = :id")
                .setParameter("id", invoiceId)
                .getSingleResult();
        return new BigDecimal(v.toString()).setScale(2, RoundingMode.HALF_UP);
    }

    private String glUntuk(ExpCategory catg, String sp) {
        Long glId = catg.getGlAccountId();
        if (glId == null && catg.getParentId() != null) {
            glId = categories.findByIdAndSpCode(catg.getParentId(), sp)
                    .map(ExpCategory::getGlAccountId).orElse(null);
        }
        return glId == null ? GlAccounts.EXPENSE_GENERAL : ledger.glCodeFor(sp, glId);
    }

    private String bankGl(ExpSetting setting, String sp) {
        return setting.getBankGlAccountId() == null
                ? GlAccounts.BANK
                : ledger.glCodeFor(sp, setting.getBankGlAccountId());
    }

    private String sp() {
        String s = TenantContext.get();
        if (s == null || s.isBlank()) {
            throw new IllegalStateException("Tiada konteks SP.");
        }
        return s;
    }
}
