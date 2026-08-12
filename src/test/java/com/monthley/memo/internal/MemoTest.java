package com.monthley.memo.internal;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Memo — peraturan tarikh dan penerbitan.
 *
 * Kegagalan di sini senyap: memo yang tarikh terbitnya ditulis semula
 * kelihatan baharu kepada pelanggan yang sudah membacanya, dan memo yang
 * luput tetapi 'diterbitkan' tidak muncul di mana-mana tanpa sebarang
 * ralat.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MemoTest {

    @Autowired MemoRepository memos;
    @PersistenceContext EntityManager em;

    @BeforeEach
    void setup() {
        em.createNativeQuery("""
            INSERT IGNORE INTO service_provider (sp_code, name, status,
                                                 created_at, updated_at, version)
            VALUES ('SPM1', 'Ujian Memo', 'ACTIVE', NOW(), NOW(), 0)
            """).executeUpdate();
        em.flush();
    }

    private MemoNotice cipta(String tajuk, LocalDate luput) {
        MemoNotice m = new MemoNotice("SPM1", tajuk, "Isi memo ujian.");
        m.setExpiresOn(luput);
        return memos.save(m);
    }

    @Test
    @DisplayName("memo baharu ialah DRAF — tidak terus kelihatan")
    void memoBaharuDraf() {
        MemoNotice m = cipta("Kerja penyelenggaraan", null);
        em.flush();

        assertThat(m.getStatus()).isEqualTo(MemoNotice.Status.DRAFT);
        assertThat(m.getPublishedAt()).isNull();
    }

    @Test
    @DisplayName("terbit menetapkan tarikh; terbit semula TIDAK menukarnya")
    void tarikhTerbitKekal() {
        MemoNotice m = cipta("Nombor telefon baharu", null);
        m.publish();
        em.flush();

        LocalDateTime asal = m.getPublishedAt();
        assertThat(asal).isNotNull();

        // Tarik balik, kemudian terbit semula.
        m.unpublish();
        m.publish();
        em.flush();

        // Tarikh KEKAL: pelanggan sudah melihat memo ini pada tarikh asal,
        // dan menulisnya semula menjadikannya kelihatan baharu kepada
        // orang yang sudah membacanya.
        assertThat(m.getPublishedAt()).isEqualTo(asal);
    }

    /**
     * Memo tanpa tarikh luput sentiasa aktif.
     *
     * Itu maksud NULL, dan sebab tarikh luput ialah per memo dan bukan
     * tetapan global: nombor telefon pengurusan tidak patut hilang selepas
     * 30 hari hanya kerana hebahan penyelenggaraan patut hilang.
     */
    @Test
    @DisplayName("memo tanpa tarikh luput kekal aktif")
    void tanpaLuputKekalAktif() {
        MemoNotice m = cipta("Waktu operasi pejabat", null);
        m.publish();
        em.flush();

        Number aktif = (Number) em.createNativeQuery("""
                SELECT COUNT(*) FROM memo_notice
                WHERE  id = :id AND status = 'PUBLISHED'
                  AND (expires_on IS NULL OR expires_on >= CURDATE())
                """).setParameter("id", m.getId()).getSingleResult();

        assertThat(aktif.intValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("memo dengan tarikh lepas dikira LAMA")
    void tarikhLepasJadiLama() {
        MemoNotice m = cipta("Mesyuarat AGM lepas", LocalDate.now().minusDays(1));
        m.publish();
        em.flush();

        Number lama = (Number) em.createNativeQuery("""
                SELECT COUNT(*) FROM memo_notice
                WHERE  id = :id AND expires_on IS NOT NULL AND expires_on < CURDATE()
                """).setParameter("id", m.getId()).getSingleResult();

        assertThat(lama.intValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("memo hari luput terakhir masih aktif")
    void hariLuputMasihAktif() {
        // Sempadan: luput HARI INI bermakna masih kelihatan hari ini.
        // '<' dan bukan '<=' — memo yang menyatakan 'sehingga 20 Ogos'
        // patut kelihatan pada 20 Ogos.
        MemoNotice m = cipta("Bayaran sehingga hari ini", LocalDate.now());
        m.publish();
        em.flush();

        Number aktif = (Number) em.createNativeQuery("""
                SELECT COUNT(*) FROM memo_notice
                WHERE  id = :id AND (expires_on IS NULL OR expires_on >= CURDATE())
                """).setParameter("id", m.getId()).getSingleResult();

        assertThat(aktif.intValue()).isEqualTo(1);
    }
}
