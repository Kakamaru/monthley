package com.monthley.billing;

import com.monthley.billing.internal.BillingContext;
import com.monthley.billing.internal.InvoiceGenerationService;
import com.monthley.billing.internal.PeriodResolver;
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
 * Ujian aliran PENUH: SP + akaun + produk + langganan → jana invois → journal di ledger.
 * Membuktikan billing engine menyatukan account + catalog + ledger.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class InvoiceGenerationServiceTest {

    @Autowired InvoiceGenerationService billing;
    @Autowired com.monthley.ledger.api.LedgerPort ledger;
    @Autowired ChartOfAccountSeeder seeder;
    @PersistenceContext EntityManager em;

    Long accountId;
    Long productId;

    @BeforeEach
    void setup() {
        em.createNativeQuery("""
            INSERT IGNORE INTO service_provider (sp_code, name, status, created_at, updated_at, version)
            VALUES ('SPB', 'SP Billing Test', 'ACTIVE', NOW(), NOW(), 0)
            """).executeUpdate();
        seeder.seedFor("SPB");

        // Produk: Maintenance Fee RM80 bulanan
        em.createNativeQuery("""
            INSERT INTO product (sp_code, code, name, charge_frequency, unit_rate,
                                 main_product, mandatory, prorated, late_penalty, status,
                                 created_at, updated_at, version)
            VALUES ('SPB', 'MF', 'Maintenance Fee', 'MONTHLY', 80.00,
                    0, 0, 0, 0, 'ACTIVE', NOW(), NOW(), 0)
            """).executeUpdate();
        productId = ((Number) em.createNativeQuery(
                "SELECT id FROM product WHERE sp_code='SPB' AND code='MF'")
                .getSingleResult()).longValue();

        // Akaun bulanan, mula Jan 2026
        em.createNativeQuery("""
            INSERT INTO account (sp_code, account_no, account_name, charge_frequency,
                                 start_date, status, cached_balance, created_at, updated_at, version)
            VALUES ('SPB', 'ACC001', 'Ahmad', 'MONTHLY', '2026-01-01', 'ACTIVE', 0, NOW(), NOW(), 0)
            """).executeUpdate();
        accountId = ((Number) em.createNativeQuery(
                "SELECT id FROM account WHERE sp_code='SPB' AND account_no='ACC001'")
                .getSingleResult()).longValue();

        // Langganan: akaun langgan Maintenance Fee, qty 1, mula Jan 2026
        em.createNativeQuery("""
            INSERT INTO account_subscription (sp_code, account_id, product_id, quantity,
                                              start_date, status, created_at, updated_at, version)
            VALUES ('SPB', :acc, :prod, 1, '2026-01-01', 'ACTIVE', NOW(), NOW(), 0)
            """)
            .setParameter("acc", accountId)
            .setParameter("prod", productId)
            .executeUpdate();
    }

    /** Tetapkan account.start_date (= Start Charging). null = kosongkan. */
    private void startCharging(String isoDate) {
        em.createNativeQuery("UPDATE account SET start_date = :d WHERE id = :acc")
                .setParameter("d", isoDate)
                .setParameter("acc", accountId).executeUpdate();
    }

    private void subscriptionStart(String isoDate) {
        em.createNativeQuery(
                "UPDATE account_subscription SET start_date = :d WHERE account_id = :acc")
                .setParameter("d", isoDate)
                .setParameter("acc", accountId).executeUpdate();
    }

    private void prorated(boolean on) {
        em.createNativeQuery("UPDATE product SET prorated = :p WHERE id = :prod")
                .setParameter("p", on ? 1 : 0)
                .setParameter("prod", productId).executeUpdate();
    }

    /** Amaun baris tunggal yang dijana untuk SPB. */
    private java.math.BigDecimal singleLineAmount() {
        return (java.math.BigDecimal) em.createNativeQuery("""
                SELECT l.amount FROM financial_document_line l
                JOIN financial_document d ON d.id = l.document_id
                WHERE d.sp_code = 'SPB'
                """).getSingleResult();
    }

    private BillingContext ctx() {
        return BillingContext.of("SPB", BigDecimal.ZERO,
                GlAccounts.ACCOUNTS_RECEIVABLE, GlAccounts.TAX_PAYABLE, GlAccounts.SERVICE_INCOME);
    }

    @Test
    @DisplayName("Jana invois bulanan → 1 journal di-post")
    void generatesMonthlyInvoice() {
        int posted = billing.generateForSp("SPB", YearMonth.of(2026, 7),
                GenMode.CURRENT, ctx());

        assertThat(posted).isEqualTo(1);

        // Sahkan journal wujud di ledger untuk SPB
        Long journalCount = ((Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM journal_entry WHERE sp_code='SPB'")
                .getSingleResult()).longValue();
        assertThat(journalCount).isEqualTo(1L);

        // Sahkan journal seimbang: SUM(debit) = SUM(credit) = 80
        BigDecimal debit = (BigDecimal) em.createNativeQuery("""
            SELECT COALESCE(SUM(jl.debit_amount),0) FROM journal_line jl
            JOIN journal_entry je ON je.id = jl.journal_entry_id
            WHERE je.sp_code='SPB'
            """).getSingleResult();
        BigDecimal credit = (BigDecimal) em.createNativeQuery("""
            SELECT COALESCE(SUM(jl.credit_amount),0) FROM journal_line jl
            JOIN journal_entry je ON je.id = jl.journal_entry_id
            WHERE je.sp_code='SPB'
            """).getSingleResult();

        assertThat(debit).isEqualByComparingTo("80.00");
        assertThat(credit).isEqualByComparingTo("80.00");
    }

    @Test
    @DisplayName("Akaun tahunan → SATU invois, 12 baris")
    void yearlyAccountGivesOneInvoiceTwelveLines() {
        // Reka bentuk lama: 12 invois (satu per period).
        // Disahkan lawan production: satu dokumen, doc.period_id = 2026000000,
        // dengan baris membawa period LIPUTAN sendiri (2026110100..2026241200).
        // Rujuk docs/domain/billing-rules.md §3
        em.createNativeQuery(
            "UPDATE account SET charge_frequency='YEAR' WHERE id=:acc")
            .setParameter("acc", accountId).executeUpdate();

        int posted = billing.generateForSp("SPB", YearMonth.of(2026, 1),
                GenMode.CURRENT, ctx());

        assertThat(posted).isEqualTo(1);

        Number lines = (Number) em.createNativeQuery("""
            SELECT COUNT(*) FROM financial_document_line l
            JOIN financial_document d ON d.id = l.document_id
            WHERE d.sp_code='SPB'
            """).getSingleResult();
        assertThat(lines.intValue()).isEqualTo(12);

        Number docPeriod = (Number) em.createNativeQuery(
            "SELECT period_id FROM financial_document WHERE sp_code='SPB'")
            .getSingleResult();
        assertThat(docPeriod.longValue()).isEqualTo(2026000000L);
    }

    // ── Suis proration: account.start_date (Start Charging) ──────────
    //
    // Peraturan (docs/domain/billing-rules.md §6):
    //   effStart   = MAX(account.start_date, sub.start_date)   -> BILA
    //   canProrate = account.start_date != null && product.prorated  -> BERAPA
    //
    // Rasional: tanpa Start Charging, satu-satunya tarikh yang kita ada ialah
    // bila kerani menaip. Memprorate berdasarkannya bermakna mengenakan caj
    // berdasarkan kelajuan kemasukan data.

    @Test
    @DisplayName("Start Charging kosong + masuk 15 Jun -> caj PENUH")
    void noStartChargingChargesFullCycle() {
        startCharging(null);
        subscriptionStart("2026-06-15");
        prorated(true);                 // walaupun produk prorated

        int posted = billing.generateForSp("SPB", YearMonth.of(2026, 6),
                GenMode.CURRENT, ctx());

        assertThat(posted).isEqualTo(1);
        assertThat(singleLineAmount()).isEqualByComparingTo("80.00");
    }

    @Test
    @DisplayName("Start Charging 15 Jun + produk prorated -> prorate 16/30")
    void startChargingWithProratedProductProrates() {
        startCharging("2026-06-15");
        subscriptionStart("2026-06-15");
        prorated(true);

        int posted = billing.generateForSp("SPB", YearMonth.of(2026, 6),
                GenMode.CURRENT, ctx());

        assertThat(posted).isEqualTo(1);
        // 80 x 16/30 = 42.666... -> 42.67   (bukan /30 tetap: Jun MEMANG 30 hari)
        assertThat(singleLineAmount()).isEqualByComparingTo("42.67");
    }

    @Test
    @DisplayName("Start Charging 15 Jun + produk TAK prorated -> caj PENUH")
    void startChargingWithNonProratedProductChargesFull() {
        startCharging("2026-06-15");
        subscriptionStart("2026-06-15");
        prorated(false);                // 99% produk production

        int posted = billing.generateForSp("SPB", YearMonth.of(2026, 6),
                GenMode.CURRENT, ctx());

        assertThat(posted).isEqualTo(1);
        assertThat(singleLineAmount()).isEqualByComparingTo("80.00");
    }

    @Test
    @DisplayName("Julai (31 hari) -> penyebut 31, bukan 30")
    void actualDaysNotThirty() {
        startCharging("2026-07-15");
        subscriptionStart("2026-07-15");
        prorated(true);

        billing.generateForSp("SPB", YearMonth.of(2026, 7), GenMode.CURRENT, ctx());

        // 80 x 17/31 = 43.87   (kalau /30 -> 45.33, SALAH)
        assertThat(singleLineAmount()).isEqualByComparingTo("43.87");
    }

    @Test
    @DisplayName("PROD: akaun dicipta 17 Jul, POSTPAID -> tiada caj Jun")
    void noChargeBeforeAccountExists() {
        // Akaun MY00006000041, dicipta 17 Julai, start_charging NULL.
        // Legacy caj RM80 untuk Jun — sebelum akaun wujud. Divergensi sengaja.
        startCharging(null);
        subscriptionStart("2026-07-17");

        int posted = billing.generateForSp("SPB", YearMonth.of(2026, 7),
                GenMode.POSTPAID, ctx());   // base = Jun

        assertThat(posted).isZero();
    }

    // ── GL income per produk (kod vs id) ─────────────────────────────
    //
    // product.income_gl_account_id ialah id chart_of_accounts (bigint), tetapi
    // PostingLine memerlukan KOD ("4000"). Ledger menterjemah via glCodeFor.
    //
    // Ujian sedia ada semua guna income_gl_account_id = NULL, jadi laluan ini
    // tidak pernah diuji — sebab itu bug id-sebagai-kod terlepas.

    @Test
    @DisplayName("Produk dengan income_gl_account_id -> kredit ke kod GL betul")
    void productIncomeGlRoutesToCorrectCode() {
        // Ambil id akaun PENALTY_INCOME (4100) — beza dari default SERVICE_INCOME (4000)
        Long penaltyId = ((Number) em.createNativeQuery(
                "SELECT id FROM chart_of_accounts WHERE sp_code='SPB' AND code='4100'")
                .getSingleResult()).longValue();

        em.createNativeQuery("UPDATE product SET income_gl_account_id = :gl WHERE id = :prod")
                .setParameter("gl", penaltyId)
                .setParameter("prod", productId).executeUpdate();

        billing.generateForSp("SPB", YearMonth.of(2026, 1), GenMode.CURRENT, ctx());

        // Kredit patut ke 4100 (via id->kod), bukan 4000 default
        BigDecimal credit4100 = glCredit("4100");
        BigDecimal credit4000 = glCredit("4000");
        assertThat(credit4100).isEqualByComparingTo("80.00");
        assertThat(credit4000).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("income_gl_account_id NULL -> default SERVICE_INCOME")
    void nullIncomeGlUsesDefault() {
        // produk ujian sudah NULL — sahkan ia ke 4000
        billing.generateForSp("SPB", YearMonth.of(2026, 1), GenMode.CURRENT, ctx());
        assertThat(glCredit("4000")).isEqualByComparingTo("80.00");
    }

    @Test
    @DisplayName("glCodeFor(id tak wujud) -> campak (jaring; FK sudah halang di DB)")
    void glCodeForRejectsUnknownId() {
        // FK fk_product_income menghalang product.income_gl_account_id tergantung,
        // jadi laluan ini tidak boleh dicapai melalui produk. glCodeFor tetap
        // campak sebagai jaring — uji terus.
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () ->
                ledger.glCodeFor("SPB", 999999L));
    }

    /** Jumlah kredit ke satu kod GL untuk SPB. */
    private java.math.BigDecimal glCredit(String code) {
        return (java.math.BigDecimal) em.createNativeQuery("""
                SELECT COALESCE(SUM(jl.credit_amount), 0)
                FROM journal_line jl
                JOIN journal_entry je ON je.id = jl.journal_entry_id
                JOIN chart_of_accounts coa ON coa.id = jl.gl_account_id
                WHERE je.sp_code = 'SPB' AND coa.code = :code
                """)
                .setParameter("code", code)
                .getSingleResult();
    }
}
