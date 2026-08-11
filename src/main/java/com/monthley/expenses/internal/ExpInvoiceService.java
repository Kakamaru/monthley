package com.monthley.expenses.internal;

import com.monthley.ledger.api.GlAccounts;
import com.monthley.ledger.api.LedgerPort;
import com.monthley.ledger.api.PostingLine;
import com.monthley.ledger.api.PostingRequest;
import com.monthley.ledger.api.SourceType;
import com.monthley.shared.ModuleGuard;
import com.monthley.shared.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Invois pembekal — cipta, batal, dan posting ledger.
 *
 * SST dikira PER BARIS, bukan diagihkan dari jumlah invois. Itu
 * mengelakkan baki pembundaran yang menjadikan jurnal tidak seimbang,
 * dan ia sepadan dengan cara pembekal sebenar mengira: TNB mengenakan SST
 * atas amaun TNB, bukan atas nisbah invois gabungan.
 *
 * SST masuk ke akaun BELANJA yang sama, bukan akaun cukai berasingan.
 * SST Malaysia tiada tuntutan input (tidak seperti GST), jadi RM1,080
 * yang keluar dari bank ialah RM1,080 kos sebenar. 2100 SST Payable ialah
 * SST yang SP KUTIP daripada pelanggan — perkara yang bertentangan.
 */
@Service
class ExpInvoiceService {

    private final ExpInvoiceRepository invoices;
    private final ExpInvoiceItemRepository items;
    private final ExpSupplierRepository suppliers;
    private final ExpCategoryRepository categories;
    private final ExpSettingRepository settings;
    private final LedgerPort ledger;
    private final ModuleGuard modules;

    ExpInvoiceService(ExpInvoiceRepository invoices, ExpInvoiceItemRepository items,
                      ExpSupplierRepository suppliers, ExpCategoryRepository categories,
                      ExpSettingRepository settings, LedgerPort ledger, ModuleGuard modules) {
        this.invoices = invoices;
        this.items = items;
        this.suppliers = suppliers;
        this.categories = categories;
        this.settings = settings;
        this.ledger = ledger;
        this.modules = modules;
    }

    record NewItem(Long categoryId, String description, BigDecimal amount) {}

    record NewInvoice(Long supplierId, String invNo, LocalDate invDate,
                      LocalDate dueDate, String note, List<NewItem> lines) {}

    @Transactional
    Long create(NewInvoice req) {
        modules.require(ModuleGuard.PERBELANJAAN, "merekod invois pembekal");
        String sp = sp();

        if (req.supplierId() == null) {
            throw new IllegalStateException("Pembekal wajib dipilih.");
        }
        suppliers.findByIdAndSpCode(req.supplierId(), sp).orElseThrow(
                () -> new IllegalStateException("Pembekal tidak wujud untuk organisasi ini."));

        String invNo = req.invNo() == null ? "" : req.invNo().trim();
        if (invNo.isBlank()) {
            throw new IllegalStateException("No. invois pembekal wajib diisi.");
        }
        if (invoices.existsBySpCodeAndSupplierIdAndInvNo(sp, req.supplierId(), invNo)) {
            throw new IllegalStateException(
                    "No. invois " + invNo + " sudah wujud untuk pembekal ini.");
        }
        if (req.lines() == null || req.lines().isEmpty()) {
            throw new IllegalStateException("Sekurang-kurangnya satu baris diperlukan.");
        }

        ExpSetting setting = settings.findById(sp).orElseGet(() -> settings.save(new ExpSetting(sp)));
        BigDecimal kadar = setting.isSstEnabled() ? setting.getSstRate() : BigDecimal.ZERO;

        ExpInvoice inv = new ExpInvoice(sp, invNo, req.supplierId(),
                req.invDate() == null ? LocalDate.now() : req.invDate());
        inv.setDueDate(req.dueDate());
        inv.setNote(req.note());
        inv.setSstRate(kadar);
        ExpInvoice saved = invoices.save(inv);

        // Kumpul belanja per GL semasa memproses baris — satu lintasan,
        // dan posting mendapat satu baris debit per akaun dan bukan per
        // baris invois. Dua baris Utiliti tidak sepatutnya menjadi dua
        // debit ke akaun yang sama.
        Map<String, BigDecimal> perGl = new LinkedHashMap<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal sstJumlah = BigDecimal.ZERO;

        for (NewItem l : req.lines()) {
            if (l.categoryId() == null || l.amount() == null || l.amount().signum() <= 0) {
                continue;
            }
            ExpCategory catg = categories.findByIdAndSpCode(l.categoryId(), sp).orElseThrow(
                    () -> new IllegalStateException("Kategori tidak wujud: " + l.categoryId()));

            BigDecimal amaun = l.amount().setScale(2, RoundingMode.HALF_UP);
            BigDecimal sst = amaun.multiply(kadar)
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

            ExpInvoiceItem item = new ExpInvoiceItem(saved.getId(), catg.getId(), amaun);
            item.setDescription(l.description());
            items.save(item);

            subtotal = subtotal.add(amaun);
            sstJumlah = sstJumlah.add(sst);

            String gl = glUntuk(catg, sp);
            perGl.merge(gl, amaun.add(sst), BigDecimal::add);
        }

        if (subtotal.signum() <= 0) {
            throw new IllegalStateException("Tiada baris sah — semua amaun sifar atau kosong.");
        }

        BigDecimal total = subtotal.add(sstJumlah);
        saved.setSubtotal(subtotal);
        saved.setSstAmount(sstJumlah);
        saved.setTotal(total);

        // Dr Belanja (termasuk SST) / Cr AP
        List<PostingLine> lines = new ArrayList<>();
        perGl.forEach((gl, amt) -> lines.add(PostingLine.debit(gl, amt, null)));
        lines.add(PostingLine.credit(GlAccounts.ACCOUNTS_PAYABLE, total, null));

        Long journalId = ledger.post(new PostingRequest(
                sp, saved.getInvDate(), SourceType.EXP_INVOICE, saved.getId(),
                "Invois pembekal " + invNo, lines, null));
        saved.setJournalEntryId(journalId);

        return saved.getId();
    }

    /**
     * Batal invois — balikkan ledger dengan contra.
     *
     * Bayaran yang sudah dibuat MESTI dibatalkan dahulu; membatalkan
     * invois yang sudah dibayar meninggalkan PV yang menunjuk dokumen
     * mati dan baki AP yang salah.
     */
    @Transactional
    void cancel(Long invoiceId, String reason, Long by) {
        modules.require(ModuleGuard.PERBELANJAAN, "membatalkan invois pembekal");
        String sp = sp();

        ExpInvoice inv = invoices.findByIdAndSpCode(invoiceId, sp).orElseThrow(
                () -> new IllegalStateException("Invois tidak wujud: " + invoiceId));
        if (inv.getStatus() == ExpInvoice.Status.CANCELLED) {
            throw new IllegalStateException("Invois " + inv.getInvNo() + " sudah dibatalkan.");
        }

        if (inv.getJournalEntryId() != null) {
            ledger.reverse(inv.getJournalEntryId(), "Batal invois " + inv.getInvNo() + ": " + reason);
        }
        inv.cancel(reason, by);
    }

    /**
     * GL untuk kategori: induk yang memegangnya, anak mewarisi.
     * NULL di kedua-dua aras jatuh ke 5900 Perbelanjaan Am — kategori
     * baharu yang belum dipetakan tidak sepatutnya memecahkan posting.
     */
    private String glUntuk(ExpCategory catg, String sp) {
        Long glId = catg.getGlAccountId();
        if (glId == null && catg.getParentId() != null) {
            glId = categories.findByIdAndSpCode(catg.getParentId(), sp)
                    .map(ExpCategory::getGlAccountId).orElse(null);
        }
        return glId == null ? GlAccounts.EXPENSE_GENERAL : ledger.glCodeFor(sp, glId);
    }

    private String sp() {
        String s = TenantContext.get();
        if (s == null || s.isBlank()) {
            throw new IllegalStateException("Tiada konteks SP.");
        }
        return s;
    }
}
