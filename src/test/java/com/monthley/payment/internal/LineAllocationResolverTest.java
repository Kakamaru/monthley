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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Resolver line — baki terbuka per line dibaca dari DB (ADR 0006).
 * Invois ujian: Yuran RM80 + Parking RM50 (mencerminkan contoh dalam ADR).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LineAllocationResolverTest {

    @Autowired InvoiceGenerationService billing;
    @Autowired ChartOfAccountSeeder seeder;
    @Autowired LineAllocationResolver resolver;
    @PersistenceContext EntityManager em;

    Long accountId;
    Long invoiceId;

    @BeforeEach
    void setup() {
        em.createNativeQuery("""
            INSERT IGNORE INTO service_provider (sp_code, name, status, created_at, updated_at, version)
            VALUES ('SPL', 'SP Line Test', 'ACTIVE', NOW(), NOW(), 0)
            """).executeUpdate();
        seeder.seedFor("SPL");

        Long yuran = product("MF", "Yuran Penyelenggaraan", "80.00");
        Long parking = product("PK", "Parking", "50.00");

        em.createNativeQuery("""
            INSERT INTO account (sp_code, account_no, account_name, charge_frequency,
                                 start_date, status, cached_balance, created_at, updated_at, version)
            VALUES ('SPL', 'LACC', 'Payer', 'MONTHLY', '2026-01-01', 'ACTIVE', 0, NOW(), NOW(), 0)
            """).executeUpdate();
        accountId = ((Number) em.createNativeQuery(
                "SELECT id FROM account WHERE sp_code='SPL' AND account_no='LACC'")
                .getSingleResult()).longValue();

        subscribe(yuran);
        subscribe(parking);

        billing.generateForSp("SPL", YearMonth.of(2026, 3), GenMode.CURRENT, ctx());
        em.flush();

        invoiceId = ((Number) em.createNativeQuery("""
                SELECT id FROM financial_document
                WHERE sp_code='SPL' AND doc_type='INVOICE' ORDER BY id DESC LIMIT 1
                """).getSingleResult()).longValue();
    }

    // ── Bantuan ──────────────────────────────────────────────────────

    private Long product(String code, String name, String rate) {
        em.createNativeQuery("""
            INSERT INTO product (sp_code, code, name, charge_frequency, unit_rate,
                                 main_product, mandatory, prorated, late_penalty, status,
                                 created_at, updated_at, version)
            VALUES ('SPL', :code, :name, 'MONTHLY', :rate, 0,0,0,0,'ACTIVE', NOW(), NOW(), 0)
            """).setParameter("code", code).setParameter("name", name)
                .setParameter("rate", new BigDecimal(rate)).executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM product WHERE sp_code='SPL' AND code=:code")
                .setParameter("code", code).getSingleResult()).longValue();
    }

    private void subscribe(Long productId) {
        em.createNativeQuery("""
            INSERT INTO account_subscription (sp_code, account_id, product_id, quantity,
                                              start_date, status, created_at, updated_at, version)
            VALUES ('SPL', :acc, :prod, 1, '2026-01-01', 'ACTIVE', NOW(), NOW(), 0)
            """).setParameter("acc", accountId).setParameter("prod", productId).executeUpdate();
    }

    private BillingContext ctx() {
        return BillingContext.of("SPL", BigDecimal.ZERO,
                GlAccounts.ACCOUNTS_RECEIVABLE, GlAccounts.TAX_PAYABLE, GlAccounts.SERVICE_INCOME);
    }

    private BigDecimal totalOf(LineFifoAllocator.Result r) {
        return r.allocations().stream()
                .map(LineFifoAllocator.LineAllocation::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ── Ujian ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Invois dua produk → dua line terbuka, jumlah = nilai invois")
    void readsOpenLines() {
        List<LineFifoAllocator.OpenLine> lines = resolver.openLines(invoiceId);

        assertThat(lines).hasSize(2);
        BigDecimal jumlah = lines.stream()
                .map(LineFifoAllocator.OpenLine::outstanding)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(jumlah).isEqualByComparingTo("130.00");   // 80 + 50
    }

    @Test
    @DisplayName("Bayaran penuh → pecah kepada dua line, tiada baki")
    void resolveFullPayment() {
        var r = resolver.resolve(invoiceId, new BigDecimal("130.00"));

        assertThat(r.allocations()).hasSize(2);
        assertThat(totalOf(r)).isEqualByComparingTo("130.00");
        assertThat(r.unallocated()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("Bayaran separa → line pertama lunas, line kedua separa")
    void resolvePartialPayment() {
        var r = resolver.resolve(invoiceId, new BigDecimal("100.00"));

        assertThat(totalOf(r)).isEqualByComparingTo("100.00");
        assertThat(r.unallocated()).isEqualByComparingTo("0.00");
        assertThat(r.allocations()).hasSize(2);
    }

    @Test
    @DisplayName("Alokasi sedia ada pada line → baki terbuka berkurang")
    void existingAllocationReducesOpen() {
        Long lineId = ((Number) em.createNativeQuery(
                "SELECT id FROM financial_document_line WHERE document_id = :d ORDER BY id LIMIT 1")
                .setParameter("d", invoiceId).getSingleResult()).longValue();

        // Rekod alokasi 30.00 terus pada line tersebut.
        em.createNativeQuery("""
            INSERT INTO fi_allocation (sp_code, account_id, debit_document_id,
                                       credit_document_id, debit_document_line_id,
                                       amount, status, created_at, updated_at, version)
            VALUES ('SPL', :acc, :doc, :doc, :line, 30.00, 'ACTIVE', NOW(), NOW(), 0)
            """).setParameter("acc", accountId).setParameter("doc", invoiceId)
                .setParameter("line", lineId).executeUpdate();
        em.flush();

        BigDecimal jumlah = resolver.openLines(invoiceId).stream()
                .map(LineFifoAllocator.OpenLine::outstanding)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(jumlah).isEqualByComparingTo("100.00");   // 130 - 30
    }

    @Test
    @DisplayName("Dokumen tanpa line → semua jadi unallocated")
    void documentWithoutLines() {
        var r = resolver.resolve(999999L, new BigDecimal("50.00"));

        assertThat(r.allocations()).isEmpty();
        assertThat(r.unallocated()).isEqualByComparingTo("50.00");
    }
}
