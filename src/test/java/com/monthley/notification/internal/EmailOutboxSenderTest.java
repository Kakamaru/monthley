package com.monthley.notification.internal;

import com.monthley.notification.api.EmailOutboxPort;
import com.monthley.notification.api.EmailOutboxPort.Kind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mekanik gilir — transaksi per-baris, cuba semula, had percubaan
 * (ADR 0014 P1b).
 *
 * TIADA @Transactional pada kelas ini. Dispatcher menggunakan
 * REQUIRES_NEW, dan transaksi ujian yang membungkus semuanya akan
 * menyembunyikan sama ada setiap baris benar-benar di-commit
 * berasingan — iaitu satu-satunya sifat yang penting di sini.
 *
 * Kelas ini duduk dalam pakej `internal` kerana EmailOutboxRenderer
 * package-private, dan mengujinya memerlukan mock.
 */
@SpringBootTest
@ActiveProfiles("test")
class EmailOutboxSenderTest {

    private static final String SP = "SPSN";

    @Autowired EmailOutboxPort outbox;
    @Autowired EmailOutboxDispatcher dispatcher;
    @Autowired JdbcClient jdbc;

    /** Renderer dimock: ujian ini tentang MEKANIK, bukan kandungan. */
    @MockitoBean EmailOutboxRenderer renderer;

    @Value("${monthley.outbox.max-attempts:5}") int maxAttempts;

    private long beratur(String ref) {
        outbox.queue(SP, Kind.STATEMENT, ref, "a@contoh.com", null, null);
        return jdbc.sql("SELECT id FROM email_outbox WHERE sp_code=:sp AND ref_key=:ref")
                .param("sp", SP).param("ref", ref).query(Long.class).single();
    }

    private String[] baris(long id) {
        return jdbc.sql("""
                SELECT status, attempts, last_error,
                       CASE WHEN sent_at IS NULL THEN 'NULL' ELSE 'ADA' END
                FROM   email_outbox WHERE id = :id
                """).param("id", id)
                .query((rs, n) -> new String[]{
                        rs.getString(1), rs.getString(2),
                        rs.getString(3), rs.getString(4)})
                .single();
    }

    private void buang(long id) {
        jdbc.sql("DELETE FROM email_outbox WHERE id = :id").param("id", id).update();
    }

    @Test
    @DisplayName("Render berjaya: SENT, sent_at diisi")
    void berjayaTandaSent() {
        long id = beratur("sn-ok-" + System.nanoTime());
        try {
            Mockito.doNothing().when(renderer).render(Mockito.any());

            assertThat(dispatcher.hantarSatu(id)).isTrue();

            var r = baris(id);
            assertThat(r[0]).isEqualTo("SENT");
            assertThat(r[1]).isEqualTo("1");
            assertThat(r[2]).isNull();
            assertThat(r[3]).as("sent_at direkod").isEqualTo("ADA");
        } finally {
            buang(id);
        }
    }

    @Test
    @DisplayName("Render gagal SEKALI: kekal PENDING untuk dicuba semula")
    void gagalSekaliKekalPending() {
        // Kegagalan sementara — penyedia tunggang, had kadar — mesti
        // dicuba semula. FAILED bermakna berhenti mencuba, bukan "gagal
        // sekali".
        long id = beratur("sn-gagal-" + System.nanoTime());
        try {
            Mockito.doThrow(new RuntimeException("penyedia tunggang"))
                    .when(renderer).render(Mockito.any());

            assertThat(dispatcher.hantarSatu(id)).isFalse();

            var r = baris(id);
            assertThat(r[0]).as("belum menyerah").isEqualTo("PENDING");
            assertThat(r[1]).isEqualTo("1");
            assertThat(r[2]).contains("penyedia tunggang");
            assertThat(r[3]).isEqualTo("NULL");
        } finally {
            buang(id);
        }
    }

    @Test
    @DisplayName("Gagal sehingga HAD percubaan: FAILED, berhenti mencuba")
    void gagalSehinggaHadJadiFailed() {
        long id = beratur("sn-had-" + System.nanoTime());
        try {
            Mockito.doThrow(new RuntimeException("alamat tidak sah"))
                    .when(renderer).render(Mockito.any());

            for (int i = 0; i < maxAttempts; i++) {
                dispatcher.hantarSatu(id);
            }

            var r = baris(id);
            assertThat(r[0]).isEqualTo("FAILED");
            assertThat(r[1]).isEqualTo(String.valueOf(maxAttempts));

            // Baris FAILED tidak diambil lagi — tanpa ini alamat yang
            // tidak sah dicuba selama-lamanya dan menyekat gilir.
            assertThat(dispatcher.hantarSatu(id)).isFalse();
            assertThat(baris(id)[1])
                    .as("cubaan tidak bertambah selepas FAILED")
                    .isEqualTo(String.valueOf(maxAttempts));
        } finally {
            buang(id);
        }
    }

    @Test
    @DisplayName("Baris yang sudah SENT tidak dihantar semula")
    void sentTidakDiulang() {
        long id = beratur("sn-ulang-" + System.nanoTime());
        try {
            Mockito.doNothing().when(renderer).render(Mockito.any());
            dispatcher.hantarSatu(id);

            assertThat(dispatcher.hantarSatu(id))
                    .as("dua larian bertindih tidak boleh menghantar dua kali")
                    .isFalse();
            assertThat(baris(id)[1]).isEqualTo("1");
        } finally {
            buang(id);
        }
    }
}
