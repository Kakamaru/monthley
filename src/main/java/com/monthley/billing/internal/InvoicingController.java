package com.monthley.billing.internal;

import com.monthley.ledger.api.GlAccounts;
import com.monthley.ledger.api.LedgerPort;
import com.monthley.shared.Access;
import com.monthley.tenancy.api.BillingSettingsPort;
import com.monthley.tenancy.api.BillingSettingsPort.BillingSettings;
import com.monthley.shared.TenantContext;
import com.monthley.shared.GenMode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Set;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.YearMonth;

/**
 * REST untuk skrin "Penjanaan Bil (Invois)" — rujuk handoff §5 (tools/generate-invoices).
 *   POST /api/v1/tools/generate-invoices
 *
 * Ini titik di mana billing engine dipanggil oleh manusia.
 */
@RestController
@RequestMapping("/api/v1/tools")
class InvoicingController {

    private final InvoiceGenerationService billing;
    private final BillingSettingsPort settings;
    private final LedgerPort ledger;
    private final AdhocInvoiceService adhoc;

    @PersistenceContext private EntityManager em;

    InvoicingController(InvoiceGenerationService billing,
                        BillingSettingsPort settings,
                        LedgerPort ledger,
                        AdhocInvoiceService adhoc) {
        this.billing = billing;
        this.settings = settings;
        this.ledger = ledger;
        this.adhoc = adhoc;
    }

    record GenerateRequest(
            String period,      // 'YYYY-MM' — null = bulan semasa
            String mode         // CURRENT | PREPAID | POSTPAID — null = ikut setting SP
    ) {}

    record GenerateResult(String spCode, String period, String mode, int invoicesPosted,
                         int accountsScanned, int skippedNoSubscription,
                         int skippedNothingToCharge, int skippedAlreadyGenerated,
                         java.util.List<String> billedPeriods) {}

    @PostMapping("/generate-invoices")
    GenerateResult generate(@RequestBody(required = false) GenerateRequest req) {
        String sp = sp();

        YearMonth runMonth = (req == null || req.period() == null || req.period().isBlank())
                ? YearMonth.now()
                : YearMonth.parse(req.period());

        BillingSettings cfg = settings.forSp(sp);

        // Mode: request boleh mengatasi setting SP (untuk jana manual ad-hoc).
        GenMode mode = (req == null || req.mode() == null || req.mode().isBlank())
                ? GenMode.valueOf(cfg.genMode())
                : GenMode.valueOf(req.mode());

        // GL: setting simpan id (bigint); ledger terjemah ke kod. null -> default.
        String arGl = cfg.arGlAccountId() == null
                ? GlAccounts.ACCOUNTS_RECEIVABLE
                : ledger.glCodeFor(sp, cfg.arGlAccountId());
        String incomeGl = cfg.incomeGlAccountId() == null
                ? GlAccounts.SERVICE_INCOME
                : ledger.glCodeFor(sp, cfg.incomeGlAccountId());

        BillingContext ctx = new BillingContext(
                sp,
                cfg.taxRate(),
                cfg.smallestDenomination().signum() == 0 ? null : cfg.smallestDenomination(),
                cfg.allowPriceOverride(),
                cfg.termDays(),
                excludedPeriodIds(sp),
                arGl,
                GlAccounts.TAX_PAYABLE,
                incomeGl,
                cfg.splitInvoiceByProduct());

        var out = billing.generateDetailed(sp, runMonth, mode, ctx);
        // Tempoh yang BENAR-BENAR dibilkan — bukan bulan larian. POSTPAID pada
        // Julai membilkan Jun; melaporkan runMonth adalah menipu.
        java.util.List<String> billed = out.billedPeriodIds().isEmpty()
                ? java.util.List.of()
                : em.createNativeQuery(
                        "SELECT name_ FROM fi_period WHERE period_id IN (:ids) ORDER BY period_id")
                    .setParameter("ids", out.billedPeriodIds())
                    .getResultList();

        return new GenerateResult(sp, runMonth.toString(), mode.name(), out.invoicesPosted(),
                out.accountsScanned(), out.skippedNoSubscription(),
                out.skippedNothingToCharge(), out.skippedAlreadyGenerated(), billed);
    }

    record GenerateSingleRequest(Long accountId, String period, String mode) {}
    record GenerateSingleResult(Long accountId, String period, String mode,
                               int invoicesPosted,
                               /**
                                * Tempoh LIPUTAN yang dibilkan, tersusun.
                                *
                                * 'period' ialah bulan LARIAN yang kerani pilih.
                                * Untuk postpaid ia satu bulan ke hadapan
                                * daripada liputan, dan untuk akaun YEAR satu
                                * larian menghasilkan dua belas tempoh.
                                * Melaporkan bulan larian sahaja memberitahu
                                * kerani sesuatu yang tidak muncul pada
                                * mana-mana invois.
                                */
                               java.util.List<String> billedPeriods) {}

    @PostMapping("/generate-single")
    GenerateSingleResult generateSingle(@RequestBody GenerateSingleRequest req) {
        String sp = sp();
        if (req == null || req.accountId() == null) {
            throw new IllegalArgumentException("accountId diperlukan.");
        }

        YearMonth runMonth = (req.period() == null || req.period().isBlank())
                ? YearMonth.now()
                : YearMonth.parse(req.period());

        BillingSettings cfg = settings.forSp(sp);
        GenMode mode = (req.mode() == null || req.mode().isBlank())
                ? GenMode.valueOf(cfg.genMode())
                : GenMode.valueOf(req.mode());

        String arGl = cfg.arGlAccountId() == null
                ? GlAccounts.ACCOUNTS_RECEIVABLE
                : ledger.glCodeFor(sp, cfg.arGlAccountId());
        String incomeGl = cfg.incomeGlAccountId() == null
                ? GlAccounts.SERVICE_INCOME
                : ledger.glCodeFor(sp, cfg.incomeGlAccountId());

        BillingContext ctx = new BillingContext(
                sp,
                cfg.taxRate(),
                cfg.smallestDenomination().signum() == 0 ? null : cfg.smallestDenomination(),
                cfg.allowPriceOverride(),
                cfg.termDays(),
                excludedPeriodIds(sp),
                arGl,
                GlAccounts.TAX_PAYABLE,
                incomeGl,
                cfg.splitInvoiceByProduct());

        var hasil = billing.generateForAccountDetailed(
                sp, req.accountId(), runMonth, mode, ctx);

        return new GenerateSingleResult(req.accountId(), runMonth.toString(),
                mode.name(), hasil.invoicesPosted(),
                namaTempoh(hasil.billedPeriodIds()));
    }

    /**
     * Nama tempoh daripada fi_period, tersusun ikut masa.
     *
     * Kerani melihat 'Julai 2026', bukan 2026230700.
     */
    @SuppressWarnings("unchecked")
    private java.util.List<String> namaTempoh(java.util.Set<Long> periodIds) {
        if (periodIds == null || periodIds.isEmpty()) return java.util.List.of();
        var rows = em.createNativeQuery(
                "SELECT name_ FROM fi_period WHERE period_id IN (:ids) "
                + "ORDER BY start_dt")
                .setParameter("ids", periodIds)
                .getResultList();
        java.util.List<String> out = new java.util.ArrayList<>();
        for (Object r : rows) {
            if (r != null) out.add(r.toString());
        }
        return out;
    }

    record PeriodOption(Long periodId, String name, java.time.LocalDate startDt) {}

    /**
     * Tempoh BULANAN untuk dropdown.
     *
     * Julat sengaja sempit: enam bulan ke belakang, dua belas ke hadapan.
     * fi_period mengandungi tempoh untuk bertahun-tahun, dan dropdown
     * dengan dua ratus pilihan menjadikan pemilihan lebih sukar bukan
     * lebih mudah.
     *
     * Ke belakang dibenarkan kerana invois adhoc kadang direkod lewat —
     * caj clamp minggu lepas dikeluarkan hari ini.
     */
    @GetMapping("/periods")
    @SuppressWarnings("unchecked")
    List<PeriodOption> periods() {
        Access.requireAnyRole("melihat tempoh", "SP_ADMIN", "CLERK");

        List<Object[]> rows = em.createNativeQuery("""
                SELECT period_id, name_, start_dt
                FROM   fi_period
                WHERE  DATEDIFF(start_dt, CURDATE()) BETWEEN -190 AND 400
                  AND  DATEDIFF(end_dt, start_dt) BETWEEN 27 AND 31
                ORDER  BY start_dt
                """).getResultList();

        List<PeriodOption> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            out.add(new PeriodOption(
                    ((Number) r[0]).longValue(),
                    (String) r[1],
                    r[2] instanceof java.time.LocalDate d ? d
                            : ((java.sql.Date) r[2]).toLocalDate()));
        }
        return out;
    }

    /**
     * Invois adhoc — kepada orang yang BUKAN pelanggan berdaftar.
     *
     * Caj clamp kepada pemandu luar; jualan buku pada pameran sekolah.
     * Semua berkongsi satu akaun ADHOC-SALES (V50) dan butiran penerima
     * duduk pada dokumen.
     */
    @PostMapping("/adhoc-invoice")
    AdhocInvoiceService.Result adhocInvoice(
            @RequestBody AdhocInvoiceService.Request req) {
        Access.requireAnyRole("menjana invois adhoc", "SP_ADMIN", "CLERK");
        return adhoc.create(sp(), req);
    }

    /** period_id BULAN yang dikecualikan untuk SP ini (invoice_exclude_period). */
    @SuppressWarnings("unchecked")
    private Set<Long> excludedPeriodIds(String spCode) {
        var rows = em.createNativeQuery(
                "SELECT period_id FROM invoice_exclude_period WHERE sp_code = :sp")
                .setParameter("sp", spCode).getResultList();
        Set<Long> out = new HashSet<>();
        for (Object r : rows) {
            if (r != null) out.add(((Number) r).longValue());
        }
        return out;
    }

    private String sp() {
        String sp = TenantContext.get();
        if (sp == null || sp.isBlank()) {
            throw new IllegalStateException("Header X-SP-Id diperlukan");
        }
        return sp;
    }
}
