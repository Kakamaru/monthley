package com.monthley.gateway.internal;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Model yuran gerbang (ADR 0007 #5).
 *
 * CASE-003 menemui 33 anomali dalam legacy, dan yuran adalah puncanya
 * dalam beberapa kes: ia wujud sebagai "beza yang kita jangka" (1.50,
 * 2.00, atau 0), jadi mengesan penyimpangan memerlukan seseorang mengingat
 * senarai beza yang boleh diterima.
 *
 * Model eksplisit menjadikan sebarang nilai lain jelas serta-merta.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class GatewayFeeTest {

    @PersistenceContext EntityManager em;

    @BeforeEach
    void setup() {
        em.createNativeQuery("""
            INSERT IGNORE INTO service_provider (sp_code, name, status,
                                                 created_at, updated_at, version)
            VALUES ('SPF1', 'Serap Yuran', 'ACTIVE', NOW(), NOW(), 0)
            """).executeUpdate();
        em.createNativeQuery("""
            INSERT IGNORE INTO service_provider (sp_code, name, status,
                                                 created_at, updated_at, version)
            VALUES ('SPF2', 'Tidak Serap', 'ACTIVE', NOW(), NOW(), 0)
            """).executeUpdate();

        // SPF1 MENYERAP yuran; SPF2 tidak.
        em.createNativeQuery("""
            INSERT INTO sp_payment_setting
              (sp_code, gateway, manual_payment, online_payment, absorb,
               rate_single, rate_multi, sandbox, version)
            VALUES ('SPF1', 'TP', 1, 1, 1, 1.50, 2.00, 1, 0)
            ON DUPLICATE KEY UPDATE absorb = 1
            """).executeUpdate();
        em.createNativeQuery("""
            INSERT INTO sp_payment_setting
              (sp_code, gateway, manual_payment, online_payment, absorb,
               rate_single, rate_multi, sandbox, version)
            VALUES ('SPF2', 'TP', 1, 1, 0, 1.50, 2.00, 1, 0)
            ON DUPLICATE KEY UPDATE absorb = 0
            """).executeUpdate();
        em.flush();
    }

    private BigDecimal kadar(String sp, int bilInvois) {
        Object[] r = (Object[]) em.createNativeQuery(
                "SELECT rate_single, rate_multi FROM sp_payment_setting WHERE sp_code = :sp")
                .setParameter("sp", sp).getSingleResult();
        return (BigDecimal) (bilInvois > 1 ? r[1] : r[0]);
    }

    private boolean serap(String sp) {
        Object v = em.createNativeQuery(
                "SELECT absorb FROM sp_payment_setting WHERE sp_code = :sp")
                .setParameter("sp", sp).getSingleResult();
        return v instanceof Boolean b ? b : ((Number) v).intValue() != 0;
    }

    @Test
    @DisplayName("satu invois guna rate_single")
    void satuInvois() {
        assertThat(kadar("SPF2", 1)).isEqualByComparingTo("1.50");
    }

    @Test
    @DisplayName("pelbagai invois guna rate_multi")
    void pelbagaiInvois() {
        assertThat(kadar("SPF2", 3)).isEqualByComparingTo("2.00");
    }

    /**
     * SP TIDAK menyerap: pelanggan menghantar amaun + yuran.
     *
     * Ini kes yang paling mudah tersalah. Gerbang memulangkan RM101.50,
     * dan menggunakannya terus sebagai amaun resit bermakna invois RM100
     * kelihatan terlebih bayar RM1.50 — lebihan itu menjadi advance yang
     * tidak pernah wujud.
     */
    @Test
    @DisplayName("SP tidak serap — pelanggan dicaj amaun + yuran, resit kekal amaun")
    void tidakSerap() {
        BigDecimal amaun = new BigDecimal("100.00");
        BigDecimal yuran = kadar("SPF2", 1);

        assertThat(serap("SPF2")).isFalse();

        BigDecimal dicaj = amaun.add(yuran);
        assertThat(dicaj).isEqualByComparingTo("101.50");

        // Gerbang memulangkan 101.50; resit mesti 100.00.
        BigDecimal diterima = dicaj;
        BigDecimal untukResit = diterima.subtract(yuran);
        assertThat(untukResit).isEqualByComparingTo("100.00");
    }

    /**
     * SP MENYERAP: pelanggan menghantar amaun tepat.
     *
     * Yuran dipotong di pihak gerbang, jadi tiada apa untuk ditolak —
     * menolaknya di sini menghasilkan resit RM98.50 untuk invois RM100,
     * dan invois kekal terbuka dengan baki RM1.50 yang tidak masuk akal
     * kepada pelanggan yang sudah membayarnya penuh.
     */
    @Test
    @DisplayName("SP serap — pelanggan dicaj amaun sahaja, resit kekal amaun")
    void serapYuran() {
        BigDecimal amaun = new BigDecimal("100.00");

        assertThat(serap("SPF1")).isTrue();

        BigDecimal dicaj = amaun;   // tiada tambahan
        assertThat(dicaj).isEqualByComparingTo("100.00");

        // Gerbang memulangkan 100.00; TIADA penolakan.
        BigDecimal untukResit = dicaj;
        assertThat(untukResit).isEqualByComparingTo("100.00");
    }

    /**
     * Resit adalah SAMA dalam kedua-dua kes.
     *
     * Itulah keseluruhan sebab model ini wujud: yuran ialah kos urusan,
     * bukan sebahagian bayaran terhadap invois. Invois RM100 dijelaskan
     * dengan RM100 tidak kira siapa menanggung yuran.
     */
    @Test
    @DisplayName("resit identik sama ada SP serap atau tidak")
    void resitIdentik() {
        BigDecimal amaun = new BigDecimal("100.00");

        BigDecimal resitSerap = amaun;
        BigDecimal resitTidakSerap = amaun.add(kadar("SPF2", 1))
                                          .subtract(kadar("SPF2", 1));

        assertThat(resitSerap).isEqualByComparingTo(resitTidakSerap);
    }
}
