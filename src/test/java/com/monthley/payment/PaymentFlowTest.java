package com.monthley.payment;

import com.monthley.billing.internal.*;
import com.monthley.ledger.api.GlAccounts;
import com.monthley.ledger.internal.ChartOfAccountSeeder;
import com.monthley.payment.api.*;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Aliran PENUH: jana invois → bayar (FIFO knock-off) → sahkan ledger.
 * Membuktikan billing + document + payment + ledger bekerja bersama.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PaymentFlowTest {

    @Autowired InvoiceGenerationService billing;
    @Autowired PaymentPort payment;
    @Autowired ChartOfAccountSeeder seeder;
    @PersistenceContext EntityManager em;

    Long accountId;

    @BeforeEach
    void setup() {
        em.createNativeQuery("""
            INSERT IGNORE INTO service_provider (sp_code, name, status, created_at, updated_at, version)
            VALUES ('SPP', 'SP Payment Test', 'ACTIVE', NOW(), NOW(), 0)
            """).executeUpdate();
        seeder.seedFor("SPP");

        em.createNativeQuery("""
            INSERT INTO product (sp_code, code, name, charge_frequency, unit_rate,
                                 main_product, mandatory, prorated, late_penalty, status,
                                 created_at, updated_at, version)
            VALUES ('SPP', 'MF', 'Maintenance', 'MONTHLY', 80.00, 0,0,0,0,'ACTIVE', NOW(), NOW(), 0)
            """).executeUpdate();
        Long productId = ((Number) em.createNativeQuery(
                "SELECT id FROM product WHERE sp_code='SPP' AND code='MF'").getSingleResult()).longValue();

        em.createNativeQuery("""
            INSERT INTO account (sp_code, account_no, account_name, charge_frequency,
                                 start_date, status, cached_balance, created_at, updated_at, version)
            VALUES ('SPP', 'PACC', 'Payer', 'MONTHLY', '2026-01-01', 'ACTIVE', 0, NOW(), NOW(), 0)
            """).executeUpdate();
        accountId = ((Number) em.createNativeQuery(
                "SELECT id FROM account WHERE sp_code='SPP' AND account_no='PACC'").getSingleResult()).longValue();

        em.createNativeQuery("""
            INSERT INTO account_subscription (sp_code, account_id, product_id, quantity,
                                              start_date, status, created_at, updated_at, version)
            VALUES ('SPP', :acc, :prod, 1, '2026-01-01', 'ACTIVE', NOW(), NOW(), 0)
            """).setParameter("acc", accountId).setParameter("prod", productId).executeUpdate();
    }

    private BillingContext ctx() {
        return BillingContext.of("SPP", BigDecimal.ZERO,
                GlAccounts.ACCOUNTS_RECEIVABLE, GlAccounts.TAX_PAYABLE, GlAccounts.SERVICE_INCOME);
    }

    @Test
    @DisplayName("Bayar penuh 1 invois → knock-off, tiada baki")
    void payOneInvoiceFully() {
        billing.generateForSp("SPP", YearMonth.of(2026, 7),
                GenMode.CURRENT, ctx());
        em.flush();

        List<OutstandingInvoice> before = payment.outstandingFor(accountId);
        assertThat(before).hasSize(1);
        assertThat(before.get(0).outstanding()).isEqualByComparingTo("80.00");

        PaymentResult r = payment.receivePayment(new NewPayment(
                "SPP", accountId, new BigDecimal("80.00"),
                PaymentMethod.FPX, "MP-REF-001", List.of()));
        em.flush();

        assertThat(r.allocated()).isEqualByComparingTo("80.00");
        assertThat(r.deposit()).isEqualByComparingTo("0.00");

        List<OutstandingInvoice> after = payment.outstandingFor(accountId);
        assertThat(after).isEmpty();   // tiada baki
    }

    @Test
    @DisplayName("Lebihan bayaran → jadi deposit")
    void overpaymentBecomesDeposit() {
        billing.generateForSp("SPP", YearMonth.of(2026, 7),
                GenMode.CURRENT, ctx());
        em.flush();

        PaymentResult r = payment.receivePayment(new NewPayment(
                "SPP", accountId, new BigDecimal("100.00"),
                PaymentMethod.FPX, "MP-REF-002", List.of()));
        em.flush();

        assertThat(r.allocated()).isEqualByComparingTo("80.00");
        assertThat(r.deposit()).isEqualByComparingTo("20.00");
    }

    @Test
    @DisplayName("Batal resit → invois terbuka semula")
    void cancelReopensInvoice() {
        billing.generateForSp("SPP", YearMonth.of(2026, 7),
                GenMode.CURRENT, ctx());
        em.flush();

        PaymentResult r = payment.receivePayment(new NewPayment(
                "SPP", accountId, new BigDecimal("80.00"),
                PaymentMethod.FPX, "MP-REF-003", List.of()));
        em.flush();
        assertThat(payment.outstandingFor(accountId)).isEmpty();

        payment.cancelReceipt(r.receiptId(), "ujian batal");
        em.flush();

        // Invois terbuka semula — baki kembali RM80
        List<OutstandingInvoice> after = payment.outstandingFor(accountId);
        assertThat(after).hasSize(1);
        assertThat(after.get(0).outstanding()).isEqualByComparingTo("80.00");
    }

}
