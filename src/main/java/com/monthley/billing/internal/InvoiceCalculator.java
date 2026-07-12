package com.monthley.billing.internal;

import com.monthley.account.api.SubscriptionView;
import com.monthley.catalog.api.CatalogPort;
import com.monthley.catalog.api.ProductView;
import com.monthley.shared.ChargeFrequency;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Kira baris invois untuk satu akaun dalam satu tempoh.
 * Menyatukan ChargeCycleCalculator + ProrationCalculator (termasuk pembundaran denom).
 */
@Component
class InvoiceCalculator {

    private final CatalogPort catalog;

    InvoiceCalculator(CatalogPort catalog) {
        this.catalog = catalog;
    }

    List<CalculatedLine> linesFor(Long accountId,
                                  List<SubscriptionView> subscriptions,
                                  YearMonth period,
                                  BillingContext ctx) {

        List<CalculatedLine> lines = new ArrayList<>();

        for (SubscriptionView sub : subscriptions) {
            Optional<ProductView> maybe = catalog.findById(sub.productId());
            if (maybe.isEmpty()) continue;
            ProductView product = maybe.get();

            if (!overlaps(sub, period)) continue;

            if (product.chargeFrequency() == ChargeFrequency.PER_USE) continue;
            if (product.chargeFrequency() != ChargeFrequency.ONE_TIME
                    && !ChargeCycleCalculator.shouldCharge(
                        product.chargeFrequency(), product.anchorMonth(),
                        sub.startDate(), period)) {
                continue;
            }

            BigDecimal rate = sub.effectiveUnitPrice() != null
                    ? sub.effectiveUnitPrice()
                    : product.unitRate();

            // proration + pembundaran denom (per baris)
            BigDecimal amount = ProrationCalculator.lineAmount(
                    rate, sub.quantity(), product.prorated(),
                    sub.startDate(), sub.endDate(), period, ctx.minDenom());

            if (amount.signum() == 0) continue;

            BigDecimal tax = ProrationCalculator.taxAmount(amount, ctx.taxRate());

            lines.add(new CalculatedLine(
                    product.id(), accountId, product.name(),
                    sub.quantity(), rate, amount, tax,
                    product.incomeGlAccountId()));
        }
        return lines;
    }

    private boolean overlaps(SubscriptionView sub, YearMonth period) {
        var periodStart = period.atDay(1);
        var periodEnd = period.atEndOfMonth();
        boolean afterStart = !sub.startDate().isAfter(periodEnd);
        boolean beforeEnd = sub.endDate() == null || !sub.endDate().isBefore(periodStart);
        return afterStart && beforeEnd;
    }
}
