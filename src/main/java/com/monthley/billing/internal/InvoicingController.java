package com.monthley.billing.internal;

import com.monthley.ledger.api.GlAccounts;
import com.monthley.ledger.api.LedgerPort;
import com.monthley.tenancy.api.BillingSettingsPort;
import com.monthley.tenancy.api.BillingSettingsPort.BillingSettings;
import com.monthley.shared.TenantContext;
import com.monthley.shared.GenMode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Set;
import java.util.HashSet;
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

    @PersistenceContext private EntityManager em;

    InvoicingController(InvoiceGenerationService billing,
                        BillingSettingsPort settings,
                        LedgerPort ledger) {
        this.billing = billing;
        this.settings = settings;
        this.ledger = ledger;
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
    record GenerateSingleResult(Long accountId, String period, String mode, int invoicesPosted) {}

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

        int posted = billing.generateForAccount(sp, req.accountId(), runMonth, mode, ctx);
        return new GenerateSingleResult(req.accountId(), runMonth.toString(), mode.name(), posted);
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
