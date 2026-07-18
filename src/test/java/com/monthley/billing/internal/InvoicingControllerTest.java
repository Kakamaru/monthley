package com.monthley.billing.internal;

import com.monthley.ledger.api.GlAccounts;
import com.monthley.ledger.internal.ChartOfAccountSeeder;
import com.monthley.shared.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Menguji InvoicingController.generate() — laluan wiring BillingContext dari
 * setting sebenar. 75 ujian lain memanggil generateForSp() terus dengan ctx()
 * buatan, jadi laluan ini (settings.forSp, glCodeFor, excludedPeriodIds, empat
 * cabang null/bukan-null) tidak pernah disentuh sebelum ini.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class InvoicingControllerTest {

    @Autowired InvoicingController controller;
    @Autowired ChartOfAccountSeeder seeder;
    @PersistenceContext EntityManager em;

    Long accountId, productId;

    @BeforeEach
    void setup() {
        em.createNativeQuery("""
            INSERT IGNORE INTO service_provider (sp_code, name, status, created_at, updated_at, version)
            VALUES ('SPC', 'SP Controller Test', 'ACTIVE', NOW(), NOW(), 0)
            """).executeUpdate();
        seeder.seedFor("SPC");

        // Setting WAJIB wujud — controller JOIN dua table
        em.createNativeQuery("""
            INSERT IGNORE INTO sp_document_setting (sp_code, invoice_gen_mode, allow_price_override)
            VALUES ('SPC', 'CURRENT', 1)
            """).executeUpdate();
        em.createNativeQuery("""
            INSERT IGNORE INTO sp_billing_setting (sp_code, currency, language, payment_term_days,
                                                   tax_rate, smallest_denomination, version)
            VALUES ('SPC', 'MYR', 'ms', 30, 0.00, 0.00, 0)
            """).executeUpdate();

        em.createNativeQuery("""
            INSERT INTO product (sp_code, code, name, charge_frequency, unit_rate,
                                 main_product, mandatory, prorated, late_penalty, status,
                                 created_at, updated_at, version)
            VALUES ('SPC', 'MF', 'Maintenance', 'MONTHLY', 80.00, 0, 0, 0, 0, 'ACTIVE', NOW(), NOW(), 0)
            """).executeUpdate();
        productId = ((Number) em.createNativeQuery(
                "SELECT id FROM product WHERE sp_code='SPC' AND code='MF'").getSingleResult()).longValue();

        em.createNativeQuery("""
            INSERT INTO account (sp_code, account_no, account_name, charge_frequency,
                                 start_date, status, cached_balance, created_at, updated_at, version)
            VALUES ('SPC', 'A1', 'Ali', 'MONTHLY', '2026-01-01', 'ACTIVE', 0, NOW(), NOW(), 0)
            """).executeUpdate();
        accountId = ((Number) em.createNativeQuery(
                "SELECT id FROM account WHERE sp_code='SPC' AND account_no='A1'").getSingleResult()).longValue();

        em.createNativeQuery("""
            INSERT INTO account_subscription (sp_code, account_id, product_id, quantity,
                                              start_date, status, created_at, updated_at, version)
            VALUES ('SPC', :acc, :prod, 1, '2026-01-01', 'ACTIVE', NOW(), NOW(), 0)
            """).setParameter("acc", accountId).setParameter("prod", productId).executeUpdate();

        TenantContext.set("SPC");
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    private BigDecimal glCredit(String code) {
        return (BigDecimal) em.createNativeQuery("""
                SELECT COALESCE(SUM(jl.credit_amount), 0) FROM journal_line jl
                JOIN journal_entry je ON je.id = jl.journal_entry_id
                JOIN chart_of_accounts coa ON coa.id = jl.gl_account_id
                WHERE je.sp_code='SPC' AND coa.code = :c
                """).setParameter("c", code).getSingleResult();
    }

    @Test
    @DisplayName("generate() baca gen_mode dari sp_document_setting")
    void readsGenModeFromSetting() {
        // setting = CURRENT; request mode null -> ikut setting
        var res = controller.generate(new InvoicingController.GenerateRequest("2026-03", null));
        assertThat(res.invoicesPosted()).isEqualTo(1);
        assertThat(res.mode()).isEqualTo("CURRENT");
    }

    @Test
    @DisplayName("GL income NULL dalam setting -> default SERVICE_INCOME (4000)")
    void nullGlUsesDefault() {
        controller.generate(new InvoicingController.GenerateRequest("2026-03", "CURRENT"));
        assertThat(glCredit("4000")).isEqualByComparingTo("80.00");
    }

    @Test
    @DisplayName("smallest_denomination 0 -> tiada pembundaran")
    void zeroDenomNoRounding() {
        controller.generate(new InvoicingController.GenerateRequest("2026-03", "CURRENT"));
        assertThat(glCredit("4000")).isEqualByComparingTo("80.00");
    }

    @Test
    @DisplayName("excludedPeriodIds dibaca -> period dikecualikan tiada bil")
    void excludedPeriodProducesNoInvoice() {
        // Kecualikan Mac 2026: period_id bulanan
        long marId = com.monthley.shared.PeriodIds.ofMonth(java.time.YearMonth.of(2026, 3));
        em.createNativeQuery("""
            INSERT INTO invoice_exclude_period (sp_code, period_id, remarks)
            VALUES ('SPC', :pid, 'ujian')
            """).setParameter("pid", marId).executeUpdate();

        var res = controller.generate(new InvoicingController.GenerateRequest("2026-03", "CURRENT"));

        // Produk bulanan, Mac dikecualikan -> baris gugur -> tiada invois
        assertThat(res.invoicesPosted()).isZero();
    }
}
