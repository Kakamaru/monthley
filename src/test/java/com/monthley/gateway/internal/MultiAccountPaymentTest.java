package com.monthley.gateway.internal;

import com.monthley.ledger.internal.ChartOfAccountSeeder;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Bayaran merentas beberapa akaun (ADR 0019).
 *
 * Satu transaksi gerbang, beberapa akaun, satu resit bagi setiap akaun.
 *
 * Bahagian yang paling mudah tersalah ialah idempotency: ADR 0004
 * menguatkuasakan keunikan pada kunci, jadi menghantar ourRef yang sama
 * untuk kedua-dua akaun bermakna bayaran kedua ditolak sebagai pendua —
 * dan pelanggan membayar untuk dua akaun tetapi hanya satu dijelaskan.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MultiAccountPaymentTest {

    @Autowired GatewayService gateway;
    @Autowired ChartOfAccountSeeder seeder;
    @PersistenceContext EntityManager em;

    Long uid;
    Long akaunA, akaunB;
    Long invA, invB;

    @BeforeEach
    void setup() {
        em.createNativeQuery("""
            INSERT IGNORE INTO service_provider
              (sp_code, name, status, allow_selective, min_pymt_amount,
               created_at, updated_at, version)
            VALUES ('SPM1', 'Ujian Multi', 'ACTIVE', 1, 1.00, NOW(), NOW(), 0)
            """).executeUpdate();
        seeder.seedFor("SPM1");

        em.createNativeQuery("""
            INSERT INTO sp_payment_setting
              (sp_code, gateway, manual_payment, online_payment, absorb,
               rate_single, rate_multi, rate_multi_acct, sandbox, version)
            VALUES ('SPM1', 'TP', 1, 1, 0, 1.50, 2.00, 2.50, 1, 0)
            ON DUPLICATE KEY UPDATE rate_multi_acct = 2.50
            """).executeUpdate();

        em.createNativeQuery("""
            INSERT IGNORE INTO app_user
              (email, full_name, mobile, password_hash, status, created_at, updated_at, version)
            VALUES ('multi@ujian.test', 'Pembayar Ujian', '0123456789',
                    'x', 'ACTIVE', NOW(), NOW(), 0)
            """).executeUpdate();
        uid = ((Number) em.createNativeQuery(
                "SELECT id FROM app_user WHERE email = 'multi@ujian.test'")
                .getSingleResult()).longValue();

        akaunA = akaun("MA-01", "Akaun A");
        akaunB = akaun("MA-02", "Akaun B");
        invA = invois(akaunA, "MA-INV-A", new BigDecimal("80.00"));
        invB = invois(akaunB, "MA-INV-B", new BigDecimal("50.00"));
        em.flush();
    }

    private Long akaun(String no, String nama) {
        em.createNativeQuery("""
            INSERT INTO account (sp_code, account_no, account_name, charge_frequency,
                                 start_date, status, payer_user_id,
                                 created_at, updated_at, version)
            VALUES ('SPM1', :no, :nm, 'MONTHLY', CURDATE(), 'ACTIVE', :uid,
                    NOW(), NOW(), 0)
            """).setParameter("no", no).setParameter("nm", nama)
                .setParameter("uid", uid).executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM account WHERE account_no = :no")
                .setParameter("no", no).getSingleResult()).longValue();
    }

    private Long invois(Long accId, String docNo, BigDecimal amaun) {
        em.createNativeQuery("""
            INSERT INTO financial_document
              (sp_code, account_id, doc_no, doc_type, doc_date, due_date,
               amount, tax_amount, status, created_at, updated_at, version)
            VALUES ('SPM1', :acc, :no, 'INVOICE', CURDATE(), CURDATE(),
                    :amt, 0.00, 'ACTIVE', NOW(), NOW(), 0)
            """).setParameter("acc", accId).setParameter("no", docNo)
                .setParameter("amt", amaun).executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM financial_document WHERE doc_no = :no")
                .setParameter("no", docNo).getSingleResult()).longValue();
    }

    // ---------- Kadar yuran ----------

    /**
     * Bilangan AKAUN mengatasi bilangan invois.
     *
     * Dua invois pada satu akaun ialah rate_multi (2.00); dua invois
     * merentas dua akaun ialah rate_multi_acct (2.50), walaupun bilangan
     * invois sama.
     */
    @Test
    @DisplayName("merentas akaun guna rate_multi_acct, bukan rate_multi")
    void kadarMerentasAkaun() {
        var p = gateway.previewMulti(uid, List.of(invA, invB), new BigDecimal("130.00"));
        assertThat(p.fee()).isEqualByComparingTo("2.50");
    }

    @Test
    @DisplayName("satu akaun dengan dua invois guna rate_multi")
    void kadarSatuAkaunDuaInvois() {
        Long invA2 = invois(akaunA, "MA-INV-A2", new BigDecimal("20.00"));
        em.flush();

        var p = gateway.previewMulti(uid, List.of(invA, invA2), new BigDecimal("100.00"));
        assertThat(p.fee()).isEqualByComparingTo("2.00");
    }

    @Test
    @DisplayName("satu invois guna rate_single")
    void kadarSatuInvois() {
        var p = gateway.previewMulti(uid, List.of(invA), new BigDecimal("80.00"));
        assertThat(p.fee()).isEqualByComparingTo("1.50");
    }

    // ---------- Guard ----------

    /**
     * Advance DITOLAK merentas akaun.
     *
     * Lebihan RM20 pada dua akaun tidak mempunyai jawapan yang betul untuk
     * 'akaun mana' — dan meletakkannya pada akaun pertama ialah pilihan
     * yang pelanggan tidak boleh jangka.
     */
    @Test
    @DisplayName("amaun melebihi jumlah ditolak bila merentas akaun")
    void lebihanDitolak() {
        assertThatThrownBy(() ->
                gateway.startMulti(uid, List.of(invA, invB), new BigDecimal("150.00")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tepat");
    }

    @Test
    @DisplayName("amaun kurang daripada jumlah ditolak bila merentas akaun")
    void kurangDitolak() {
        assertThatThrownBy(() ->
                gateway.startMulti(uid, List.of(invA, invB), new BigDecimal("100.00")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tepat");
    }

    /**
     * Invois orang lain ditolak.
     *
     * Pemilikan disahkan dalam pertanyaan yang sama yang membaca invois,
     * jadi baris yang bukan milik pengguna tidak pernah dipulangkan dan
     * kiraan yang tidak sepadan menolaknya.
     */
    @Test
    @DisplayName("invois bukan milik pengguna ditolak")
    void invoisOrangLainDitolak() {
        em.createNativeQuery("""
            INSERT INTO account (sp_code, account_no, account_name, charge_frequency,
                                 start_date, status, created_at, updated_at, version)
            VALUES ('SPM1', 'MA-99', 'Orang Lain', 'MONTHLY', CURDATE(), 'ACTIVE',
                    NOW(), NOW(), 0)
            """).executeUpdate();
        Long lain = ((Number) em.createNativeQuery(
                "SELECT id FROM account WHERE account_no = 'MA-99'")
                .getSingleResult()).longValue();
        Long invLain = invois(lain, "MA-INV-X", new BigDecimal("30.00"));
        em.flush();

        assertThatThrownBy(() ->
                gateway.startMulti(uid, List.of(invA, invLain), new BigDecimal("110.00")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bukan milik anda");
    }

    /**
     * Merentas SP ditolak (ADR 0018).
     *
     * absorb dan kadar berbeza antara SP, dan rujukan bank membawa satu
     * sp_code.
     */
    @Test
    @DisplayName("invois merentas dua SP ditolak")
    void merentasSpDitolak() {
        em.createNativeQuery("""
            INSERT IGNORE INTO service_provider
              (sp_code, name, status, created_at, updated_at, version)
            VALUES ('SPM2', 'SP Kedua', 'ACTIVE', NOW(), NOW(), 0)
            """).executeUpdate();
        em.createNativeQuery("""
            INSERT INTO account (sp_code, account_no, account_name, charge_frequency,
                                 start_date, status, payer_user_id,
                                 created_at, updated_at, version)
            VALUES ('SPM2', 'MB-01', 'Akaun SP2', 'MONTHLY', CURDATE(), 'ACTIVE', :uid,
                    NOW(), NOW(), 0)
            """).setParameter("uid", uid).executeUpdate();
        Long acc2 = ((Number) em.createNativeQuery(
                "SELECT id FROM account WHERE account_no = 'MB-01'")
                .getSingleResult()).longValue();

        em.createNativeQuery("""
            INSERT INTO financial_document
              (sp_code, account_id, doc_no, doc_type, doc_date, due_date,
               amount, tax_amount, status, created_at, updated_at, version)
            VALUES ('SPM2', :acc, 'MB-INV', 'INVOICE', CURDATE(), CURDATE(),
                    40.00, 0.00, 'ACTIVE', NOW(), NOW(), 0)
            """).setParameter("acc", acc2).executeUpdate();
        Long inv2 = ((Number) em.createNativeQuery(
                "SELECT id FROM financial_document WHERE doc_no = 'MB-INV'")
                .getSingleResult()).longValue();
        em.flush();

        assertThatThrownBy(() ->
                gateway.startMulti(uid, List.of(invA, inv2), new BigDecimal("120.00")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("organisasi");
    }
}
