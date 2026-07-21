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
 * AllocationGuard — jantung anti-drift. Sahkan invariant
 * SUM(alokasi aktif) + amt <= document.amount dikuatkuasa masa write.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AllocationGuardTest {

    @Autowired AllocationGuard guard;
    @PersistenceContext EntityManager em;

    Long invId;
    Long accId;

    @BeforeEach
    void setup() {
        em.createNativeQuery("""
            INSERT IGNORE INTO service_provider (sp_code, name, status, created_at, updated_at, version)
            VALUES ('SPG', 'Guard Test', 'ACTIVE', NOW(), NOW(), 0)
            """).executeUpdate();

        em.createNativeQuery("""
            INSERT INTO account (sp_code, account_no, account_name, charge_frequency,
                                 start_date, status, cached_balance, created_at, updated_at, version)
            VALUES ('SPG', 'GACC', 'Guard Acc', 'MONTHLY', '2026-01-01', 'ACTIVE', 0, NOW(), NOW(), 0)
            """).executeUpdate();
        accId = ((Number) em.createNativeQuery(
                "SELECT id FROM account WHERE sp_code='SPG' AND account_no='GACC'")
                .getSingleResult()).longValue();

        // Invois RM80 (amount 80, tax 0).
        em.createNativeQuery("""
            INSERT INTO financial_document (sp_code, doc_no, doc_type, doc_date,
                                            currency, amount, tax_amount, status,
                                            created_at, updated_at, version)
            VALUES ('SPG', 'INV-G1', 'INVOICE', '2026-01-01', 'MYR', 80.00, 0.00, 'ACTIVE', NOW(), NOW(), 0)
            """).executeUpdate();
        invId = ((Number) em.createNativeQuery(
                "SELECT id FROM financial_document WHERE sp_code='SPG' AND doc_no='INV-G1'")
                .getSingleResult()).longValue();
    }

    @Test
    @DisplayName("checkAndLock lulus bila dalam had; sumActive kira betul")
    void allowsWithinCap() {
        // Kosong dulu.
        assertThat(guard.sumActive(invId)).isEqualByComparingTo("0.00");
        // RM80 tepat cap — lulus.
        guard.checkAndLock(invId, new BigDecimal("80.00"));
    }

    @Test
    @DisplayName("checkAndLock TOLAK bila melebihi had (over-allocation)")
    void rejectsOverCap() {
        // Sisip alokasi RM80 (guna resit dummy sebagai credit doc).
        em.createNativeQuery("""
            INSERT INTO financial_document (sp_code, doc_no, doc_type, doc_date,
                                            currency, amount, tax_amount, status,
                                            created_at, updated_at, version)
            VALUES ('SPG', 'RCP-G1', 'RECEIPT', '2026-01-02', 'MYR', 80.00, 0.00, 'ACTIVE', NOW(), NOW(), 0)
            """).executeUpdate();
        Long rcpId = ((Number) em.createNativeQuery(
                "SELECT id FROM financial_document WHERE sp_code='SPG' AND doc_no='RCP-G1'")
                .getSingleResult()).longValue();
        em.createNativeQuery("""
            INSERT INTO fi_allocation (sp_code, account_id, debit_document_id, credit_document_id,
                                       amount, status, created_at, updated_at, version)
            VALUES ('SPG', :acc, :inv, :rcp, 80.00, 'ACTIVE', NOW(), NOW(), 0)
            """).setParameter("acc", accId).setParameter("inv", invId).setParameter("rcp", rcpId).executeUpdate();
        em.flush();

        // Sudah 80 = cap penuh. Cuba tambah RM1 -> over.
        assertThat(guard.sumActive(invId)).isEqualByComparingTo("80.00");
        assertThatThrownBy(() -> guard.checkAndLock(invId, new BigDecimal("1.00")))
                .isInstanceOf(AllocationGuard.OverAllocationException.class);
    }

    @Test
    @DisplayName("checkAndLock lempar bila dokumen tak wujud")
    void rejectsMissingDoc() {
        assertThatThrownBy(() -> guard.checkAndLock(999999999L, new BigDecimal("1.00")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
