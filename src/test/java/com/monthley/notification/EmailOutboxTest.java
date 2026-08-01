package com.monthley.notification;

import com.monthley.notification.api.EmailOutboxPort;
import com.monthley.notification.api.EmailOutboxPort.Kind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Baris gilir penghantaran (ADR 0014, V55).
 *
 * Legacy menulis DUA kali untuk satu peristiwa — DB dan Hazelcast —
 * dengan catch yang hanya log. Di sini satu tulisan, dan idempotensi
 * dijaga oleh kekangan DB, bukan oleh semakan-lalu-sisip yang
 * membiarkan lubang perlumbaan.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class EmailOutboxTest {

    private static final String SP = "SPOB";

    @Autowired EmailOutboxPort outbox;
    @Autowired JdbcClient jdbc;

    private Map<String, String> params() {
        var m = new LinkedHashMap<String, String>();
        m.put("p_acc_no", "42");
        m.put("p_period", "2026230700");
        return m;
    }

    private String rujukan() { return "acc-" + System.nanoTime(); }

    private long bilangan(String ref) {
        return jdbc.sql("SELECT COUNT(*) FROM email_outbox WHERE sp_code=:sp AND ref_key=:ref")
                .param("sp", SP).param("ref", ref).query(Long.class).single();
    }

    @Test
    @DisplayName("Baris diberatur dengan dua alamat dan dua params")
    void beraturSatuBaris() {
        String ref = rujukan();

        assertThat(outbox.queue(SP, Kind.STATEMENT, ref,
                "utama@contoh.com", "kedua@contoh.com", params())).isTrue();

        var r = jdbc.sql("""
                SELECT to_email, cc_email, param1_key, param1_val,
                       param2_key, param2_val, status, attempts, channel
                FROM   email_outbox WHERE sp_code = :sp AND ref_key = :ref
                """).param("sp", SP).param("ref", ref)
                .query((rs, n) -> new String[]{
                        rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getString(7), rs.getString(8), rs.getString(9)})
                .single();

        assertThat(r[0]).isEqualTo("utama@contoh.com");
        assertThat(r[1]).isEqualTo("kedua@contoh.com");
        assertThat(r[2]).isEqualTo("p_acc_no");
        assertThat(r[3]).isEqualTo("42");
        assertThat(r[4]).isEqualTo("p_period");
        assertThat(r[5]).isEqualTo("2026230700");
        assertThat(r[6]).as("belum dihantar").isEqualTo("PENDING");
        assertThat(r[7]).isEqualTo("0");
        assertThat(r[8]).isEqualTo("EMAIL");
    }

    @Test
    @DisplayName("Beratur DUA KALI untuk rujukan sama: baris kedua ditolak")
    void penduaDitolak() {
        // Larian kedua bagi tempoh yang sama ialah laluan NORMAL, bukan
        // ralat. Pelanggan tidak sepatutnya menerima penyata dua kali
        // kerana kerani menekan Jana Bil sekali lagi.
        String ref = rujukan();

        assertThat(outbox.queue(SP, Kind.STATEMENT, ref, "a@contoh.com", null, params()))
                .isTrue();
        assertThat(outbox.queue(SP, Kind.STATEMENT, ref, "a@contoh.com", null, params()))
                .as("kekangan DB, bukan semakan-lalu-sisip yang ada lubang perlumbaan")
                .isFalse();

        assertThat(bilangan(ref)).isEqualTo(1L);
    }

    @Test
    @DisplayName("Rujukan sama, JENIS berbeza: dua baris")
    void jenisBerbezaDuaBaris() {
        // Akaun yang sama boleh menerima penyata DAN peringatan untuk
        // tempoh yang sama. Kekangan pada (sp, kind, ref), bukan (sp, ref).
        String ref = rujukan();

        assertThat(outbox.queue(SP, Kind.STATEMENT, ref, "a@contoh.com", null, null)).isTrue();
        assertThat(outbox.queue(SP, Kind.REMINDER, ref, "a@contoh.com", null, null)).isTrue();

        assertThat(bilangan(ref)).isEqualTo(2L);
    }

    @Test
    @DisplayName("Tiada alamat: dilangkau, bukan ralat")
    void tiadaAlamatDilangkau() {
        // SP boleh mempunyai akaun tanpa e-mel — bil diserahkan sendiri.
        // Itu bukan kegagalan yang perlu dilaporkan.
        String ref = rujukan();

        assertThat(outbox.queue(SP, Kind.STATEMENT, ref, null, null, params())).isFalse();
        assertThat(outbox.queue(SP, Kind.STATEMENT, ref, "  ", null, params())).isFalse();

        assertThat(bilangan(ref)).isZero();
    }

    @Test
    @DisplayName("Lebih daripada dua params: MELONTAR, bukan potong senyap")
    void paramsLebihDuaDitolak() {
        // Params ketiga yang hilang bermakna e-mel dihantar dengan
        // maklumat kurang dan tiada apa yang menjerit.
        var tiga = new LinkedHashMap<String, String>();
        tiga.put("a", "1");
        tiga.put("b", "2");
        tiga.put("c", "3");

        assertThatThrownBy(() -> outbox.queue(SP, Kind.STATEMENT, rujukan(),
                "a@contoh.com", null, tiga))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("paling banyak 2");
    }
}
