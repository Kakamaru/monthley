package com.monthley.billing.internal;

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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Split invoice (ADR 0008): split mempengaruhi BILANGAN DOKUMEN sahaja.
 * Baris transaksi sentiasa lengkap — SUM(baris) kekal sama.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SplitInvoiceTest {

    @Autowired InvoiceGenerationService billing;
    @Autowired ChartOfAccountSeeder seeder;
    @PersistenceContext EntityManager em;

    Long accountId;

    @BeforeEach
    void setup() {
        em.createNativeQuery("""
            INSERT IGNORE INTO service_provider (sp_code, name, status, created_at, updated_at, version)
            VALUES ('SPS2', 'SP Split Test', 'ACTIVE', NOW(), NOW(), 0)
            """).executeUpdate();
        seeder.seedFor("SPS2");

        Long mf = product("MF", "80.00");
        Long pk = product("PK", "50.00");

        em.createNativeQuery("""
            INSERT INTO account (sp_code, account_no, account_name, charge_frequency,
                                 start_date, status, cached_balance, created_at, updated_at, version)
            VALUES ('SPS2', 'SACC', 'Payer', 'MONTHLY', '2026-01-01', 'ACTIVE', 0, NOW(), NOW(), 0)
            """).executeUpdate();
        accountId = ((Number) em.createNativeQuery(
                "SELECT id FROM account WHERE sp_code='SPS2' AND account_no='SACC'")
                .getSingleResult()).longValue();

        subscribe(mf);
        subscribe(pk);
    }

    // ── Bantuan ──────────────────────────────────────────────────────

    private Long product(String code, String rate) {
        em.createNativeQuery("""
            INSERT INTO product (sp_code, code, name, charge_frequency, unit_rate,
                                 main_product, mandatory, prorated, late_penalty, status,
                                 created_at, updated_at, version)
            VALUES ('SPS2', :c, :c, 'MONTHLY', :r, 0,0,0,0,'ACTIVE', NOW(), NOW(), 0)
            """).setParameter("c", code).setParameter("r", new BigDecimal(rate)).executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM product WHERE sp_code='SPS2' AND code=:c")
                .setParameter("c", code).getSingleResult()).longValue();
    }

    private void subscribe(Long productId) {
        em.createNativeQuery("""
            INSERT INTO account_subscription (sp_code, account_id, product_id, quantity,
                                              start_date, status, created_at, updated_at, version)
            VALUES ('SPS2', :a, :p, 1, '2026-01-01', 'ACTIVE', NOW(), NOW(), 0)
            """).setParameter("a", accountId).setParameter("p", productId).executeUpdate();
    }

    private BillingContext ctx(boolean split) {
        return new BillingContext("SPS2", BigDecimal.ZERO, null, true, 14, Set.of(),
                GlAccounts.ACCOUNTS_RECEIVABLE, GlAccounts.TAX_PAYABLE,
                GlAccounts.SERVICE_INCOME, split);
    }

    private long docCount() {
        return ((Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM financial_document WHERE sp_code='SPS2' AND doc_type='INVOICE'")
                .getSingleResult()).longValue();
    }

    private long lineCount() {
        return ((Number) em.createNativeQuery("""
                SELECT COUNT(*) FROM financial_document_line l
                JOIN financial_document d ON l.document_id = d.id
                WHERE d.sp_code='SPS2'
                """).getSingleResult()).longValue();
    }

    private BigDecimal lineTotal() {
        Object v = em.createNativeQuery("""
                SELECT COALESCE(SUM(l.amount + l.tax_amount), 0) FROM financial_document_line l
                JOIN financial_document d ON l.document_id = d.id
                WHERE d.sp_code='SPS2'
                """).getSingleResult();
        return new BigDecimal(v.toString());
    }

    // ── Ujian ────────────────────────────────────────────────────────

    @Test
    @DisplayName("split = 0 → SATU dokumen, semua baris di dalamnya")
    void noSplitOneDocument() {
        int posted = billing.generateForSp("SPS2", YearMonth.of(2026, 3),
                GenMode.CURRENT, ctx(false));
        em.flush();

        assertThat(posted).isEqualTo(1);
        assertThat(docCount()).isEqualTo(1);
        assertThat(lineCount()).isEqualTo(2);
        assertThat(lineTotal()).isEqualByComparingTo("130.00");
    }

    @Test
    @DisplayName("split = 1 → DUA dokumen (satu per produk), baris diagih")
    void splitOneDocumentPerProduct() {
        int posted = billing.generateForSp("SPS2", YearMonth.of(2026, 3),
                GenMode.CURRENT, ctx(true));
        em.flush();

        assertThat(posted).isEqualTo(2);
        assertThat(docCount()).isEqualTo(2);
        assertThat(lineCount()).isEqualTo(2);       // baris sama banyak
        assertThat(lineTotal()).isEqualByComparingTo("130.00");   // jumlah SAMA
    }

    @Test
    @DisplayName("split = 1 → setiap dokumen satu baris sahaja")
    void eachDocumentHasOneLine() {
        billing.generateForSp("SPS2", YearMonth.of(2026, 3), GenMode.CURRENT, ctx(true));
        em.flush();

        long maxLines = ((Number) em.createNativeQuery("""
                SELECT COALESCE(MAX(c), 0) FROM (
                  SELECT COUNT(*) c FROM financial_document_line l
                  JOIN financial_document d ON l.document_id = d.id
                  WHERE d.sp_code='SPS2' GROUP BY l.document_id) t
                """).getSingleResult()).longValue();

        assertThat(maxLines).isEqualTo(1);
    }

    @Test
    @DisplayName("split = 1 → idempotent, larian kedua tiada dokumen baharu")
    void splitIsIdempotent() {
        billing.generateForSp("SPS2", YearMonth.of(2026, 3), GenMode.CURRENT, ctx(true));
        em.flush();
        long after1 = docCount();

        int posted2 = billing.generateForSp("SPS2", YearMonth.of(2026, 3),
                GenMode.CURRENT, ctx(true));
        em.flush();

        assertThat(posted2).isZero();
        assertThat(docCount()).isEqualTo(after1);
    }
}
