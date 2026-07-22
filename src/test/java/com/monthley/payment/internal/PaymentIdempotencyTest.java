package com.monthley.payment.internal;

import com.monthley.billing.internal.*;
import com.monthley.ledger.api.GlAccounts;
import com.monthley.ledger.internal.ChartOfAccountSeeder;
import com.monthley.shared.GenMode;
import com.monthley.payment.api.*;
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
 * Idempotency bayaran manual (ADR 0004) — elak double-entry.
 * Key sama dua kali = satu resit sahaja.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PaymentIdempotencyTest {

    @Autowired InvoiceGenerationService billing;
    @Autowired PaymentPort payment;
    @Autowired ChartOfAccountSeeder seeder;
    @PersistenceContext EntityManager em;

    Long accountId;

    @BeforeEach
    void setup() {
        em.createNativeQuery("""
            INSERT IGNORE INTO service_provider (sp_code, name, status, created_at, updated_at, version)
            VALUES ('SPI', 'SP Idem Test', 'ACTIVE', NOW(), NOW(), 0)
            """).executeUpdate();
        seeder.seedFor("SPI");

        em.createNativeQuery("""
            INSERT INTO product (sp_code, code, name, charge_frequency, unit_rate,
                                 main_product, mandatory, prorated, late_penalty, status,
                                 created_at, updated_at, version)
            VALUES ('SPI', 'MF', 'Maintenance', 'MONTHLY', 80.00, 0,0,0,0,'ACTIVE', NOW(), NOW(), 0)
            """).executeUpdate();
        Long productId = ((Number) em.createNativeQuery(
                "SELECT id FROM product WHERE sp_code='SPI' AND code='MF'").getSingleResult()).longValue();

        em.createNativeQuery("""
            INSERT INTO account (sp_code, account_no, account_name, charge_frequency,
                                 start_date, status, cached_balance, created_at, updated_at, version)
            VALUES ('SPI', 'IACC', 'Payer', 'MONTHLY', '2026-01-01', 'ACTIVE', 0, NOW(), NOW(), 0)
            """).executeUpdate();
        accountId = ((Number) em.createNativeQuery(
                "SELECT id FROM account WHERE sp_code='SPI' AND account_no='IACC'").getSingleResult()).longValue();

        em.createNativeQuery("""
            INSERT INTO account_subscription (sp_code, account_id, product_id, quantity,
                                              start_date, status, created_at, updated_at, version)
            VALUES ('SPI', :acc, :prod, 1, '2026-01-01', 'ACTIVE', NOW(), NOW(), 0)
            """).setParameter("acc", accountId).setParameter("prod", productId).executeUpdate();

        billing.generateForSp("SPI", YearMonth.of(2026, 1), GenMode.CURRENT, ctx());
        em.flush();
    }

    private BillingContext ctx() {
        return BillingContext.of("SPI", BigDecimal.ZERO,
                GlAccounts.ACCOUNTS_RECEIVABLE, GlAccounts.TAX_PAYABLE, GlAccounts.SERVICE_INCOME);
    }

    @Test
    @DisplayName("Key sama dua kali → satu resit sahaja (elak double-entry)")
    void duplicateKeyReturnsSameReceipt() {
        NewPayment req = new NewPayment("SPI", accountId, new BigDecimal("50.00"),
                PaymentMethod.CASH, "REF-1", List.of(), "IDEM-TEST-001");

        PaymentResult r1 = payment.receivePayment(req);
        em.flush();
        PaymentResult r2 = payment.receivePayment(req);   // key sama
        em.flush();

        assertThat(r2.receiptId()).isEqualTo(r1.receiptId());   // resit sama

        long cnt = ((Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM payment WHERE sp_code='SPI' AND idempotency_key=:k")
                .setParameter("k", "IDEM-TEST-001").getSingleResult()).longValue();
        assertThat(cnt).isEqualTo(1);   // satu payment sahaja
    }

    @Test
    @DisplayName("Key berbeza → dua resit berasingan")
    void differentKeyTwoReceipts() {
        PaymentResult r1 = payment.receivePayment(new NewPayment("SPI", accountId,
                new BigDecimal("20.00"), PaymentMethod.CASH, "REF-A", List.of(), "IDEM-A"));
        em.flush();
        PaymentResult r2 = payment.receivePayment(new NewPayment("SPI", accountId,
                new BigDecimal("20.00"), PaymentMethod.CASH, "REF-B", List.of(), "IDEM-B"));
        em.flush();
        assertThat(r2.receiptId()).isNotEqualTo(r1.receiptId());
    }

    @Test
    @DisplayName("Tanpa key (null) → proses biasa")
    void nullKeyProcessesNormally() {
        PaymentResult r = payment.receivePayment(new NewPayment("SPI", accountId,
                new BigDecimal("30.00"), PaymentMethod.CASH, "REF-N", List.of(), null));
        em.flush();
        assertThat(r.receiptId()).isNotNull();
    }
}
