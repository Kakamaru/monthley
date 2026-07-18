package com.monthley.billing;

import com.monthley.billing.internal.BillingContext;
import com.monthley.billing.internal.InvoiceGenerationService;
import com.monthley.billing.internal.PeriodResolver;
import com.monthley.ledger.api.GlAccounts;
import com.monthley.ledger.internal.ChartOfAccountSeeder;
import com.monthley.shared.GenMode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ujian aliran PENUH: SP + akaun + produk + langganan → jana invois → journal di ledger.
 * Membuktikan billing engine menyatukan account + catalog + ledger.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class InvoiceGenerationServiceTest {

    @Autowired InvoiceGenerationService billing;
    @Autowired ChartOfAccountSeeder seeder;
    @PersistenceContext EntityManager em;

    Long accountId;
    Long productId;

    @BeforeEach
    void setup() {
        em.createNativeQuery("""
            INSERT IGNORE INTO service_provider (sp_code, name, status, created_at, updated_at, version)
            VALUES ('SPB', 'SP Billing Test', 'ACTIVE', NOW(), NOW(), 0)
            """).executeUpdate();
        seeder.seedFor("SPB");

        // Produk: Maintenance Fee RM80 bulanan
        em.createNativeQuery("""
            INSERT INTO product (sp_code, code, name, charge_frequency, unit_rate,
                                 main_product, mandatory, prorated, late_penalty, status,
                                 created_at, updated_at, version)
            VALUES ('SPB', 'MF', 'Maintenance Fee', 'MONTHLY', 80.00,
                    0, 0, 0, 0, 'ACTIVE', NOW(), NOW(), 0)
            """).executeUpdate();
        productId = ((Number) em.createNativeQuery(
                "SELECT id FROM product WHERE sp_code='SPB' AND code='MF'")
                .getSingleResult()).longValue();

        // Akaun bulanan, mula Jan 2026
        em.createNativeQuery("""
            INSERT INTO account (sp_code, account_no, account_name, charge_frequency,
                                 start_date, status, cached_balance, created_at, updated_at, version)
            VALUES ('SPB', 'ACC001', 'Ahmad', 'MONTHLY', '2026-01-01', 'ACTIVE', 0, NOW(), NOW(), 0)
            """).executeUpdate();
        accountId = ((Number) em.createNativeQuery(
                "SELECT id FROM account WHERE sp_code='SPB' AND account_no='ACC001'")
                .getSingleResult()).longValue();

        // Langganan: akaun langgan Maintenance Fee, qty 1, mula Jan 2026
        em.createNativeQuery("""
            INSERT INTO account_subscription (sp_code, account_id, product_id, quantity,
                                              start_date, status, created_at, updated_at, version)
            VALUES ('SPB', :acc, :prod, 1, '2026-01-01', 'ACTIVE', NOW(), NOW(), 0)
            """)
            .setParameter("acc", accountId)
            .setParameter("prod", productId)
            .executeUpdate();
    }

    private BillingContext ctx() {
        return BillingContext.of("SPB", BigDecimal.ZERO,
                GlAccounts.ACCOUNTS_RECEIVABLE, GlAccounts.TAX_PAYABLE, GlAccounts.SERVICE_INCOME);
    }

    @Test
    @DisplayName("Jana invois bulanan → 1 journal di-post")
    void generatesMonthlyInvoice() {
        int posted = billing.generateForSp("SPB", YearMonth.of(2026, 7),
                GenMode.CURRENT, ctx());

        assertThat(posted).isEqualTo(1);

        // Sahkan journal wujud di ledger untuk SPB
        Long journalCount = ((Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM journal_entry WHERE sp_code='SPB'")
                .getSingleResult()).longValue();
        assertThat(journalCount).isEqualTo(1L);

        // Sahkan journal seimbang: SUM(debit) = SUM(credit) = 80
        BigDecimal debit = (BigDecimal) em.createNativeQuery("""
            SELECT COALESCE(SUM(jl.debit_amount),0) FROM journal_line jl
            JOIN journal_entry je ON je.id = jl.journal_entry_id
            WHERE je.sp_code='SPB'
            """).getSingleResult();
        BigDecimal credit = (BigDecimal) em.createNativeQuery("""
            SELECT COALESCE(SUM(jl.credit_amount),0) FROM journal_line jl
            JOIN journal_entry je ON je.id = jl.journal_entry_id
            WHERE je.sp_code='SPB'
            """).getSingleResult();

        assertThat(debit).isEqualByComparingTo("80.00");
        assertThat(credit).isEqualByComparingTo("80.00");
    }

    @Test
    @DisplayName("Akaun tahunan → SATU invois, 12 baris")
    void yearlyAccountGivesOneInvoiceTwelveLines() {
        // Reka bentuk lama: 12 invois (satu per period).
        // Disahkan lawan production: satu dokumen, doc.period_id = 2026000000,
        // dengan baris membawa period LIPUTAN sendiri (2026110100..2026241200).
        // Rujuk docs/domain/billing-rules.md §3
        em.createNativeQuery(
            "UPDATE account SET charge_frequency='YEAR' WHERE id=:acc")
            .setParameter("acc", accountId).executeUpdate();

        int posted = billing.generateForSp("SPB", YearMonth.of(2026, 1),
                GenMode.CURRENT, ctx());

        assertThat(posted).isEqualTo(1);

        Number lines = (Number) em.createNativeQuery("""
            SELECT COUNT(*) FROM financial_document_line l
            JOIN financial_document d ON d.id = l.document_id
            WHERE d.sp_code='SPB'
            """).getSingleResult();
        assertThat(lines.intValue()).isEqualTo(12);

        Number docPeriod = (Number) em.createNativeQuery(
            "SELECT period_id FROM financial_document WHERE sp_code='SPB'")
            .getSingleResult();
        assertThat(docPeriod.longValue()).isEqualTo(2026000000L);
    }
}
