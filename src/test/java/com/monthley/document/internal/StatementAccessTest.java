package com.monthley.document.internal;

import com.monthley.document.api.StatementAccessPort;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Token capaian penyata (V57, ADR 0014 P3).
 *
 * Pelanggan yang menerima e-mel penyata mungkin tiada akaun portal.
 * Pautan mesti berfungsi tanpa log masuk, dan token ialah
 * satu-satunya kawalan capaian.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StatementAccessTest {

    private static final String SP = "SPST";

    @Autowired StatementAccessPort access;
    @PersistenceContext EntityManager em;

    private long akaun;

    @BeforeEach
    void seed() {
        em.createNativeQuery("""
                INSERT IGNORE INTO service_provider (sp_code, name, status, version)
                VALUES (:sp, 'SP Ujian Token Penyata', 'ACTIVE', 0)
                """).setParameter("sp", SP).executeUpdate();

        String no = "ST-" + System.nanoTime();
        em.createNativeQuery("""
                INSERT INTO account (sp_code, account_no, account_name, status)
                VALUES (:sp, :no, :no, 'ACTIVE')
                """).setParameter("sp", SP).setParameter("no", no).executeUpdate();
        akaun = ((Number) em.createNativeQuery(
                "SELECT id FROM account WHERE sp_code=:sp AND account_no=:no")
                .setParameter("sp", SP).setParameter("no", no)
                .getSingleResult()).longValue();
        em.flush();

        TenantContext.set(SP);
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    private Object[] baris(String token) {
        return (Object[]) em.createNativeQuery(
                "SELECT view_count, first_seen_at, last_seen_at "
                + "FROM statement_access_token WHERE token = :t")
                .setParameter("t", token).getSingleResult();
    }

    @Test
    @DisplayName("Token SAMA dipulangkan untuk (akaun, tahun) yang sama")
    void tokenSamaTidakBerulang() {
        // Penyata dihantar setiap kali bil dijana — dua belas kali
        // setahun untuk akaun bulanan. Token per PENGHANTARAN bermakna
        // sepuluh ribu akaun menghasilkan seratus dua puluh ribu baris
        // setahun untuk mengakses data yang sama.
        //
        // Legacy menghasilkan UUID baharu setiap penghantaran; itu kesan
        // sampingan daripada menggunakan dokumen hantu (CASE-006).
        String a = access.tokenFor(SP, akaun, 2026);
        String b = access.tokenFor(SP, akaun, 2026);

        assertThat(a).isEqualTo(b);
        assertThat(em.createNativeQuery(
                "SELECT COUNT(*) FROM statement_access_token WHERE account_id = :a")
                .setParameter("a", akaun).getSingleResult())
                .as("satu baris, bukan dua")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("Tahun berbeza: token berbeza")
    void tahunBerbezaTokenBerbeza() {
        // Penyata 2025 dan 2026 ialah dokumen berbeza. Satu token untuk
        // kedua-duanya bermakna pelanggan yang membuka pautan lama
        // melihat tahun yang salah.
        assertThat(access.tokenFor(SP, akaun, 2025))
                .isNotEqualTo(access.tokenFor(SP, akaun, 2026));
    }

    @Test
    @DisplayName("resolve memulangkan sp, akaun, tahun — dan merekod paparan")
    void resolveMerekodPaparan() {
        // SP boleh menjawab "adakah pelanggan pernah membuka penyata
        // ini". Legacy tidak boleh.
        String t = access.tokenFor(SP, akaun, 2026);
        em.flush();

        var r = access.resolve(t).orElseThrow();
        em.flush();

        assertThat(r.spCode()).isEqualTo(SP);
        assertThat(r.accountId()).isEqualTo(akaun);
        assertThat(r.year()).isEqualTo(2026);

        var b = baris(t);
        assertThat(((Number) b[0]).intValue()).isEqualTo(1);
        assertThat(b[1]).as("first_seen_at").isNotNull();
        assertThat(b[2]).as("last_seen_at").isNotNull();
    }

    @Test
    @DisplayName("Token dibatalkan: resolve KOSONG, sama seperti tidak wujud")
    void revokeMenutupPautan() {
        // Pemanggil tidak boleh membezakan "dibatalkan" daripada "tidak
        // wujud" — kalau boleh, penyerang mengesahkan token mana pernah
        // wujud.
        String t = access.tokenFor(SP, akaun, 2026);
        em.flush();

        access.revoke(akaun, 2026);
        em.flush();

        assertThat(access.resolve(t)).isEmpty();
        assertThat(access.resolve("tiada-token-begini")).isEmpty();
    }

    @Test
    @DisplayName("Token ialah 43 aksara base64url — 256 bit entropi")
    void tokenCukupPanjang() {
        // Keselamatan bergantung SEPENUHNYA pada token: tiada log masuk,
        // tiada JWT. UUID legacy memberi kira-kira 122 bit dan
        // membocorkan masa penciptaan pada sesetengah versi.
        String t = access.tokenFor(SP, akaun, 2026);

        assertThat(t).hasSize(43).matches("[A-Za-z0-9_-]+");
    }
}
