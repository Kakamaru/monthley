package com.monthley.platform.internal;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Hak modul dan bil mesti berubah BERSAMA, dengan tarikh berbeza.
 *
 * Satu peristiwa menghasilkan dua tarikh (ADR 0016): modul diluluskan
 * 15 Ogos bermakna hak aktif 15 Ogos tetapi bil bermula 1 September. Itu
 * sebab hak dipisahkan daripada bil — satu rekod memaksa kita memilih
 * satu tarikh, dan salah satunya akan salah.
 *
 * Kegagalan di sini senyap dalam pengeluaran: SP dibil untuk modul yang
 * tidak boleh diguna, atau menggunakan modul percuma selama berbulan.
 * Tiada apa yang memberi amaran sehingga seseorang menyemak manual.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ModuleEntitlementTest {

    @Autowired ModuleEntitlementService service;
    @PersistenceContext EntityManager em;

    Long produkModul;
    Long akaunBil;

    @BeforeEach
    void setup() {
        // SP platform + SP pelanggan
        em.createNativeQuery("""
            INSERT IGNORE INTO service_provider (sp_code, name, status, is_platform_owner,
                                                 created_at, updated_at, version)
            VALUES ('SPE0', 'Platform Ujian', 'ACTIVE', 1, NOW(), NOW(), 0)
            """).executeUpdate();

        em.createNativeQuery("""
            INSERT INTO product (sp_code, code, name, charge_frequency, unit_rate,
                                 main_product, mandatory, prorated, late_penalty,
                                 status, created_at, updated_at, version)
            VALUES ('SPE0', 'MODX', 'Modul Ujian', 'MONTHLY', 25.00,
                    0,0,0,0, 'ACTIVE', NOW(), NOW(), 0)
            """).executeUpdate();
        produkModul = ((Number) em.createNativeQuery(
                "SELECT id FROM product WHERE sp_code='SPE0' AND code='MODX'")
                .getSingleResult()).longValue();

        em.createNativeQuery("""
            INSERT INTO account (sp_code, account_no, account_name, charge_frequency,
                                 start_date, status, created_at, updated_at, version)
            VALUES ('SPE0', 'ACC-E1', 'SP Pelanggan Ujian', 'MONTHLY',
                    CURDATE(), 'ACTIVE', NOW(), NOW(), 0)
            """).executeUpdate();
        akaunBil = ((Number) em.createNativeQuery(
                "SELECT id FROM account WHERE sp_code='SPE0' AND account_no='ACC-E1'")
                .getSingleResult()).longValue();

        em.createNativeQuery("""
            INSERT IGNORE INTO service_provider (sp_code, name, status, billing_account_id,
                                                 created_at, updated_at, version)
            VALUES ('SPE1', 'Pelanggan Ujian', 'ACTIVE', :acc, NOW(), NOW(), 0)
            """).setParameter("acc", akaunBil).executeUpdate();

        em.createNativeQuery("""
            INSERT IGNORE INTO ref_module (code, name, product_id, sort_order, status,
                                           created_at, updated_at, version)
            VALUES ('MODX', 'Modul Ujian', :p, 1, 'ACTIVE', NOW(), NOW(), 0)
            """).setParameter("p", produkModul).executeUpdate();

        em.flush();
    }

    @Test
    @DisplayName("beri hak — aktif serta-merta, bil bermula 1hb bulan berikutnya")
    void grantMenciptaHakDanBil() {
        service.grant("SPE1", "MODX", null);
        em.flush();

        Object[] hak = (Object[]) em.createNativeQuery(
                "SELECT status, start_date, end_date FROM sp_module "
                + "WHERE sp_code='SPE1' AND module_code='MODX'").getSingleResult();

        assertThat((String) hak[0]).isEqualTo("ACTIVE");
        assertThat(tarikh(hak[1])).isEqualTo(LocalDate.now());
        assertThat(hak[2]).isNull();

        Object[] bil = (Object[]) em.createNativeQuery(
                "SELECT start_date, status, product_id FROM account_subscription "
                + "WHERE account_id = :a AND product_id = :p")
                .setParameter("a", akaunBil).setParameter("p", produkModul)
                .getSingleResult();

        // Bil bermula 1hb BULAN BERIKUTNYA, bukan hari ini.
        LocalDate dijangka = LocalDate.now().withDayOfMonth(1).plusMonths(1);
        assertThat(tarikh(bil[0])).isEqualTo(dijangka);
        assertThat((String) bil[1]).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("tamat hak — guna sampai hujung bulan, bil berhenti serentak")
    void revokeMenamatkanKeduaDua() {
        service.grant("SPE1", "MODX", null);
        em.flush();

        service.revoke("SPE1", "MODX", null);
        em.flush();

        LocalDate hujungBulan = LocalDate.now()
                .withDayOfMonth(LocalDate.now().lengthOfMonth());

        Object[] hak = (Object[]) em.createNativeQuery(
                "SELECT status, end_date FROM sp_module "
                + "WHERE sp_code='SPE1' AND module_code='MODX'").getSingleResult();
        assertThat((String) hak[0]).isEqualTo("ENDED");
        assertThat(tarikh(hak[1])).isEqualTo(hujungBulan);

        Object[] bil = (Object[]) em.createNativeQuery(
                "SELECT status, end_date FROM account_subscription "
                + "WHERE account_id = :a AND product_id = :p")
                .setParameter("a", akaunBil).setParameter("p", produkModul)
                .getSingleResult();
        assertThat((String) bil[0]).isEqualTo("ENDED");
        assertThat(tarikh(bil[1])).isEqualTo(hujungBulan);
    }

    @Test
    @DisplayName("hak berulang ditolak — modul tidak boleh diberi dua kali")
    void grantDuaKaliDitolak() {
        service.grant("SPE1", "MODX", null);
        em.flush();

        assertThatThrownBy(() -> service.grant("SPE1", "MODX", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sudah mempunyai modul");
    }

    /**
     * SP tanpa akaun bil tidak boleh melanggan.
     *
     * Membenarkannya menghasilkan hak tanpa bil — SP menggunakan modul
     * secara percuma selama-lamanya, dan tiada apa yang memberi amaran.
     */
    @Test
    @DisplayName("SP tanpa akaun bil ditolak dengan mesej jelas")
    void tanpaAkaunBilDitolak() {
        em.createNativeQuery("""
            INSERT IGNORE INTO service_provider (sp_code, name, status,
                                                 created_at, updated_at, version)
            VALUES ('SPE2', 'Tiada Akaun Bil', 'ACTIVE', NOW(), NOW(), 0)
            """).executeUpdate();
        em.flush();

        assertThatThrownBy(() -> service.grant("SPE2", "MODX", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tiada akaun bil");
    }

    private static LocalDate tarikh(Object v) {
        if (v == null) return null;
        if (v instanceof LocalDate d) return d;
        if (v instanceof java.sql.Date d) return d.toLocalDate();
        return LocalDate.parse(v.toString());
    }
}
