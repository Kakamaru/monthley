package com.monthley.account.internal;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kebenaran penyata portal (ADR 0010 P4b).
 *
 * StatementService tidak tahu siapa pemanggilnya dan tidak boleh
 * menguatkuasakan apa-apa. Semakan pemilikan tinggal di pengawal, jadi
 * ia mesti diuji DI SINI — bukan diandaikan.
 *
 * Akaun orang lain mengembalikan 404 dan bukan 403: 403 membenarkan
 * penyerang membilang ID untuk mengetahui akaun mana wujud.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MyStatementAccessTest {

    @Autowired AccountController controller;
    @Autowired JdbcClient jdbc;
    @PersistenceContext EntityManager em;

    /** Pengguna SEBENAR: payer_user_id ialah FK kepada app_user. */
    private long pembayarA;
    private long pembayarB;
    private long accA;
    private long accB;

    private long pengguna(String nama) {
        String email = "acl-" + System.nanoTime() + "@ujian.local";
        jdbc.sql("""
                INSERT INTO app_user (email, full_name, status, version)
                VALUES (:e, :n, 'ACTIVE', 0)
                """)
                .param("e", email).param("n", nama)
                .update();
        return jdbc.sql("SELECT id FROM app_user WHERE email = :e")
                .param("e", email).query(Long.class).single();
    }

    private long akaun(String sp, long payer) {
        String no = "STMT-ACL-" + System.nanoTime();
        jdbc.sql("""
                INSERT INTO account (sp_code, account_no, account_name,
                                     payer_user_id, status)
                VALUES (:sp, :no, 'Ujian Akses Penyata', :payer, 'ACTIVE')
                """)
                .param("sp", sp).param("no", no).param("payer", payer)
                .update();
        long id = jdbc.sql("SELECT id FROM account WHERE sp_code=:sp AND account_no=:no")
                .param("sp", sp).param("no", no).query(Long.class).single();
        jdbc.sql("""
                INSERT INTO financial_document
                  (sp_code, doc_no, doc_type, account_id, doc_date,
                   amount, tax_amount, status, title, currency)
                VALUES (:sp, :dn, 'INVOICE', :acc, :d, 100.00, 0, 'ACTIVE', 'Yuran', 'MYR')
                """)
                .param("sp", sp).param("dn", "ACL-" + id)
                .param("acc", id).param("d", LocalDate.of(2026, 3, 1))
                .update();
        return id;
    }

    private void masukSebagai(long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        String.valueOf(userId), "n/a", List.of()));
    }

    @BeforeEach
    void seed() {
        // SP sendiri, bukan yang kebetulan wujud. Meminjam SP pertama
        // dalam jadual menjadikan keputusan ujian bergantung pada data
        // yang ada — dan gagal sepenuhnya dalam DB kosong.
        jdbc.sql("""
                INSERT IGNORE INTO service_provider (sp_code, name, status, version)
                VALUES (:sp, 'SP Ujian Akses', 'ACTIVE', 0)
                """).param("sp", "SACL").update();
        String sp = "SACL";
        pembayarA = pengguna("Pembayar A");
        pembayarB = pengguna("Pembayar B");
        accA = akaun(sp, pembayarA);
        accB = akaun(sp, pembayarB);
        masukSebagai(pembayarA);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("pembayar boleh memuat turun penyata akaunnya sendiri")
    void akaunSendiriDibenarkan() {
        var res = controller.myStatement(accA, 2026);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isNotNull();
        assertThat(new String(res.getBody(), 0, 5)).startsWith("%PDF");
        assertThat(res.getHeaders().getFirst("Content-Disposition"))
                .contains("penyata-");
    }

    @Test
    @DisplayName("akaun pembayar LAIN mengembalikan 404, bukan PDF dan bukan 403")
    void akaunOrangLainDitolak() {
        var res = controller.myStatement(accB, 2026);

        assertThat(res.getStatusCode())
                .as("403 membenarkan penyerang membilang ID akaun")
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody()).isNull();
    }

    @Test
    @DisplayName("akaun tidak wujud memberi respons SAMA dengan akaun orang lain")
    void tidakWujudSamaSepertiDitolak() {
        var lain = controller.myStatement(accB, 2026);
        var hantu = controller.myStatement(99999999L, 2026);

        assertThat(hantu.getStatusCode())
                .as("respons berbeza akan mendedahkan akaun mana wujud")
                .isEqualTo(lain.getStatusCode());
    }
}
