package com.monthley.statement;

import com.monthley.ledger.internal.ChartOfAccountSeeder;
import com.monthley.statement.api.StatementPort;
import com.monthley.statement.api.StatementRenderPort;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Berapa besar penyata boleh menjadi sebelum render segerak menyakitkan?
 *
 * ADR 0010 P6 mencadangkan ambang baris dan laluan tak segerak. Untuk
 * memilih ambang, kita perlukan pengukuran — bukan tekaan.
 *
 * Data pengeluaran hari ini: penyata TERBESAR ialah 32 dokumen dengan 21
 * padanan. Sasaran ADR ialah 20-40 baris setahun. Kita berada tepat
 * dalam julat itu, jadi ambang menyelesaikan masalah yang belum wujud.
 *
 * Ujian ini menjana data sintetik yang jauh lebih besar dan mengukur.
 * Jika 1000 baris dirender dalam masa yang boleh diterima, P6 boleh
 * ditangguh dengan yakin dan bukan dengan harapan.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StatementPerformanceTest {

    private static final String SP = "SPPF";

    @Autowired StatementPort statements;
    @Autowired StatementRenderPort renderer;
    @Autowired ChartOfAccountSeeder seeder;
    @Autowired JdbcClient jdbc;
    @PersistenceContext EntityManager em;

    private long acc;

    @BeforeEach
    void seed() {
        jdbc.sql("""
                INSERT IGNORE INTO service_provider (sp_code, name, status, version)
                VALUES (:sp, 'SP Ujian Prestasi', 'ACTIVE', 0)
                """).param("sp", SP).update();
        seeder.seedFor(SP);
        jdbc.sql("INSERT IGNORE INTO sp_document_setting (sp_code, version) VALUES (:sp, 0)")
                .param("sp", SP).update();
        jdbc.sql("""
                INSERT IGNORE INTO sp_billing_setting (sp_code, currency, language, version)
                VALUES (:sp, 'MYR', 'ms', 0)
                """).param("sp", SP).update();

        String no = "PF-" + System.nanoTime();
        jdbc.sql("""
                INSERT INTO account (sp_code, account_no, account_name, billto_name, status)
                VALUES (:sp, :no, 'Ujian Prestasi', 'Ujian Prestasi', 'ACTIVE')
                """).param("sp", SP).param("no", no).update();
        acc = jdbc.sql("SELECT id FROM account WHERE sp_code=:sp AND account_no=:no")
                .param("sp", SP).param("no", no).query(Long.class).single();
    }

    /** Dokumen mentah — memintas enjin bil supaya volum boleh dikawal. */
    private void janaDokumen(int bilangan) {
        for (int i = 1; i <= bilangan; i++) {
            LocalDate d = LocalDate.of(2026, 1, 1).plusDays(i % 360);
            jdbc.sql("""
                    INSERT INTO financial_document
                      (sp_code, doc_no, doc_type, account_id, doc_date,
                       amount, tax_amount, status, title, currency)
                    VALUES (:sp, :no, 'INVOICE', :acc, :d,
                            50.00, 0, 'ACTIVE', 'Yuran', 'MYR')
                    """)
                    .param("sp", SP)
                    .param("no", "PF-INV-" + i)
                    .param("acc", acc)
                    .param("d", d)
                    .update();
        }
        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("UKURAN: 1000 baris — query dan render")
    void ukurSeribuBaris() {
        janaDokumen(1000);

        long t0 = System.nanoTime();
        var m = statements.forYear(SP, acc, 2026);
        long msQuery = (System.nanoTime() - t0) / 1_000_000;

        long t1 = System.nanoTime();
        byte[] pdf = renderer.renderPdf(m);
        long msPdf = (System.nanoTime() - t1) / 1_000_000;

        long t2 = System.nanoTime();
        byte[] xlsx = renderer.renderXlsx(m);
        long msXlsx = (System.nanoTime() - t2) / 1_000_000;

        System.out.printf(
                "%n=== PRESTASI PENYATA ===%n"
                + "  baris     : %d%n"
                + "  query     : %d ms%n"
                + "  render PDF: %d ms  (%d KB)%n"
                + "  render XLSX: %d ms  (%d KB)%n"
                + "  JUMLAH PDF: %d ms%n%n",
                m.rows().size(), msQuery, msPdf, pdf.length / 1024,
                msXlsx, xlsx.length / 1024, msQuery + msPdf);

        assertThat(m.rows()).hasSize(1000);

        // Sasaran ADR 0010: query bawah 50ms, render bawah satu saat.
        // Ini pada 1000 baris — dua puluh kali ganda penyata terbesar
        // pengeluaran hari ini.
        assertThat(msQuery + msPdf)
                .as("jika ini melebihi beberapa saat, laluan tak segerak (P6) "
                    + "diperlukan; jika tidak, ia menyelesaikan masalah yang "
                    + "belum wujud")
                .isLessThan(10_000);
    }
}
