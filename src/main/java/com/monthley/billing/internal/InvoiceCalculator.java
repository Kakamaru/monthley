package com.monthley.billing.internal;

import com.monthley.account.api.AccountView;
import com.monthley.account.api.SubscriptionView;
import com.monthley.catalog.api.CatalogPort;
import com.monthley.catalog.api.ProductView;
import com.monthley.shared.Charge;
import com.monthley.shared.ChargeFrequency;
import com.monthley.shared.PeriodIds;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Kira baris invois untuk satu akaun dalam satu period asas.
 *
 * Satu langganan boleh hasilkan BANYAK baris — akaun tahunan dengan produk
 * bulanan bagi 12 baris, satu setiap bulan. Setiap baris bawa Charge sendiri.
 *
 * Rujukan: docs/domain/billing-rules.md
 *          docs/domain/legacy-generator-analysis.md §3
 */
@Component
class InvoiceCalculator {

    private final CatalogPort catalog;

    InvoiceCalculator(CatalogPort catalog) {
        this.catalog = catalog;
    }

    /**
     * @param base     ufuk larian — julat period pada aras akaun. Kekal
     *                 sebagai label dokumen (createAndPost) dan sebagai
     *                 gate untuk produk yang sama atau lebih halus.
     * @param runMonth bulan larian; diperlukan kerana anjakan mod kini
     *                 dikenakan pada frekuensi yang lebih KASAR antara
     *                 akaun dan produk (ADR 0013).
     */
    List<CalculatedLine> linesFor(AccountView account,
                                  List<SubscriptionView> subscriptions,
                                  Charge base,
                                  java.time.YearMonth runMonth,
                                  com.monthley.shared.GenMode mode,
                                  BillingContext ctx) {

        List<CalculatedLine> lines = new ArrayList<>();

        for (SubscriptionView sub : subscriptions) {

            // Anak pakej tidak dicaj — hanya parent. Legacy §3.3.
            if (sub.parentSubscriptionId() != null) continue;

            Optional<ProductView> maybe = catalog.findById(sub.productId());
            if (maybe.isEmpty()) continue;
            ProductView product = maybe.get();

            if (sub.quantity() == null || sub.quantity().signum() <= 0) continue;

            ChargeFrequency freq = product.chargeFrequency();
            if (freq == null || freq == ChargeFrequency.PER_USE) {
                continue;   // PER_USE ikut laluan usage berasingan
            }

            LocalDate effStart = later(account.startDate(), sub.startDate());
            LocalDate effEnd = earlier(account.expiryDate(), sub.endDate());

            // Start Charging ialah SUIS proration, dan ia wujud di DUA
            // tempat: akaun dan langganan produk. effStart sudah MAX kedua-
            // duanya, jadi effStart != null bermakna sekurang-kurangnya satu
            // diisytihar.
            //
            // Diisi  -> SP mengisytihar tarikh mula sebenar; prorate dari situ.
            // Kosong -> tiada tarikh langsung; kitaran berjalan dicaj PENUH.
            //
            // Sebab: tanpa isytihar SP, memprorate bermakna mengenakan caj
            // berdasarkan kelajuan kemasukan data.
            //
            // Sehingga hari ini syarat ini membaca account.startDate() SAHAJA,
            // sedangkan effStart membaca dua-dua. Kerani yang mengisi tarikh
            // pada langganan produk tetapi tidak pada akaun mendapat caj
            // PENUH — tarikhnya menentukan BILA tetapi bukan BERAPA, tanpa
            // amaran. Alasan asal ("sub.start_date lalai kepada tarikh cipta
            // akaun") sudah luput: auto-isi itu dibuang, jadi nilai di sana
            // kini isytihar kerani, sama seperti akaun.
            //
            // Rujuk docs/domain/billing-rules.md §6
            boolean canProrate = effStart != null && product.prorated();

            BigDecimal rate = resolveRate(sub, product, ctx);

            if (freq == ChargeFrequency.ONE_TIME) {
                oneTimeLine(account, sub, product, base, rate, effStart, effEnd, ctx)
                        .ifPresent(lines::add);
                continue;
            }

            for (Charge charge : PeriodResolver.chargesFor(
                    runMonth, mode, account.chargeFrequency(), freq,
                    product.anchorMonth(), effStart, effEnd)) {

                recurringLine(account, sub, product, charge, rate, canProrate, ctx)
                        .ifPresent(lines::add);
            }
        }
        return lines;
    }

    // ── Baris berulang ───────────────────────────────────────────────

    private Optional<CalculatedLine> recurringLine(AccountView account,
                                                   SubscriptionView sub,
                                                   ProductView product,
                                                   Charge charge,
                                                   BigDecimal rate,
                                                   boolean canProrate,
                                                   BillingContext ctx) {

        BigDecimal ratio = ProrationCalculator.prorationRatio(
                canProrate, charge, ctx.excludedPeriodIds());

        if (ratio.signum() <= 0) return Optional.empty();   // bulan dikecualikan sepenuhnya

        BigDecimal amount = ProrationCalculator.lineAmount(
                rate, sub.quantity(), ratio, ctx.minDenom());
        if (amount.signum() == 0) return Optional.empty();

        return Optional.of(new CalculatedLine(
                product.id(), account.id(), charge, product.name(), null,
                sub.quantity(), rate, ratio, amount,
                ProrationCalculator.taxAmount(amount, ctx.taxRate()),
                product.incomeGlAccountId(),
                false));
    }

    // ── Baris ONE_TIME ───────────────────────────────────────────────

    /**
     * ONE_TIME: sekali seumur hidup, period = TAHUN semasa, tiada proration.
     *
     * Legacy guna sub.last_charged_period sebagai penunjuk — batalkan invois
     * dan produk tersekat selamanya. Kita guna idem_key dengan onceOnly=true:
     * batal -> doc_cancelled=1 -> idem_key NULL -> boleh jana semula (V52).
     *
     * Sehingga V52 komen ini menyebut active=0, tetapi TIADA kod pernah
     * menetapkannya — mekanisme didokumenkan, tidak dibina, dan ralat
     * legacy yang sama hidup di sini.
     *
     * Rujukan: legacy-generator-analysis.md §3.4, V18
     */
    private Optional<CalculatedLine> oneTimeLine(AccountView account,
                                                 SubscriptionView sub,
                                                 ProductView product,
                                                 Charge base,
                                                 BigDecimal rate,
                                                 LocalDate effStart,
                                                 LocalDate effEnd,
                                                 BillingContext ctx) {

        // Belum bermula, atau sudah tamat.
        if (effStart != null && effStart.isAfter(base.cycleEnd())) return Optional.empty();
        if (effEnd != null && effEnd.isBefore(base.cycleStart())) return Optional.empty();

        int year = base.cycleStart().getYear();
        LocalDate yearStart = LocalDate.of(year, 1, 1);
        LocalDate yearEnd = LocalDate.of(year, 12, 31);

        Charge charge = new Charge(PeriodIds.ofYear(year), yearStart, yearEnd, yearStart, yearEnd);

        BigDecimal amount = ProrationCalculator.lineAmount(
                rate, sub.quantity(), BigDecimal.ONE, ctx.minDenom());
        if (amount.signum() == 0) return Optional.empty();

        return Optional.of(new CalculatedLine(
                product.id(), account.id(), charge, product.name(), null,
                sub.quantity(), rate, BigDecimal.ONE, amount,
                ProrationCalculator.taxAmount(amount, ctx.taxRate()),
                product.incomeGlAccountId(),
                true));
    }

    // ── Bantuan ──────────────────────────────────────────────────────

    /**
     * Harga berkesan. Override hanya dihormati kalau SP benarkan —
     * kalau tidak, subscription.effectiveUnitPrice DIABAIKAN. Legacy §3.1.
     */
    private BigDecimal resolveRate(SubscriptionView sub, ProductView product, BillingContext ctx) {
        if (ctx.allowPriceOverride() && sub.effectiveUnitPrice() != null) {
            return sub.effectiveUnitPrice();
        }
        return product.unitRate();
    }

    /** MAX — null bermakna tiada had. */
    private static LocalDate later(LocalDate a, LocalDate b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }

    /** MIN — null bermakna tiada had. */
    private static LocalDate earlier(LocalDate a, LocalDate b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isBefore(b) ? a : b;
    }
}
