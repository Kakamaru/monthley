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

/**
 * Bayaran sebenar merekod alokasi peringkat line (ADR 0006 P5).
 *
 * Ujian sedia ada hijau kerana jumlah per dokumen kekal — ujian INI pula
 * membuktikan pemecahan line benar-benar berlaku, dan produk dapat dikesan.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LineAllocationWiringTest {

    @Autowired InvoiceGenerationService billing;
    @Autowired PaymentPort payment;
    @Autowired ChartOfAccountSeeder seeder;
    @PersistenceContext EntityManager em;

    Long accountId;
    Long yuranId;
    Long parkingId;

    @BeforeEach
    void setup() {
        em.createNativeQuery("""
            INSERT IGNORE INTO service_provider (sp_code, name, status, created_at, updated_at, version)
            VALUES ('SPW', 'SP Wiring Test', 'ACTIVE', NOW(), NOW(), 0)
            """).executeUpdate();
        seeder.seedFor("SPW");

        yuranId = product("MF", "Yuran", "80.00");
        parkingId = product("PK", "Parking", "50.00");

        em.createNativeQuery("""
            INSERT INTO account (sp_code, account_no, account_name, charge_frequency,
                                 start_date, status, cached_balance, created_at, updated_at, version)
            VALUES ('SPW', 'WACC', 'Payer', 'MONTHLY', '2026-01-01', 'ACTIVE', 0, NOW(), NOW(), 0)
            """).executeUpdate();
        accountId = ((Number) em.createNativeQuery(
                "SELECT id FROM account WHERE sp_code='SPW' AND account_no='WACC'")
                .getSingleResult()).longValue();

        subscribe(yuranId);
        subscribe(parkingId);

        billing.generateForSp("SPW", YearMonth.of(2026, 5), GenMode.CURRENT, ctx());
        em.flush();
    }

    // ── Bantuan ──────────────────────────────────────────────────────

    private Long product(String code, String name, String rate) {
        em.createNativeQuery("""
            INSERT INTO product (sp_code, code, name, charge_frequency, unit_rate,
                                 main_product, mandatory, prorated, late_penalty, status,
                                 created_at, updated_at, version)
            VALUES ('SPW', :code, :name, 'MONTHLY', :rate, 0,0,0,0,'ACTIVE', NOW(), NOW(), 0)
            """).setParameter("code", code).setParameter("name", name)
                .setParameter("rate", new BigDecimal(rate)).executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM product WHERE sp_code='SPW' AND code=:code")
                .setParameter("code", code).getSingleResult()).longValue();
    }

    private void subscribe(Long productId) {
        em.createNativeQuery("""
            INSERT INTO account_subscription (sp_code, account_id, product_id, quantity,
                                              start_date, status, created_at, updated_at, version)
            VALUES ('SPW', :acc, :prod, 1, '2026-01-01', 'ACTIVE', NOW(), NOW(), 0)
            """).setParameter("acc", accountId).setParameter("prod", productId).executeUpdate();
    }

    private BillingContext ctx() {
        return BillingContext.of("SPW", BigDecimal.ZERO,
                GlAccounts.ACCOUNTS_RECEIVABLE, GlAccounts.TAX_PAYABLE, GlAccounts.SERVICE_INCOME);
    }

    private void pay(String amount) {
        payment.receivePayment(new NewPayment("SPW", accountId, new BigDecimal(amount),
                PaymentMethod.CASH, "REF", List.of(), null));
        em.flush();
    }

    /** Alokasi ikut produk — soalan yang legacy tak dapat jawab. */
    @SuppressWarnings("unchecked")
    private BigDecimal allocatedTo(Long productId) {
        Object v = em.createNativeQuery("""
                SELECT COALESCE(SUM(a.amount), 0)
                FROM fi_allocation a
                JOIN financial_document_line l ON a.debit_document_line_id = l.id
                WHERE a.sp_code = 'SPW' AND a.status = 'ACTIVE' AND l.product_id = :p
                """).setParameter("p", productId).getSingleResult();
        return new BigDecimal(v.toString());
    }

    private BigDecimal totalAllocated() {
        Object v = em.createNativeQuery(
                "SELECT COALESCE(SUM(amount), 0) FROM fi_allocation WHERE sp_code='SPW' AND status='ACTIVE'")
                .getSingleResult();
        return new BigDecimal(v.toString());
    }

    // ── Ujian ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Bayaran penuh → alokasi dipecah kepada line, setiap satu ada line_id")
    void fullPaymentSplitsIntoLines() {
        pay("130.00");

        long rowsWithLine = ((Number) em.createNativeQuery("""
                SELECT COUNT(*) FROM fi_allocation
                WHERE sp_code='SPW' AND status='ACTIVE' AND debit_document_line_id IS NOT NULL
                """).getSingleResult()).longValue();

        assertThat(rowsWithLine).isEqualTo(2);
        assertThat(totalAllocated()).isEqualByComparingTo("130.00");
    }

    @Test
    @DisplayName("Kutipan boleh dikesan ikut produk — soalan yang legacy tak dapat jawab")
    void collectionIsAttributableToProduct() {
        pay("130.00");

        assertThat(allocatedTo(yuranId)).isEqualByComparingTo("80.00");
        assertThat(allocatedTo(parkingId)).isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("Bayaran separa → line tempoh tertua dahulu")
    void partialPaymentFollowsFifo() {
        pay("80.00");

        // Kedua-dua line tempoh sama; seri dipecah ikut line id (Yuran dijana dahulu).
        assertThat(allocatedTo(yuranId)).isEqualByComparingTo("80.00");
        assertThat(allocatedTo(parkingId)).isEqualByComparingTo("0.00");
        assertThat(totalAllocated()).isEqualByComparingTo("80.00");
    }

    @Test
    @DisplayName("INVARIANT: jumlah alokasi == jumlah dibayar (tiada duit hilang/tercipta)")
    void conservationAcrossPayments() {
        pay("50.00");
        pay("30.00");
        pay("20.00");

        assertThat(totalAllocated()).isEqualByComparingTo("100.00");
    }
}
