package com.monthley.billing.internal;

import com.monthley.ledger.api.GlAccounts;
import com.monthley.ledger.internal.ChartOfAccountSeeder;
import com.monthley.shared.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Menguji InvoicingController.generate() — laluan wiring BillingContext dari
 * setting sebenar. 75 ujian lain memanggil generateForSp() terus dengan ctx()
 * buatan, jadi laluan ini (settings.forSp, glCodeFor, excludedPeriodIds, empat
 * cabang null/bukan-null) tidak pernah disentuh sebelum ini.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class InvoicingControllerTest {

    @Autowired InvoicingController controller;
    @Autowired ChartOfAccountSeeder seeder;
    @PersistenceContext EntityManager em;

    Long accountId, productId;

    // ── Laporan penjanaan (ADR 0014 P2) ──────────────────────────────

    /** Admin SP dengan e-mel — penerima laporan. */
    private void adminSp(String email, String role) {
        em.createNativeQuery("""
                INSERT INTO app_user (email, full_name, password_hash, status, uuid)
                VALUES (:e, 'Admin Ujian', 'x', 'ACTIVE', UUID())
                """).setParameter("e", email).executeUpdate();
        long uid = ((Number) em.createNativeQuery(
                "SELECT id FROM app_user WHERE email = :e")
                .setParameter("e", email).getSingleResult()).longValue();
        em.createNativeQuery("""
                INSERT INTO sp_membership (sp_code, user_id, role, status)
                VALUES ('SPC', :u, :r, 'ACTIVE')
                """).setParameter("u", uid).setParameter("r", role).executeUpdate();
        em.flush();
    }

    private void notifikasi(int emailOnInvoice) {
        em.createNativeQuery("""
                INSERT INTO sp_notification_setting (sp_code, email_on_invoice, version)
                VALUES ('SPC', :v, 0)
                ON DUPLICATE KEY UPDATE email_on_invoice = :v
                """).setParameter("v", emailOnInvoice).executeUpdate();
        em.flush();
    }

    @SuppressWarnings("unchecked")
    private java.util.List<Object[]> barisOutbox() {
        em.flush();
        em.clear();
        return em.createNativeQuery("""
                SELECT to_email, param1_key, param1_val, param2_key, param2_val
                FROM   email_outbox
                WHERE  sp_code = 'SPC' AND kind = 'GENERATION_REPORT'
                ORDER  BY to_email
                """).getResultList();
    }

    private void akaunBerEmel(String no, String emel, String cc) {
        em.createNativeQuery("""
                INSERT INTO account (sp_code, account_no, account_name,
                                     billto_email, billto_email_secondary, status)
                VALUES ('SPC', :no, :no, :e, :cc, 'ACTIVE')
                """).setParameter("no", no).setParameter("e", emel)
                .setParameter("cc", cc).executeUpdate();
        em.flush();
    }

    @SuppressWarnings("unchecked")
    private java.util.List<Object[]> barisPenyata() {
        em.flush();
        em.clear();
        return em.createNativeQuery("""
                SELECT to_email, cc_email, param1_val, param2_val, ref_key
                FROM   email_outbox
                WHERE  sp_code = 'SPC' AND kind = 'STATEMENT'
                ORDER  BY to_email
                """).getResultList();
    }

    @Test
    @DisplayName("Penyata: SEMUA akaun aktif yang ada e-mel, satu per akaun")
    void penyataUntukAkaunBerEmel() {
        // Penyata ialah keadaan AKAUN, bukan resit bagi satu invois.
        // Akaun yang tidak menerima invois dalam larian ini tetap
        // mendapat penyata — bakinya mungkin masih tertunggak.
        adminSp("admin@ujian.my", "SP_ADMIN");
        notifikasi(1);
        akaunBerEmel("PS-1", "satu@ujian.my", "dua@ujian.my");
        akaunBerEmel("PS-2", "tiga@ujian.my", null);

        controller.generate(null);

        var baris = barisPenyata();
        assertThat(baris).hasSize(2);

        // cc daripada LAJUR, bukan rentetan butiran.
        assertThat(baris.get(0)[0]).isEqualTo("satu@ujian.my");
        assertThat(baris.get(0)[1]).isEqualTo("dua@ujian.my");
        assertThat(baris.get(1)[1]).as("tiada alamat kedua").isNull();

        // spName|akaunNo|tempoh|baki|spEmail|spPhone|url
        String[] f = ((String) baris.get(0)[3]).split("\\|", -1);
        assertThat(f).hasSize(7);
        assertThat(f[1]).as("nombor akaun").isEqualTo("PS-1");
        assertThat(f[6]).as("pautan awam").contains("/api/v1/pub/stmt/");
    }

    @Test
    @DisplayName("Penyata: akaun TANPA e-mel dilangkau")
    void penyataAkaunTanpaEmelDilangkau() {
        // SP boleh mempunyai akaun tanpa e-mel — bil diserahkan sendiri.
        // Itu pilihan yang sah, bukan kegagalan.
        adminSp("admin@ujian.my", "SP_ADMIN");
        notifikasi(1);
        akaunBerEmel("PS-ADA", "ada@ujian.my", null);
        em.createNativeQuery("""
                INSERT INTO account (sp_code, account_no, account_name, status)
                VALUES ('SPC', 'PS-TIADA', 'PS-TIADA', 'ACTIVE')
                """).executeUpdate();
        em.flush();

        controller.generate(null);

        assertThat(barisPenyata()).singleElement()
                .extracting(r -> ((Object[]) r)[0])
                .isEqualTo("ada@ujian.my");
    }

    @Test
    @DisplayName("Penyata: larian KEDUA bulan sama tidak menghantar dua kali")
    void penyataTidakBerulangDalamBulanSama() {
        // ref_key membawa bulan larian: kerani yang menekan Jana Bil
        // sekali lagi tidak menghantar penyata kedua kepada semua
        // pelanggan.
        adminSp("admin@ujian.my", "SP_ADMIN");
        notifikasi(1);
        akaunBerEmel("PS-DUP", "dup@ujian.my", null);

        controller.generate(null);
        controller.generate(null);

        assertThat(barisPenyata()).hasSize(1);
    }

    @Test
    @DisplayName("Laporan diberatur untuk SP_ADMIN sahaja, satu baris setiap admin")
    void laporanBeraturUntukAdminSahaja() {
        // Kerani mengendalikan bayaran, bukan mengawasi larian bil.
        adminSp("admin1@ujian.my", "SP_ADMIN");
        adminSp("admin2@ujian.my", "SP_ADMIN");
        adminSp("kerani@ujian.my", "CLERK");
        notifikasi(1);

        controller.generate(null);

        var baris = barisOutbox();
        assertThat(baris).hasSize(2);
        assertThat(baris.get(0)[0]).isEqualTo("admin1@ujian.my");
        assertThat(baris.get(1)[0]).isEqualTo("admin2@ujian.my");

        // Params dibaca ikut KUNCI oleh renderer, tetapi susunan tetap
        // dijamin di sini: LinkedHashMap dengan put() berturutan, bukan
        // Map.of yang menyusun ikut hash.
        assertThat(baris.get(0)[1]).isEqualTo("p_sp_name");
        assertThat(baris.get(0)[2]).isEqualTo("SP Controller Test");
        assertThat(baris.get(0)[3]).isEqualTo("p_summary");
        assertThat((String) baris.get(0)[4])
                .as("tarikh|akaun|invois|jumlah|tempoh")
                .contains("|")
                .startsWith(java.time.LocalDate.now().toString());
    }

    @Test
    @DisplayName("email_on_invoice = 0: TIADA laporan diberatur")
    void tetapanMatiTiadaLaporan() {
        // CASE-008: tetapan yang disimpan dan dibaca tetapi tidak pernah
        // dikuatkuasakan. Ujian ini membuktikan menukarnya MENGUBAH
        // tingkah laku, bukan sekadar bahawa ia tersimpan.
        adminSp("admin@ujian.my", "SP_ADMIN");
        notifikasi(0);

        controller.generate(null);

        assertThat(barisOutbox()).isEmpty();
    }

    @Test
    @DisplayName("Tiada SP_ADMIN: tiada laporan, invois tetap dijana")
    void tiadaAdminTiadaLaporan() {
        adminSp("kerani@ujian.my", "CLERK");
        notifikasi(1);

        var hasil = controller.generate(null);

        assertThat(hasil.invoicesPosted())
                .as("kegagalan notifikasi tidak boleh menghalang bil")
                .isPositive();
        assertThat(barisOutbox()).isEmpty();
    }


    @BeforeEach
    void setup() {
        em.createNativeQuery("""
            INSERT IGNORE INTO service_provider (sp_code, name, status, created_at, updated_at, version)
            VALUES ('SPC', 'SP Controller Test', 'ACTIVE', NOW(), NOW(), 0)
            """).executeUpdate();
        seeder.seedFor("SPC");

        // Setting WAJIB wujud — controller JOIN dua table
        em.createNativeQuery("""
            INSERT IGNORE INTO sp_document_setting (sp_code, invoice_gen_mode, allow_price_override)
            VALUES ('SPC', 'CURRENT', 1)
            """).executeUpdate();
        em.createNativeQuery("""
            INSERT IGNORE INTO sp_billing_setting (sp_code, currency, language, payment_term_days,
                                                   tax_rate, smallest_denomination, version)
            VALUES ('SPC', 'MYR', 'ms', 30, 0.00, 0.00, 0)
            """).executeUpdate();

        em.createNativeQuery("""
            INSERT INTO product (sp_code, code, name, charge_frequency, unit_rate,
                                 main_product, mandatory, prorated, late_penalty, status,
                                 created_at, updated_at, version)
            VALUES ('SPC', 'MF', 'Maintenance', 'MONTHLY', 80.00, 0, 0, 0, 0, 'ACTIVE', NOW(), NOW(), 0)
            """).executeUpdate();
        productId = ((Number) em.createNativeQuery(
                "SELECT id FROM product WHERE sp_code='SPC' AND code='MF'").getSingleResult()).longValue();

        em.createNativeQuery("""
            INSERT INTO account (sp_code, account_no, account_name, charge_frequency,
                                 start_date, status, created_at, updated_at, version)
            VALUES ('SPC', 'A1', 'Ali', 'MONTHLY', '2026-01-01', 'ACTIVE', NOW(), NOW(), 0)
            """).executeUpdate();
        accountId = ((Number) em.createNativeQuery(
                "SELECT id FROM account WHERE sp_code='SPC' AND account_no='A1'").getSingleResult()).longValue();

        em.createNativeQuery("""
            INSERT INTO account_subscription (sp_code, account_id, product_id, quantity,
                                              start_date, status, created_at, updated_at, version)
            VALUES ('SPC', :acc, :prod, 1, '2026-01-01', 'ACTIVE', NOW(), NOW(), 0)
            """).setParameter("acc", accountId).setParameter("prod", productId).executeUpdate();

        TenantContext.set("SPC");
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    private BigDecimal glCredit(String code) {
        return (BigDecimal) em.createNativeQuery("""
                SELECT COALESCE(SUM(jl.credit_amount), 0) FROM journal_line jl
                JOIN journal_entry je ON je.id = jl.journal_entry_id
                JOIN chart_of_accounts coa ON coa.id = jl.gl_account_id
                WHERE je.sp_code='SPC' AND coa.code = :c
                """).setParameter("c", code).getSingleResult();
    }

    @Test
    @DisplayName("generate() baca gen_mode dari sp_document_setting")
    void readsGenModeFromSetting() {
        // setting = CURRENT; request mode null -> ikut setting
        var res = controller.generate(new InvoicingController.GenerateRequest("2026-03", null));
        assertThat(res.invoicesPosted()).isEqualTo(1);
        assertThat(res.mode()).isEqualTo("CURRENT");
    }

    @Test
    @DisplayName("GL income NULL dalam setting -> default SERVICE_INCOME (4000)")
    void nullGlUsesDefault() {
        controller.generate(new InvoicingController.GenerateRequest("2026-03", "CURRENT"));
        assertThat(glCredit("4000")).isEqualByComparingTo("80.00");
    }

    @Test
    @DisplayName("smallest_denomination 0 -> tiada pembundaran")
    void zeroDenomNoRounding() {
        controller.generate(new InvoicingController.GenerateRequest("2026-03", "CURRENT"));
        assertThat(glCredit("4000")).isEqualByComparingTo("80.00");
    }

    @Test
    @DisplayName("excludedPeriodIds dibaca -> period dikecualikan tiada bil")
    void excludedPeriodProducesNoInvoice() {
        // Kecualikan Mac 2026: period_id bulanan
        long marId = com.monthley.shared.PeriodIds.ofMonth(java.time.YearMonth.of(2026, 3));
        em.createNativeQuery("""
            INSERT INTO invoice_exclude_period (sp_code, period_id, remarks)
            VALUES ('SPC', :pid, 'ujian')
            """).setParameter("pid", marId).executeUpdate();

        var res = controller.generate(new InvoicingController.GenerateRequest("2026-03", "CURRENT"));

        // Produk bulanan, Mac dikecualikan -> baris gugur -> tiada invois
        assertThat(res.invoicesPosted()).isZero();
    }
}
