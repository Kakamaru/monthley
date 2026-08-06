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
 * Guna advance semasa jana bil (ADR 0009 P3).
 *
 * Produk RM80/bulan. Bayar RM200 untuk invois Feb -> 80 dialokasi,
 * 120 jadi advance. Invois Mac dan Apr patut ditampung advance itu
 * secara automatik.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AdvanceApplyTest {

    @Autowired InvoiceGenerationService billing;
    @Autowired PaymentPort payment;
    @Autowired ChartOfAccountSeeder seeder;
    @PersistenceContext EntityManager em;

    Long accountId;

    @BeforeEach
    void setup() {
        em.createNativeQuery("""
            INSERT IGNORE INTO service_provider (sp_code, name, status, created_at, updated_at, version)
            VALUES ('SPA', 'SP Advance Test', 'ACTIVE', NOW(), NOW(), 0)
            """).executeUpdate();
        seeder.seedFor("SPA");

        em.createNativeQuery("""
            INSERT INTO product (sp_code, code, name, charge_frequency, unit_rate,
                                 main_product, mandatory, prorated, late_penalty, status,
                                 created_at, updated_at, version)
            VALUES ('SPA', 'MF', 'Yuran', 'MONTHLY', 80.00, 0,0,0,0,'ACTIVE', NOW(), NOW(), 0)
            """).executeUpdate();
        Long productId = ((Number) em.createNativeQuery(
                "SELECT id FROM product WHERE sp_code='SPA' AND code='MF'").getSingleResult()).longValue();

        em.createNativeQuery("""
            INSERT INTO account (sp_code, account_no, account_name, charge_frequency,
                                 start_date, status, created_at, updated_at, version)
            VALUES ('SPA', 'AACC', 'Payer', 'MONTHLY', '2026-01-01', 'ACTIVE', NOW(), NOW(), 0)
            """).executeUpdate();
        accountId = ((Number) em.createNativeQuery(
                "SELECT id FROM account WHERE sp_code='SPA' AND account_no='AACC'")
                .getSingleResult()).longValue();

        em.createNativeQuery("""
            INSERT INTO account_subscription (sp_code, account_id, product_id, quantity,
                                              start_date, status, created_at, updated_at, version)
            VALUES ('SPA', :acc, :prod, 1, '2026-01-01', 'ACTIVE', NOW(), NOW(), 0)
            """).setParameter("acc", accountId).setParameter("prod", productId).executeUpdate();
    }

    // ── Bantuan ──────────────────────────────────────────────────────

    private BillingContext ctx() {
        return BillingContext.of("SPA", BigDecimal.ZERO,
                GlAccounts.ACCOUNTS_RECEIVABLE, GlAccounts.TAX_PAYABLE, GlAccounts.SERVICE_INCOME);
    }

    private void generate(int month) {
        billing.generateForSp("SPA", YearMonth.of(2026, month), GenMode.CURRENT, ctx());
        em.flush();
    }

    private void pay(String amount) {
        payment.receivePayment(new NewPayment("SPA", accountId, new BigDecimal(amount),
                PaymentMethod.CASH, "REF", List.of(), null, null, null));
        em.flush();
    }

    private BigDecimal balance() {
        Object v = em.createNativeQuery(
                "SELECT COALESCE((SELECT balance FROM account_balance WHERE account_id = :a), 0)")
                .setParameter("a", accountId).getSingleResult();
        return new BigDecimal(v.toString());
    }

    /**
     * Baki terbuka invois TERAKHIR dicipta — sifar bermakna sudah ditampung
     * sepenuhnya. Guna id, bukan carian nama period: ujian tidak patut
     * bergantung pada format teks.
     */
    private BigDecimal openOnLatest() {
        Object v = em.createNativeQuery("""
                SELECT (d.amount + d.tax_amount) - COALESCE((
                         SELECT SUM(al.amount) FROM fi_allocation al
                         WHERE al.debit_document_id = d.id AND al.status='ACTIVE'), 0)
                FROM financial_document d
                WHERE d.sp_code='SPA' AND d.doc_type='INVOICE'
                ORDER BY d.id DESC LIMIT 1
                """).getSingleResult();
        return new BigDecimal(v.toString());
    }

    private BigDecimal depositBalance() {
        Object v = em.createNativeQuery("""
                SELECT COALESCE(SUM(l.credit_amount) - SUM(l.debit_amount), 0)
                FROM journal_line l
                JOIN journal_entry e ON e.id = l.journal_entry_id
                JOIN chart_of_accounts c ON c.id = l.gl_account_id
                WHERE e.sp_code='SPA' AND c.code = :gl
                """).setParameter("gl", GlAccounts.CUSTOMER_DEPOSIT).getSingleResult();
        return new BigDecimal(v.toString());
    }

    // ── Ujian ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Advance menampung invois bulan berikutnya secara automatik")
    void advanceCoversNextInvoice() {
        generate(2);
        pay("200.00");                       // 80 ke invois Feb, 120 advance

        assertThat(balance()).isEqualByComparingTo("-120.00");

        generate(3);                          // invois Mac 80 — patut ditampung

        assertThat(openOnLatest()).isEqualByComparingTo("0.00");
        assertThat(balance()).isEqualByComparingTo("-40.00");
    }

    @Test
    @DisplayName("Advance habis separuh jalan — invois seterusnya ditampung sebahagian")
    void advanceRunsOut() {
        generate(2);
        pay("200.00");                       // advance 120
        generate(3);                          // guna 80, tinggal 40
        generate(4);                          // invois 80, advance tinggal 40

        assertThat(openOnLatest()).isEqualByComparingTo("40.00");   // separuh terbuka
        assertThat(balance()).isEqualByComparingTo("40.00");   // kini berhutang
    }

    @Test
    @DisplayName("Tiada advance → invois kekal terbuka penuh")
    void noAdvanceLeavesInvoiceOpen() {
        generate(2);
        generate(3);

        assertThat(openOnLatest()).isEqualByComparingTo("80.00");
        assertThat(balance()).isEqualByComparingTo("160.00");
    }

    @Test
    @DisplayName("LEDGER: liabiliti deposit diterbalikkan bila advance digunakan")
    void depositLiabilityReversed() {
        generate(2);
        pay("200.00");
        assertThat(depositBalance()).isEqualByComparingTo("120.00");

        generate(3);                          // guna 80

        assertThat(depositBalance()).isEqualByComparingTo("40.00");
    }
}
