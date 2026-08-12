package com.monthley.complaints.internal;

import com.monthley.shared.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Aduan — peraturan yang senyap apabila salah.
 *
 * Kegagalan di sini tidak menghasilkan ralat: aduan muncul pada SP yang
 * salah, nota dalaman dilihat pengadu, atau aduan yang dibuka semula
 * kekal dikira sebagai selesai. Semuanya hanya disedari apabila seseorang
 * mengadu tentang aduan.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AduServiceTest {

    @Autowired AduService service;
    @PersistenceContext EntityManager em;

    Long akaunA;
    Long akaunB;   // milik SP LAIN
    Long kategori;

    @BeforeEach
    void setup() {
        for (String kod : new String[]{"SPA1", "SPA2"}) {
            em.createNativeQuery("""
                INSERT IGNORE INTO service_provider (sp_code, name, status,
                                                     created_at, updated_at, version)
                VALUES (:k, :n, 'ACTIVE', NOW(), NOW(), 0)
                """).setParameter("k", kod).setParameter("n", "Ujian " + kod).executeUpdate();

            em.createNativeQuery("""
                INSERT IGNORE INTO ref_module (code, name, sort_order, status,
                                               created_at, updated_at, version)
                VALUES ('ADUAN', 'Aduan', 2, 'ACTIVE', NOW(), NOW(), 0)
                """).executeUpdate();

            em.createNativeQuery("""
                INSERT INTO sp_module (sp_code, module_code, status, start_date,
                                       created_at, updated_at, version)
                VALUES (:k, 'ADUAN', 'ACTIVE', CURDATE(), NOW(), NOW(), 0)
                """).setParameter("k", kod).executeUpdate();

            em.createNativeQuery("""
                INSERT INTO account (sp_code, account_no, account_name, charge_frequency,
                                     start_date, status, created_at, updated_at, version)
                VALUES (:k, :no, 'Penghuni Ujian', 'MONTHLY', CURDATE(), 'ACTIVE',
                        NOW(), NOW(), 0)
                """).setParameter("k", kod).setParameter("no", "ACC-" + kod).executeUpdate();
        }

        akaunA = ((Number) em.createNativeQuery(
                "SELECT id FROM account WHERE account_no='ACC-SPA1'").getSingleResult()).longValue();
        akaunB = ((Number) em.createNativeQuery(
                "SELECT id FROM account WHERE account_no='ACC-SPA2'").getSingleResult()).longValue();

        em.createNativeQuery("""
            INSERT INTO adu_category (sp_code, name, sort_order, status,
                                      created_at, updated_at, version)
            VALUES ('SPA1', 'Penyelenggaraan', 1, 'ACTIVE', NOW(), NOW(), 0)
            """).executeUpdate();
        kategori = ((Number) em.createNativeQuery(
                "SELECT id FROM adu_category WHERE sp_code='SPA1'").getSingleResult()).longValue();

        em.flush();
        TenantContext.set("SPA1");
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    private Long cipta(String tajuk, Long akaun) {
        return service.create(new AduService.NewComplaint(
                akaun, kategori, tajuk, "Butiran ujian", "HIGH", "Ali", "0123456789"),
                null, true);
    }

    @Test
    @DisplayName("aduan dicipta dengan nombor dan status NEW")
    void ciptaAduan() {
        Long id = cipta("Lampu rosak", akaunA);
        em.flush();

        Object[] r = (Object[]) em.createNativeQuery(
                "SELECT complaint_no, status, priority, sp_code FROM adu_complaint WHERE id=:i")
                .setParameter("i", id).getSingleResult();

        assertThat((String) r[0]).startsWith("ADU");
        assertThat((String) r[1]).isEqualTo("NEW");
        assertThat((String) r[2]).isEqualTo("HIGH");
        assertThat((String) r[3]).isEqualTo("SPA1");
    }

    /**
     * Akaun SP LAIN ditolak.
     *
     * Tanpa semakan ini, sesiapa yang boleh menghantar permintaan boleh
     * mencipta aduan pada mana-mana SP dengan meneka id akaun — dan aduan
     * itu muncul dalam senarai SP yang tidak pernah mendengarnya.
     */
    @Test
    @DisplayName("akaun milik SP lain ditolak")
    void akaunSpLainDitolak() {
        assertThatThrownBy(() -> cipta("Silap SP", akaunB))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tidak wujud untuk organisasi ini");
    }

    @Test
    @DisplayName("balasan SP menukar status dan resolved_at diisi")
    void balasanSpMenukarStatus() {
        Long id = cipta("Paip bocor", akaunA);
        em.flush();

        service.reply(id, new AduService.ReplyRequest(
                "Juruteknik dihantar.", "RESOLVED", null, "kontraktor dibayar", false),
                null, true);
        em.flush();

        Object[] r = (Object[]) em.createNativeQuery(
                "SELECT status, resolved_at, internal_note FROM adu_complaint WHERE id=:i")
                .setParameter("i", id).getSingleResult();

        assertThat((String) r[0]).isEqualTo("RESOLVED");
        assertThat(r[1]).isNotNull();
        assertThat((String) r[2]).isEqualTo("kontraktor dibayar");
    }

    /**
     * Pengadu yang membalas aduan SELESAI membukanya semula.
     *
     * Itu satu-satunya isyarat bahawa penyelesaian tidak menyelesaikan
     * masalah. Tanpa ia, aduan kekal dikira selesai dan kadar penyelesaian
     * kelihatan lebih baik daripada realiti.
     */
    @Test
    @DisplayName("pengadu balas aduan selesai — dibuka semula")
    void pengaduBukaSemula() {
        Long id = cipta("Lif rosak", akaunA);
        em.flush();

        service.reply(id, new AduService.ReplyRequest(
                "Sudah dibaiki.", "RESOLVED", null, null, false), null, true);
        em.flush();

        service.reply(id, new AduService.ReplyRequest(
                "Masih rosak.", null, null, null, false), null, false);
        em.flush();

        Object[] r = (Object[]) em.createNativeQuery(
                "SELECT status, resolved_at FROM adu_complaint WHERE id=:i")
                .setParameter("i", id).getSingleResult();

        assertThat((String) r[0]).isEqualTo("REOPENED");
        // resolved_at dikosongkan supaya 'purata masa selesai' mengukur
        // penyelesaian TERKINI dan bukan yang pertama.
        assertThat(r[1]).isNull();
    }

    /**
     * Pengadu TIDAK boleh menukar status atau menulis nota dalaman.
     *
     * Membenarkannya bermakna pengadu boleh menutup aduannya sendiri, dan
     * nota yang sepatutnya tersembunyi ditulis oleh orang yang tidak
     * sepatutnya melihatnya.
     */
    @Test
    @DisplayName("pengadu tidak boleh menukar status atau nota dalaman")
    void pengaduTidakBolehUbahStatus() {
        Long id = cipta("Sampah tidak dikutip", akaunA);
        em.flush();

        // assignedTo dibiarkan NULL dengan sengaja: nilai palsu melanggar FK
        // dan ujian gagal atas sebab yang salah — ia akan lulus walaupun
        // pengadu dibenarkan menukar STATUS, iaitu perkara yang diuji.
        service.reply(id, new AduService.ReplyRequest(
                "Tolong cepat.", "RESOLVED", null, "nota curi", false), null, false);
        em.flush();

        Object[] r = (Object[]) em.createNativeQuery(
                "SELECT status, assigned_to, internal_note FROM adu_complaint WHERE id=:i")
                .setParameter("i", id).getSingleResult();

        assertThat((String) r[0]).isEqualTo("NEW");   // BUKAN RESOLVED
        assertThat(r[1]).isNull();
        assertThat(r[2]).isNull();                    // nota dalaman tidak ditulis
    }

    @Test
    @DisplayName("SP tanpa hak modul ditolak")
    void tanpaHakModul() {
        em.createNativeQuery("UPDATE sp_module SET status='ENDED' WHERE sp_code='SPA1'")
                .executeUpdate();
        em.flush();

        assertThatThrownBy(() -> cipta("Tiada hak", akaunA))
                .hasMessageContaining("belum dilanggan");
    }
}
