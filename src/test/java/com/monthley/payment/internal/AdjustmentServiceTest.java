package com.monthley.payment.internal;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AdjustmentService — Credit Note (Reduction) & Debit Note (Additional).
 * Sahkan: baki kesan betul, doc type betul, idempotency, guard tolak over.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AdjustmentServiceTest {

    @Autowired AdjustmentService service;
    @Autowired AllocationGuard guard;
    @Autowired com.monthley.ledger.internal.ChartOfAccountSeeder seeder;
    @PersistenceContext EntityManager em;

    Long accId;
    Long invId;

    @BeforeEach
    void setup() {
        em.createNativeQuery("""
            INSERT IGNORE INTO service_provider (sp_code, name, status, created_at, updated_at, version)
            VALUES ('SPA', 'Adj Test', 'ACTIVE', NOW(), NOW(), 0)
            """).executeUpdate();
        seeder.seedFor("SPA");   // chart of accounts (GL 1100/4000) untuk posting.

        em.createNativeQuery("""
            INSERT INTO account (sp_code, account_no, account_name, charge_frequency,
                                 start_date, status, cached_balance, created_at, updated_at, version)
            VALUES ('SPA', 'AACC', 'Adj Acc', 'MONTHLY', '2026-01-01', 'ACTIVE', 0, NOW(), NOW(), 0)
            """).executeUpdate();
        accId = ((Number) em.createNativeQuery(
                "SELECT id FROM account WHERE sp_code='SPA' AND account_no='AACC'").getSingleResult()).longValue();

        // Invois RM100.
        em.createNativeQuery("""
            INSERT INTO financial_document (sp_code, doc_no, doc_type, doc_date,
                                            currency, amount, tax_amount, status,
                                            created_at, updated_at, version)
            VALUES ('SPA', 'INV-A1', 'INVOICE', '2026-01-01', 'MYR', 100.00, 0.00, 'ACTIVE', NOW(), NOW(), 0)
            """).executeUpdate();
        invId = ((Number) em.createNativeQuery(
                "SELECT id FROM financial_document WHERE sp_code='SPA' AND doc_no='INV-A1'").getSingleResult()).longValue();
    }

    private BigDecimal invoiceBalance() {
        // baki invois = amount - SUM(alokasi aktif).
        return new BigDecimal("100.00").subtract(guard.sumActive(invId));
    }

    @Test
    @DisplayName("Reduction — CREDIT_NOTE + alokasi, baki invois turun")
    void reductionLowersBalance() {
        assertThat(invoiceBalance()).isEqualByComparingTo("100.00");

        var r = service.adjust(new AdjustmentService.NewAdjustment(
                "SPA", accId, AdjustmentService.Kind.REDUCTION,
                new BigDecimal("30.00"), invId, "Diskaun", "ADJ-R1"));
        em.flush();

        assertThat(r.docType()).isEqualTo("CREDIT_NOTE");
        assertThat(invoiceBalance()).isEqualByComparingTo("70.00");   // 100 - 30
    }

    @Test
    @DisplayName("Additional — DEBIT_NOTE dicipta (masuk baki akaun)")
    void additionalCreatesDebitNote() {
        var r = service.adjust(new AdjustmentService.NewAdjustment(
                "SPA", accId, AdjustmentService.Kind.ADDITIONAL,
                new BigDecimal("25.00"), null, "Caj tambahan", "ADJ-A1"));
        em.flush();

        assertThat(r.docType()).isEqualTo("DEBIT_NOTE");
        // Doc DEBIT_NOTE wujud dgn amount 25.
        Object[] doc = (Object[]) em.createNativeQuery(
                "SELECT doc_type, amount FROM financial_document WHERE id = :id")
                .setParameter("id", r.documentId()).getSingleResult();
        assertThat(doc[0]).isEqualTo("DEBIT_NOTE");
        assertThat((BigDecimal) doc[1]).isEqualByComparingTo("25.00");
    }

    @Test
    @DisplayName("Idempotency — sama sourceRef dua kali = satu doc")
    void idempotentBySourceRef() {
        var r1 = service.adjust(new AdjustmentService.NewAdjustment(
                "SPA", accId, AdjustmentService.Kind.ADDITIONAL,
                new BigDecimal("10.00"), null, "x", "ADJ-DUP"));
        em.flush();
        var r2 = service.adjust(new AdjustmentService.NewAdjustment(
                "SPA", accId, AdjustmentService.Kind.ADDITIONAL,
                new BigDecimal("10.00"), null, "x", "ADJ-DUP"));
        em.flush();
        assertThat(r2.documentId()).isEqualTo(r1.documentId());   // doc sama
    }

    @Test
    @DisplayName("Reduction melebihi baki invois — guard tolak")
    void reductionOverBalanceRejected() {
        assertThatThrownBy(() -> service.adjust(new AdjustmentService.NewAdjustment(
                "SPA", accId, AdjustmentService.Kind.REDUCTION,
                new BigDecimal("150.00"), invId, "over", "ADJ-OVER")))
                .isInstanceOf(AllocationGuard.OverAllocationException.class);
    }
}
