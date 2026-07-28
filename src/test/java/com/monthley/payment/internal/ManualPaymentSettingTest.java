package com.monthley.payment.internal;

import com.monthley.ledger.internal.ChartOfAccountSeeder;
import com.monthley.shared.TenantContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * enable_manual_payment DIKUATKUASAKAN (CASE-008 kes 6).
 *
 * Tetapan wujud sejak V14 tetapi hanya muncul dalam SettingsController —
 * dibaca dan ditulis, tidak pernah disemak. SP mematikannya dan kerani
 * tetap boleh merekod bayaran, mengelak kawalan yang sengaja dipasang.
 *
 * Ujian ini mengikut piawai CASE-008: ia membuktikan menukar tetapan
 * MENGUBAH tingkah laku, bukan sekadar bahawa ia disimpan dan dibaca.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ManualPaymentSettingTest {

    private static final String SP = "SPMP";

    @Autowired ManualPaymentController controller;
    @Autowired ChartOfAccountSeeder seeder;
    @Autowired JdbcClient jdbc;
    @PersistenceContext EntityManager em;

    private long acc;

    @BeforeEach
    void seed() {
        jdbc.sql("""
                INSERT IGNORE INTO service_provider (sp_code, name, status, version)
                VALUES (:sp, 'SP Ujian Manual', 'ACTIVE', 0)
                """).param("sp", SP).update();
        seeder.seedFor(SP);
        jdbc.sql("""
                INSERT IGNORE INTO sp_document_setting (sp_code, enable_manual_payment, version)
                VALUES (:sp, 1, 0)
                """).param("sp", SP).update();

        String no = "MP-" + System.nanoTime();
        jdbc.sql("""
                INSERT INTO account (sp_code, account_no, account_name, status)
                VALUES (:sp, :no, 'Ujian Manual', 'ACTIVE')
                """).param("sp", SP).param("no", no).update();
        acc = jdbc.sql("SELECT id FROM account WHERE sp_code=:sp AND account_no=:no")
                .param("sp", SP).param("no", no).query(Long.class).single();

        TenantContext.set(SP);
        // Access.requireRole berjalan SEBELUM semakan tetapan — kawalan
        // peranan dahulu, kemudian kawalan tetapan. Tanpa authority,
        // ujian gagal pada kebenaran dan tidak pernah menguji tetapan.
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("clerk", "n/a",
                        java.util.List.of(new SimpleGrantedAuthority("SP_" + SP + "_CLERK"))));
    }

    @AfterEach
    void clear() { TenantContext.clear(); SecurityContextHolder.clearContext(); }

    private void tetapan(boolean hidup) {
        jdbc.sql("UPDATE sp_document_setting SET enable_manual_payment = :v WHERE sp_code = :sp")
                .param("v", hidup ? 1 : 0).param("sp", SP).update();
        em.flush();
        em.clear();
    }

    private ManualPaymentController.ManualPaymentRequest permintaan() {
        return new ManualPaymentController.ManualPaymentRequest(
                java.util.List.of(), acc, "CASH", null, null,
                new BigDecimal("25.00"), null, null);
    }

    @Test
    @DisplayName("tetapan HIDUP: bayaran diterima")
    void hidupBenarkan() {
        tetapan(true);
        var res = controller.recordPayment(permintaan());
        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    @DisplayName("tetapan MATI: bayaran DITOLAK, bukan diterima senyap")
    void matiTolak() {
        tetapan(false);

        assertThatThrownBy(() -> controller.recordPayment(permintaan()))
                .as("SP mematikannya dengan sengaja; menerima bayaran tetap "
                    + "bermakna mengelak kawalan itu")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Bayaran manual dimatikan");

        long bil = jdbc.sql(
                "SELECT COUNT(*) FROM payment WHERE payer_account_id = :a")
                .param("a", acc).query(Long.class).single();
        assertThat(bil)
                .as("tiada rekod bayaran dicipta")
                .isZero();
    }
}
