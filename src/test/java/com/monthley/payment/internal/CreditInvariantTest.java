package com.monthley.payment.internal;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Invariant sisi KREDIT (ADR 0009 P2):
 *   SUM(alokasi ACTIVE dari resit) + amt <= nilai resit
 *
 * Tidak penting selagi setiap resit dialokasi sekali sahaja. Menjadi penting
 * sebaik advance di-knock automatik — satu resit dialokasi merentas banyak
 * invois sepanjang beberapa bulan.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CreditInvariantTest {

    @Autowired InvoiceGenerationService billing;
    @Autowired PaymentPort payment;
    @Autowired ChartOfAccountSeeder seeder;
    @Autowired AllocationGuard guard;
    @PersistenceContext EntityManager em;

    Long accountId;

    @BeforeEach
    void setup() {
        em.createNativeQuery("""
            INSERT IGNORE INTO service_provider (sp_code, name, status, created_at, updated_at, version)
            VALUES ('SPC', 'SP Credit Test', 'ACTIVE', NOW(), NOW(), 0)
            """).executeUpdate();
        seeder.seedFor("SPC");

        em.createNativeQuery("""
            INSERT INTO product (sp_code, code, name, charge_frequency, unit_rate,
                                 main_product, mandatory, prorated, late_penalty, status,
                                 created_at, updated_at, version)
            VALUES ('SPC', 'MF', 'Yuran', 'MONTHLY', 80.00, 0,0,0,0,'ACTIVE', NOW(), NOW(), 0)
            """).executeUpdate();
        Long productId = ((Number) em.createNativeQuery(
                "SELECT id FROM product WHERE sp_code='SPC' AND code='MF'").getSingleResult()).longValue();

        em.createNativeQuery("""
            INSERT INTO account (sp_code, account_no, account_name, charge_frequency,
                                 start_date, status, cached_balance, created_at, updated_at, version)
            VALUES ('SPC', 'CACC', 'Payer', 'MONTHLY', '2026-01-01', 'ACTIVE', 0, NOW(), NOW(), 0)
            """).executeUpdate();
        accountId = ((Number) em.createNativeQuery(
                "SELECT id FROM account WHERE sp_code='SPC' AND account_no='CACC'")
                .getSingleResult()).longValue();

        em.createNativeQuery("""
            INSERT INTO account_subscription (sp_code, account_id, product_id, quantity,
                                              start_date, status, created_at, updated_at, version)
            VALUES ('SPC', :acc, :prod, 1, '2026-01-01', 'ACTIVE', NOW(), NOW(), 0)
            """).setParameter("acc", accountId).setParameter("prod", productId).executeUpdate();

        billing.generateForSp("SPC", YearMonth.of(2026, 2), GenMode.CURRENT, ctx());
        em.flush();
    }

    private BillingContext ctx() {
        return BillingContext.of("SPC", BigDecimal.ZERO,
                GlAccounts.ACCOUNTS_RECEIVABLE, GlAccounts.TAX_PAYABLE, GlAccounts.SERVICE_INCOME);
    }

    /** Bayar dan pulangkan id DOKUMEN resit (bukan id payment). */
    private Long payAndGetReceiptDoc(String amount) {
        payment.receivePayment(new NewPayment("SPC", accountId, new BigDecimal(amount),
                PaymentMethod.CASH, "REF", List.of(), null, null, null));
        em.flush();
        return ((Number) em.createNativeQuery("""
                SELECT id FROM financial_document
                WHERE sp_code='SPC' AND doc_type='RECEIPT' ORDER BY id DESC LIMIT 1
                """).getSingleResult()).longValue();
    }

    // ── Ujian ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Resit lunas penuh → sebarang tambahan DITOLAK")
    void fullyAllocatedReceiptRejectsMore() {
        Long receipt = payAndGetReceiptDoc("50.00");   // invois 80, bayar 50 -> semua 50 dialokasi

        assertThatThrownBy(() -> guard.checkAndLockCredit(receipt, new BigDecimal("0.01")))
                .isInstanceOf(AllocationGuard.OverAllocationException.class);
    }

    @Test
    @DisplayName("Resit dengan advance → boleh alokasi sehingga baki advance")
    void receiptWithAdvanceHasRoom() {
        Long receipt = payAndGetReceiptDoc("100.00");   // invois 80 -> 80 dialokasi, 20 advance

        assertThat(guard.sumActiveFromCredit(receipt)).isEqualByComparingTo("80.00");

        guard.checkAndLockCredit(receipt, new BigDecimal("20.00"));   // tepat baki advance
    }

    @Test
    @DisplayName("MELEBIHI baki advance → OverAllocationException")
    void exceedingAdvanceThrows() {
        Long receipt = payAndGetReceiptDoc("100.00");   // advance 20

        assertThatThrownBy(() -> guard.checkAndLockCredit(receipt, new BigDecimal("20.01")))
                .isInstanceOf(AllocationGuard.OverAllocationException.class)
                .hasMessageContaining("dokumen kredit");
    }
}
