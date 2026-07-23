package com.monthley.payment.internal;

import com.monthley.billing.internal.*;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Invariant peringkat line (ADR 0006 P6):
 *   SUM(alokasi ACTIVE line) + amt <= line.amount + line.tax_amount
 *
 * Race sudah tertutup oleh kunci dokumen — semakan ini menangkap pepijat
 * logik (cth resolver tersalah kira baki terbuka).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LineInvariantTest {

    @Autowired InvoiceGenerationService billing;
    @Autowired ChartOfAccountSeeder seeder;
    @Autowired AllocationGuard guard;
    @PersistenceContext EntityManager em;

    Long accountId;
    Long lineId;

    @BeforeEach
    void setup() {
        em.createNativeQuery("""
            INSERT IGNORE INTO service_provider (sp_code, name, status, created_at, updated_at, version)
            VALUES ('SPV', 'SP Invariant Test', 'ACTIVE', NOW(), NOW(), 0)
            """).executeUpdate();
        seeder.seedFor("SPV");

        em.createNativeQuery("""
            INSERT INTO product (sp_code, code, name, charge_frequency, unit_rate,
                                 main_product, mandatory, prorated, late_penalty, status,
                                 created_at, updated_at, version)
            VALUES ('SPV', 'MF', 'Yuran', 'MONTHLY', 80.00, 0,0,0,0,'ACTIVE', NOW(), NOW(), 0)
            """).executeUpdate();
        Long productId = ((Number) em.createNativeQuery(
                "SELECT id FROM product WHERE sp_code='SPV' AND code='MF'").getSingleResult()).longValue();

        em.createNativeQuery("""
            INSERT INTO account (sp_code, account_no, account_name, charge_frequency,
                                 start_date, status, cached_balance, created_at, updated_at, version)
            VALUES ('SPV', 'VACC', 'Payer', 'MONTHLY', '2026-01-01', 'ACTIVE', 0, NOW(), NOW(), 0)
            """).executeUpdate();
        accountId = ((Number) em.createNativeQuery(
                "SELECT id FROM account WHERE sp_code='SPV' AND account_no='VACC'")
                .getSingleResult()).longValue();

        em.createNativeQuery("""
            INSERT INTO account_subscription (sp_code, account_id, product_id, quantity,
                                              start_date, status, created_at, updated_at, version)
            VALUES ('SPV', :acc, :prod, 1, '2026-01-01', 'ACTIVE', NOW(), NOW(), 0)
            """).setParameter("acc", accountId).setParameter("prod", productId).executeUpdate();

        billing.generateForSp("SPV", YearMonth.of(2026, 6), GenMode.CURRENT, ctx());
        em.flush();

        lineId = ((Number) em.createNativeQuery("""
                SELECT l.id FROM financial_document_line l
                JOIN financial_document d ON l.document_id = d.id
                WHERE d.sp_code='SPV' ORDER BY l.id DESC LIMIT 1
                """).getSingleResult()).longValue();
    }

    private BillingContext ctx() {
        return BillingContext.of("SPV", BigDecimal.ZERO,
                GlAccounts.ACCOUNTS_RECEIVABLE, GlAccounts.TAX_PAYABLE, GlAccounts.SERVICE_INCOME);
    }

    private void allocate(String amount) {
        em.createNativeQuery("""
            INSERT INTO fi_allocation (sp_code, account_id, debit_document_id,
                                       credit_document_id, debit_document_line_id,
                                       amount, status, created_at, updated_at, version)
            SELECT 'SPV', :acc, l.document_id, l.document_id, l.id, :amt, 'ACTIVE', NOW(), NOW(), 0
            FROM financial_document_line l WHERE l.id = :line
            """).setParameter("acc", accountId).setParameter("line", lineId)
                .setParameter("amt", new BigDecimal(amount)).executeUpdate();
        em.flush();
    }

    // ── Ujian ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Dalam had → lulus")
    void withinCapPasses() {
        guard.checkLine(lineId, new BigDecimal("80.00"));   // tepat penuh
    }

    @Test
    @DisplayName("MELEBIHI had → OverAllocationException")
    void exceedingCapThrows() {
        assertThatThrownBy(() -> guard.checkLine(lineId, new BigDecimal("80.01")))
                .isInstanceOf(AllocationGuard.OverAllocationException.class);
    }

    @Test
    @DisplayName("Alokasi sedia ada mengurangkan ruang — baki 30 sahaja")
    void existingAllocationReducesCapacity() {
        allocate("50.00");

        guard.checkLine(lineId, new BigDecimal("30.00"));   // tepat baki

        assertThatThrownBy(() -> guard.checkLine(lineId, new BigDecimal("30.01")))
                .isInstanceOf(AllocationGuard.OverAllocationException.class);
    }

    @Test
    @DisplayName("Line lunas → sebarang tambahan ditolak")
    void settledLineRejectsMore() {
        allocate("80.00");

        assertThatThrownBy(() -> guard.checkLine(lineId, new BigDecimal("0.01")))
                .isInstanceOf(AllocationGuard.OverAllocationException.class);
    }
}
