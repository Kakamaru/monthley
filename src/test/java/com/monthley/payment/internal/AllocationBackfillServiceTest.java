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

/**
 * Backfill alokasi peringkat dokumen -> peringkat line (ADR 0006 P4).
 *
 * Jaminan utama: SUM per dokumen KEKAL. Bilangan baris boleh bertambah.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AllocationBackfillServiceTest {

    @Autowired InvoiceGenerationService billing;
    @Autowired ChartOfAccountSeeder seeder;
    @Autowired AllocationBackfillService backfill;
    @PersistenceContext EntityManager em;

    Long accountId;
    Long invoiceId;

    @BeforeEach
    void setup() {
        em.createNativeQuery("""
            INSERT IGNORE INTO service_provider (sp_code, name, status, created_at, updated_at, version)
            VALUES ('SPB', 'SP Backfill Test', 'ACTIVE', NOW(), NOW(), 0)
            """).executeUpdate();
        seeder.seedFor("SPB");

        Long yuran = product("MF", "80.00");
        Long parking = product("PK", "50.00");

        em.createNativeQuery("""
            INSERT INTO account (sp_code, account_no, account_name, charge_frequency,
                                 start_date, status, cached_balance, created_at, updated_at, version)
            VALUES ('SPB', 'BACC', 'Payer', 'MONTHLY', '2026-01-01', 'ACTIVE', 0, NOW(), NOW(), 0)
            """).executeUpdate();
        accountId = ((Number) em.createNativeQuery(
                "SELECT id FROM account WHERE sp_code='SPB' AND account_no='BACC'")
                .getSingleResult()).longValue();

        subscribe(yuran);
        subscribe(parking);

        billing.generateForSp("SPB", YearMonth.of(2026, 4), GenMode.CURRENT, ctx());
        em.flush();

        invoiceId = ((Number) em.createNativeQuery("""
                SELECT id FROM financial_document
                WHERE sp_code='SPB' AND doc_type='INVOICE' ORDER BY id DESC LIMIT 1
                """).getSingleResult()).longValue();
    }

    // ── Bantuan ──────────────────────────────────────────────────────

    private Long product(String code, String rate) {
        em.createNativeQuery("""
            INSERT INTO product (sp_code, code, name, charge_frequency, unit_rate,
                                 main_product, mandatory, prorated, late_penalty, status,
                                 created_at, updated_at, version)
            VALUES ('SPB', :code, :code, 'MONTHLY', :rate, 0,0,0,0,'ACTIVE', NOW(), NOW(), 0)
            """).setParameter("code", code).setParameter("rate", new BigDecimal(rate)).executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM product WHERE sp_code='SPB' AND code=:code")
                .setParameter("code", code).getSingleResult()).longValue();
    }

    private void subscribe(Long productId) {
        em.createNativeQuery("""
            INSERT INTO account_subscription (sp_code, account_id, product_id, quantity,
                                              start_date, status, created_at, updated_at, version)
            VALUES ('SPB', :acc, :prod, 1, '2026-01-01', 'ACTIVE', NOW(), NOW(), 0)
            """).setParameter("acc", accountId).setParameter("prod", productId).executeUpdate();
    }

    private BillingContext ctx() {
        return BillingContext.of("SPB", BigDecimal.ZERO,
                GlAccounts.ACCOUNTS_RECEIVABLE, GlAccounts.TAX_PAYABLE, GlAccounts.SERVICE_INCOME);
    }

    /** Alokasi lama gaya lama: peringkat dokumen, line_id NULL. */
    private void legacyAllocation(String amount) {
        em.createNativeQuery("""
            INSERT INTO fi_allocation (sp_code, account_id, debit_document_id,
                                       credit_document_id, debit_document_line_id,
                                       amount, status, created_at, updated_at, version)
            VALUES ('SPB', :acc, :doc, :doc, NULL, :amt, 'ACTIVE', NOW(), NOW(), 0)
            """).setParameter("acc", accountId).setParameter("doc", invoiceId)
                .setParameter("amt", new BigDecimal(amount)).executeUpdate();
        em.flush();
    }

    private BigDecimal sumForInvoice() {
        Object v = em.createNativeQuery("""
                SELECT COALESCE(SUM(amount), 0) FROM fi_allocation
                WHERE debit_document_id = :d AND status = 'ACTIVE'
                """).setParameter("d", invoiceId).getSingleResult();
        return new BigDecimal(v.toString());
    }

    private long rowsWithLine() {
        return ((Number) em.createNativeQuery("""
                SELECT COUNT(*) FROM fi_allocation
                WHERE debit_document_id = :d AND debit_document_line_id IS NOT NULL
                """).setParameter("d", invoiceId).getSingleResult()).longValue();
    }

    // ── Ujian ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Alokasi penuh (130) → pecah 2 line, jumlah kekal")
    void splitsFullAllocation() {
        legacyAllocation("130.00");
        BigDecimal before = sumForInvoice();

        var report = backfill.backfill("SPB");
        em.flush();

        assertThat(report.allocationsProcessed()).isEqualTo(1);
        assertThat(sumForInvoice()).isEqualByComparingTo(before);   // JUMLAH KEKAL
        assertThat(rowsWithLine()).isEqualTo(2);                    // 80 + 50
    }

    @Test
    @DisplayName("Alokasi separa (100) → line pertama lunas, kedua separa")
    void splitsPartialAllocation() {
        legacyAllocation("100.00");

        backfill.backfill("SPB");
        em.flush();

        assertThat(sumForInvoice()).isEqualByComparingTo("100.00");
        assertThat(rowsWithLine()).isEqualTo(2);   // 80 penuh + 20 separa
    }

    @Test
    @DisplayName("Bayaran berbilang (kes doc 373) → semua dipecah, jumlah kekal")
    void handlesMultiplePayments() {
        legacyAllocation("50.00");
        legacyAllocation("50.00");
        legacyAllocation("30.00");
        BigDecimal before = sumForInvoice();

        var report = backfill.backfill("SPB");
        em.flush();

        assertThat(report.allocationsProcessed()).isEqualTo(3);
        assertThat(sumForInvoice()).isEqualByComparingTo(before);   // 130 kekal
    }

    @Test
    @DisplayName("IDEMPOTEN: larian kedua tiada kesan")
    void isIdempotent() {
        legacyAllocation("130.00");
        backfill.backfill("SPB");
        em.flush();

        BigDecimal afterFirst = sumForInvoice();
        long rowsAfterFirst = rowsWithLine();

        var second = backfill.backfill("SPB");
        em.flush();

        assertThat(second.allocationsProcessed()).isZero();
        assertThat(sumForInvoice()).isEqualByComparingTo(afterFirst);
        assertThat(rowsWithLine()).isEqualTo(rowsAfterFirst);
    }

    @Test
    @DisplayName("INVARIANT: jumlah keseluruhan sebelum == selepas")
    void preservesGlobalSum() {
        legacyAllocation("130.00");

        var report = backfill.backfill("SPB");

        assertThat(report.sumBefore()).isEqualByComparingTo(report.sumAfter());
    }
}
