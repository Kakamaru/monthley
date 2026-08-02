package com.monthley.catalog.internal;

import com.monthley.shared.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nyahaktif dan aktifkan semula produk.
 *
 * TIADA PADAM. Produk yang pernah dilanggan atau dibil mempunyai baris
 * yang merujuknya; memadamnya meninggalkan rujukan yatim atau melanggar
 * FK. Nyahaktif menyembunyikannya daripada senarai aktif dan daripada
 * penjanaan bil, dan sejarah kekal boleh dibaca.
 *
 * Ujian pertama untuk modul catalog — ProductController tidak pernah
 * disentuh secara langsung sebelum ini.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ProductStatusTest {

    private static final String SP = "SPPS";

    @Autowired ProductController controller;
    @PersistenceContext EntityManager em;

    @BeforeEach
    void seed() {
        em.createNativeQuery("""
                INSERT IGNORE INTO service_provider (sp_code, name, status, version)
                VALUES (:sp, 'SP Ujian Status Produk', 'ACTIVE', 0)
                """).setParameter("sp", SP).executeUpdate();
        TenantContext.set(SP);
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    private Long cipta(String kod) {
        var r = new ProductController.SaveProductRequest(
                kod, "SUB-" + kod, null, "Produk " + kod, "Keterangan asal",
                new BigDecimal("55.00"), "MONTHLY", 3,
                true, true, true, true);
        return controller.create(r).id();
    }

    private Object[] baca(long id) {
        return (Object[]) em.createNativeQuery("""
                SELECT status, name, unit_rate, charge_frequency, anchor_month,
                       subscription_code, description, prorated, late_penalty,
                       mandatory, main_product
                FROM   product WHERE id = :id
                """).setParameter("id", id).getSingleResult();
    }

    @Test
    @DisplayName("Nyahaktif: status INACTIVE")
    void nyahaktif() {
        long id = cipta("PS-A");

        var resp = controller.setStatus(id, new ProductController.StatusRequest(false));

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        em.flush();
        em.clear();
        assertThat(baca(id)[0]).isEqualTo("INACTIVE");
    }

    @Test
    @DisplayName("Aktifkan semula: kembali ACTIVE")
    void aktifkanSemula() {
        // Tab Inactive Products wujud untuk ini — nyahaktif bukan
        // keputusan muktamad.
        long id = cipta("PS-B");
        controller.setStatus(id, new ProductController.StatusRequest(false));
        em.flush();

        controller.setStatus(id, new ProductController.StatusRequest(true));
        em.flush();
        em.clear();

        assertThat(baca(id)[0]).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("Tukar status TIDAK menyentuh medan lain")
    void medanLainKekal() {
        // Sebab endpoint ini wujud berasingan daripada update(): update
        // menuntut SaveProductRequest LENGKAP, dan satu medan yang
        // terlepas menjadi null secara SENYAP.
        //
        // Kalau seseorang kemudian "memudahkan" dengan menghalakan
        // status melalui update(), ujian ini memerah.
        long id = cipta("PS-C");
        Object[] sebelum = baca(id);

        controller.setStatus(id, new ProductController.StatusRequest(false));
        em.flush();
        em.clear();

        Object[] selepas = baca(id);
        for (int i = 1; i < sebelum.length; i++) {
            assertThat(selepas[i])
                    .as("medan indeks " + i + " berubah")
                    .isEqualTo(sebelum[i]);
        }
    }

    @Test
    @DisplayName("Produk SP lain: 404, bukan diubah")
    void spLainDitolak() {
        // Pengasingan penyewa. Tanpa ini, SP boleh menyahaktifkan produk
        // SP lain dengan meneka id.
        em.createNativeQuery("""
                INSERT IGNORE INTO service_provider (sp_code, name, status, version)
                VALUES ('SPPT', 'SP Lain', 'ACTIVE', 0)
                """).executeUpdate();
        em.createNativeQuery("""
                INSERT INTO product (sp_code, code, name, charge_frequency, unit_rate,
                                     main_product, mandatory, prorated, late_penalty,
                                     status, version)
                VALUES ('SPPT', 'PS-LAIN', 'Produk SP Lain', 'MONTHLY', 10.00,
                        0, 0, 0, 0, 'ACTIVE', 0)
                """).executeUpdate();
        long lain = ((Number) em.createNativeQuery(
                "SELECT id FROM product WHERE sp_code='SPPT' AND code='PS-LAIN'")
                .getSingleResult()).longValue();
        em.flush();

        var resp = controller.setStatus(lain, new ProductController.StatusRequest(false));

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        em.clear();
        assertThat(baca(lain)[0]).as("tidak disentuh").isEqualTo("ACTIVE");
    }
}
