package com.monthley.document.internal;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Penomboran dokumen membaca tetapan SP (ADR 0012, CASE-008 kes 5).
 *
 * SP0002 menetapkan prefix I26 dan R26; dokumen keluar sebagai INV000099
 * dan RCP000034. Tetapan tidak pernah dibaca — document_number_sequence
 * mempunyai lajur prefix dan padding sendiri.
 *
 * Ujian mengikut piawai CASE-008: membuktikan menukar tetapan MENGUBAH
 * tingkah laku.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DocumentNumberTest {

    private static final String SP = "SPNO";

    @Autowired DocumentNumberService numbers;
    @Autowired JdbcClient jdbc;
    @PersistenceContext EntityManager em;

    @BeforeEach
    void seed() {
        jdbc.sql("""
                INSERT IGNORE INTO service_provider (sp_code, name, status, version)
                VALUES (:sp, 'SP Ujian Nombor', 'ACTIVE', 0)
                """).param("sp", SP).update();
        jdbc.sql("DELETE FROM document_number_sequence WHERE sp_code = :sp")
                .param("sp", SP).update();
        jdbc.sql("DELETE FROM sp_document_setting WHERE sp_code = :sp")
                .param("sp", SP).update();
    }

    private void tetapan(String prefix, int saiz, long mula) {
        jdbc.sql("""
                INSERT INTO sp_document_setting
                  (sp_code, invoice_prefix, invoice_no_size, invoice_no_start, version)
                VALUES (:sp, :p, :s, :m, 0)
                ON DUPLICATE KEY UPDATE
                  invoice_prefix = :p, invoice_no_size = :s, invoice_no_start = :m
                """)
                .param("sp", SP).param("p", prefix).param("s", saiz).param("m", mula)
                .update();
        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("prefix dan saiz datang daripada TETAPAN, bukan lalai")
    void ikutTetapan() {
        tetapan("I26", 7, 1);

        assertThat(numbers.next(SP, "INVOICE"))
                .as("SP menetapkan I26 dan 7 digit; INV000001 bermakna "
                    + "tetapan diabaikan")
                .isEqualTo("I260000001");
        assertThat(numbers.next(SP, "INVOICE")).isEqualTo("I260000002");
    }

    @Test
    @DisplayName("no_start dihormati — SP boleh mula dari nombor lain")
    void mulaDariNomborLain() {
        tetapan("I26", 6, 1001);

        assertThat(numbers.next(SP, "INVOICE")).isEqualTo("I26001001");
        assertThat(numbers.next(SP, "INVOICE")).isEqualTo("I26001002");
    }

    @Test
    @DisplayName("prefix BERUBAH: turutan reset ke no_start")
    void prefixBerubahReset() {
        tetapan("INV", 6, 1);
        numbers.next(SP, "INVOICE");
        numbers.next(SP, "INVOICE");
        assertThat(numbers.next(SP, "INVOICE")).isEqualTo("INV000003");

        tetapan("I27", 6, 1);

        assertThat(numbers.next(SP, "INVOICE"))
                .as("prefix menandakan tempoh; menukarnya bermakna kitaran "
                    + "baharu bermula, bukan menyambung dari 4")
                .isEqualTo("I27000001");
        assertThat(numbers.next(SP, "INVOICE")).isEqualTo("I27000002");
    }

    @Test
    @DisplayName("prefix SAMA: turutan berterusan, tidak reset")
    void prefixSamaBerterusan() {
        tetapan("I26", 6, 1);
        numbers.next(SP, "INVOICE");
        numbers.next(SP, "INVOICE");

        tetapan("I26", 6, 1);

        assertThat(numbers.next(SP, "INVOICE"))
                .as("menyimpan tetapan tidak sepatutnya mengulang nombor")
                .isEqualTo("I26000003");
    }

    @Test
    @DisplayName("SP tanpa tetapan mendapat lalai — tingkah laku semasa kekal")
    void tanpaTetapanGunaLalai() {
        assertThat(numbers.next(SP, "INVOICE")).isEqualTo("INV000001");
        assertThat(numbers.next(SP, "RECEIPT")).isEqualTo("RCP000001");
        assertThat(numbers.next(SP, "CREDIT_NOTE")).isEqualTo("CN000001");
        assertThat(numbers.next(SP, "DEBIT_NOTE")).isEqualTo("DN000001");
    }

    @Test
    @DisplayName("nota kredit/debit tidak terjejas oleh tetapan invois")
    void notaTidakIkutTetapanInvois() {
        tetapan("I26", 7, 500);

        assertThat(numbers.next(SP, "INVOICE")).isEqualTo("I260000500");
        assertThat(numbers.next(SP, "CREDIT_NOTE"))
                .as("nota ialah pelarasan yang jarang; tiada tetapan untuknya")
                .isEqualTo("CN000001");
    }

    @Test
    @DisplayName("tukar prefix BALIK: nombor sedia ada dilangkau, tiada pendua")
    void tukarBalikTiadaPendua() {
        tetapan("INV", 6, 1);
        String a = numbers.next(SP, "INVOICE");   // INV000001
        String b = numbers.next(SP, "INVOICE");   // INV000002
        em.flush();

        // Dokumen sebenar wujud dengan nombor itu.
        for (String no : new String[]{a, b}) {
            jdbc.sql("""
                    INSERT INTO financial_document
                      (sp_code, doc_no, doc_type, doc_date, amount, tax_amount,
                       status, title, currency)
                    VALUES (:sp, :no, 'INVOICE', '2026-01-01', 10.00, 0,
                            'ACTIVE', 'Ujian', 'MYR')
                    """).param("sp", SP).param("no", no).update();
        }
        em.flush();
        em.clear();

        // SP tersilap taip prefix, kemudian membetulkannya.
        tetapan("I27", 6, 1);
        numbers.next(SP, "INVOICE");
        tetapan("INV", 6, 1);

        assertThat(numbers.next(SP, "INVOICE"))
                .as("reset ke 1 akan menghasilkan INV000001 yang SUDAH wujud; "
                    + "nombor yang digunakan mesti dilangkau")
                .isEqualTo("INV000003");
    }

    @Test
    @DisplayName("prefix kosong jatuh ke lalai, bukan menghasilkan '000001'")
    void prefixKosongJatuhKeLalai() {
        tetapan("", 6, 1);
        assertThat(numbers.next(SP, "INVOICE")).isEqualTo("INV000001");
    }

    @Test
    @DisplayName("saiz tidak munasabah jatuh ke 6")
    void saizTidakMunasabah() {
        tetapan("I26", 99, 1);
        assertThat(numbers.next(SP, "INVOICE"))
                .as("String.format dengan lebar 99 menghasilkan nombor yang "
                    + "tidak boleh dibaca")
                .isEqualTo("I26000001");
    }
}
