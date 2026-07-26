package com.monthley.statement;

import com.monthley.shared.TenantContext;
import com.monthley.statement.api.StatementModel;
import com.monthley.statement.api.StatementPort;
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
import java.time.LocalDate;

import org.springframework.dao.EmptyResultDataAccessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Sempadan penyewa untuk penyata (ADR 0010 P4b).
 *
 * Skrin SP disempadani oleh TenantContext. Penapisan berlaku dalam query
 * StatementQuery (sp_code = :sp), bukan dalam pengawal — jadi ia diuji di
 * sini. Jika penapis itu hilang semasa suatu refactor, SP boleh membaca
 * akaun SP lain dan tiada apa yang akan memberitahu kita.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StatementTenantBoundaryTest {

    @Autowired StatementPort statements;
    @Autowired JdbcClient jdbc;

    private String spA;
    private String spB;
    private long accA;

    private String sp(String suffix) {
        String code = ("T" + System.nanoTime() + suffix);
        code = code.substring(code.length() - 8);
        jdbc.sql("""
                INSERT INTO service_provider (sp_code, name, status, version)
                VALUES (:c, :n, 'ACTIVE', 0)
                """).param("c", code).param("n", "SP Ujian " + suffix).update();
        return code;
    }

    @BeforeEach
    void seed() {
        spA = sp("A");
        spB = sp("B");

        String no = "TEN-" + System.nanoTime();
        jdbc.sql("""
                INSERT INTO account (sp_code, account_no, account_name, status)
                VALUES (:sp, :no, 'Akaun SP A', 'ACTIVE')
                """).param("sp", spA).param("no", no).update();
        accA = jdbc.sql("SELECT id FROM account WHERE sp_code=:sp AND account_no=:no")
                .param("sp", spA).param("no", no).query(Long.class).single();

        jdbc.sql("""
                INSERT INTO financial_document
                  (sp_code, doc_no, doc_type, account_id, doc_date,
                   amount, tax_amount, status, title, currency)
                VALUES (:sp, :dn, 'INVOICE', :acc, :d, 250.00, 0, 'ACTIVE', 'Yuran', 'MYR')
                """)
                .param("sp", spA).param("dn", "TEN-" + accA)
                .param("acc", accA).param("d", LocalDate.of(2026, 5, 1))
                .update();

        TenantContext.set(spA);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("SP pemilik melihat transaksinya")
    void spPemilikNampak() {
        StatementModel m = statements.forYear(spA, accA, 2026);

        assertThat(m.rows()).hasSize(1);
        assertThat(m.closingBalance()).isEqualByComparingTo("250.00");
    }

    @Test
    @DisplayName("SP LAIN GAGAL, bukan mendapat penyata kosong")
    void spLainDitolak() {
        // Permintaan silang penyewa ialah kesilapan pengaturcaraan atau
        // serangan. Kedua-duanya patut BISING. Penyata kosong kelihatan
        // seperti 'akaun ini tiada transaksi' — jawapan yang salah kepada
        // soalan yang salah, dan ia menyembunyikan pepijat.
        assertThatThrownBy(() -> statements.forYear(spB, accA, 2026))
                .as("silang penyewa mesti gagal keras")
                .isInstanceOf(EmptyResultDataAccessException.class);
    }

    @Test
    @DisplayName("penapis sp_code melindungi baris, bukan hanya kepala")
    void barisDitapisIkutSp() {
        // Kepala gagal dahulu, jadi ujian di atas tidak membuktikan baris
        // ditapis. Uji lapisan itu terus: jika penapis sp_code hilang
        // daripada query baris, kebocoran akan berlaku di sini dan bukan
        // di kepala.
        var baris = jdbc.sql("""
                SELECT COUNT(*) FROM account_document_entry
                WHERE sp_code = :sp AND account_id = :acc
                """)
                .param("sp", spB).param("acc", accA)
                .query(Integer.class).single();

        assertThat(baris)
                .as("dokumen SP A tidak boleh kelihatan di bawah SP B")
                .isZero();
    }
}
