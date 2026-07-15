package com.monthley.billing.internal;

import com.monthley.ledger.api.GlAccounts;
import com.monthley.shared.TenantContext;
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

    InvoicingController(InvoiceGenerationService billing) {
        this.billing = billing;
    }

    record GenerateRequest(
            String period,      // 'YYYY-MM' — null = bulan semasa
            String mode,        // CURRENT | PREPAID | POSTPAID — null = CURRENT
            BigDecimal taxRate, // null = 0
            BigDecimal minDenom // null = tiada pembundaran
    ) {}

    record GenerateResult(String spCode, String period, String mode, int invoicesPosted) {}

    @PostMapping("/generate-invoices")
    GenerateResult generate(@RequestBody(required = false) GenerateRequest req) {
        String sp = sp();

        YearMonth runMonth = (req == null || req.period() == null || req.period().isBlank())
                ? YearMonth.now()
                : YearMonth.parse(req.period());

        PeriodResolver.GenMode mode = (req == null || req.mode() == null || req.mode().isBlank())
                ? PeriodResolver.GenMode.CURRENT
                : PeriodResolver.GenMode.valueOf(req.mode());

        BillingContext ctx = new BillingContext(
                sp,
                req == null || req.taxRate() == null ? BigDecimal.ZERO : req.taxRate(),
                req == null ? null : req.minDenom(),
                GlAccounts.ACCOUNTS_RECEIVABLE,
                GlAccounts.TAX_PAYABLE,
                GlAccounts.SERVICE_INCOME);

        int posted = billing.generateForSp(sp, runMonth, mode, ctx);
        return new GenerateResult(sp, runMonth.toString(), mode.name(), posted);
    }

    private String sp() {
        String sp = TenantContext.get();
        if (sp == null || sp.isBlank()) {
            throw new IllegalStateException("Header X-SP-Id diperlukan");
        }
        return sp;
    }
}
