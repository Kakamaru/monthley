package com.monthley.payment.internal;

import com.monthley.billing.internal.BillingContext;
import com.monthley.billing.internal.InvoiceGenerationService;
import com.monthley.ledger.api.GlAccounts;
import com.monthley.ledger.internal.ChartOfAccountSeeder;
import com.monthley.payment.api.*;
import com.monthley.shared.GenMode;
import com.monthley.shared.PageResponse;
import com.monthley.shared.TenantContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invoice Vs Receipt (paymentReport) — grain per invois, tapis tahun period.
 * Cover: invois multi-resit (stack), invois unpaid (receipts null),
 * filter tahun ikut period billing (bukan doc_date).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PaymentReportTest {

    @Autowired InvoiceGenerationService billing;
    @Autowired PaymentPort payment;
    @Autowired ChartOfAccountSeeder seeder;
    @Autowired ManualPaymentController controller;
    @PersistenceContext EntityManager em;

    Long accountId;

    @BeforeEach
    void setup() {
        em.createNativeQuery("""
            INSERT IGNORE INTO service_provider (sp_code, name, status, created_at, updated_at, version)
            VALUES ('SPR', 'Report Test', 'ACTIVE', NOW(), NOW(), 0)
            """).executeUpdate();
        seeder.seedFor("SPR");

        em.createNativeQuery("""
            INSERT INTO product (sp_code, code, name, charge_frequency, unit_rate,
                                 main_product, mandatory, prorated, late_penalty, status,
                                 created_at, updated_at, version)
            VALUES ('SPR', 'MF', 'Maintenance', 'MONTHLY', 80.00, 0,0,0,0,'ACTIVE', NOW(), NOW(), 0)
            """).executeUpdate();
        Long productId = ((Number) em.createNativeQuery(
                "SELECT id FROM product WHERE sp_code='SPR' AND code='MF'").getSingleResult()).longValue();

        em.createNativeQuery("""
            INSERT INTO account (sp_code, account_no, account_name, charge_frequency,
                                 start_date, status, cached_balance, created_at, updated_at, version)
            VALUES ('SPR', 'RACC', 'Report Payer', 'MONTHLY', '2026-01-01', 'ACTIVE', 0, NOW(), NOW(), 0)
            """).executeUpdate();
        accountId = ((Number) em.createNativeQuery(
                "SELECT id FROM account WHERE sp_code='SPR' AND account_no='RACC'").getSingleResult()).longValue();

        em.createNativeQuery("""
            INSERT INTO account_subscription (sp_code, account_id, product_id, quantity,
                                              start_date, status, created_at, updated_at, version)
            VALUES ('SPR', :acc, :prod, 1, '2026-01-01', 'ACTIVE', NOW(), NOW(), 0)
            """).setParameter("acc", accountId).setParameter("prod", productId).executeUpdate();
    }

    @AfterEach
    void clear() { TenantContext.clear(); SecurityContextHolder.clearContext(); }

    private BillingContext ctx() {
        return BillingContext.of("SPR", BigDecimal.ZERO,
                GlAccounts.ACCOUNTS_RECEIVABLE, GlAccounts.TAX_PAYABLE, GlAccounts.SERVICE_INCOME);
    }

    @Test
    @DisplayName("paymentReport — multi-resit stack + unpaid kosong + tapis tahun period")
    void invoiceVsReceipt() {
        billing.generateForSp("SPR", YearMonth.of(2026, 1), GenMode.CURRENT, ctx());
        billing.generateForSp("SPR", YearMonth.of(2026, 2), GenMode.CURRENT, ctx());
        em.flush();

        List<OutstandingInvoice> out = payment.outstandingFor(accountId);
        assertThat(out).hasSize(2);
        Long janInvId = out.get(0).documentId();

        payment.receivePayment(new NewPayment("SPR", accountId, new BigDecimal("40.00"),
                PaymentMethod.FPX, "MP-A", List.of(janInvId)));
        payment.receivePayment(new NewPayment("SPR", accountId, new BigDecimal("40.00"),
                PaymentMethod.CASH, "MP-B", List.of(janInvId)));
        em.flush();

        TenantContext.set("SPR");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("clerk", "n/a",
                        List.of(new SimpleGrantedAuthority("SP_SPR_CLERK"))));
        PageResponse<ManualPaymentController.PaymentReportRow> rep =
                controller.paymentReport(accountId, "2026", 0, 50);

        assertThat(rep.total()).isEqualTo(2);
        var rows = rep.items();

        var jan = rows.stream().filter(r -> r.period().contains("January")).findFirst().orElseThrow();
        var feb = rows.stream().filter(r -> r.period().contains("February")).findFirst().orElseThrow();

        assertThat(jan.invAmount()).isEqualByComparingTo("80.00");
        assertThat(jan.receipts()).contains(" / ");
        assertThat(jan.receipts()).contains("40.00");
        assertThat(feb.invAmount()).isEqualByComparingTo("80.00");
        assertThat(feb.receipts()).isNull();

        PageResponse<ManualPaymentController.PaymentReportRow> rep2027 =
                controller.paymentReport(accountId, "2027", 0, 50);
        assertThat(rep2027.total()).isEqualTo(0);
    }
}
