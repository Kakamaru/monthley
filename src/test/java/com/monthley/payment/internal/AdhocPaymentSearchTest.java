package com.monthley.payment.internal;

import com.monthley.ledger.internal.ChartOfAccountSeeder;
import com.monthley.shared.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Carian "Search Invoice" mesti menunjukkan nama PENERIMA untuk invois
 * adhoc, bukan nama akaun teknikal.
 *
 * Keputusan ini sudah dibuat untuk PDF — AdhocInvoiceTest mengunci
 * "nama PENERIMA, bukan 'Jualan Adhoc'". /outstanding tidak pernah
 * diselaraskan dengannya. Satu keputusan, dua tempat, satu ingat satu
 * lupa (guard 6).
 *
 * Endpoint ini ialah skrin yang kerani gunakan setiap hari untuk mencari
 * invois, dan sebelum ujian ini ia TIDAK PERNAH diuji melalui controller.
 * Itu sebabnya isu ini sampai ke UI dan ditemui dengan mata, bukan oleh
 * regresi.
 *
 * Ujian ini tidak menggunakan AdhocInvoiceService: billing.internal dan
 * payment.internal ialah modul berbeza dan tidak boleh saling capai
 * (Spring Modulith). Dokumen adhoc dibina terus dengan SQL — apa yang
 * diuji ialah query carian, bukan penciptaan.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AdhocPaymentSearchTest {

    private static final String SP = "SPAS";

    @Autowired ManualPaymentController controller;
    @Autowired ChartOfAccountSeeder seeder;
    @Autowired JdbcClient jdbc;
    @PersistenceContext EntityManager em;

    @BeforeEach
    void seed() {
        jdbc.sql("""
                INSERT IGNORE INTO service_provider (sp_code, name, status, version)
                VALUES (:sp, 'SP Ujian Carian Adhoc', 'ACTIVE', 0)
                """).param("sp", SP).update();
        seeder.seedFor(SP);
        jdbc.sql("""
                INSERT IGNORE INTO sp_document_setting
                  (sp_code, enable_manual_payment, version)
                VALUES (:sp, 1, 0)
                """).param("sp", SP).update();

        TenantContext.set(SP);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("clerk", "n/a",
                        java.util.List.of(new SimpleGrantedAuthority("SP_" + SP + "_CLERK"))));
    }

    @AfterEach
    void clear() { TenantContext.clear(); SecurityContextHolder.clearContext(); }

    private long akaun(String no, String nama, String jenis) {
        jdbc.sql("""
                INSERT INTO account (sp_code, account_no, account_name, account_type, status)
                VALUES (:sp, :no, :nm, :t, 'ACTIVE')
                """)
                .param("sp", SP).param("no", no).param("nm", nama)
                .param("t", jenis).update();
        return jdbc.sql("SELECT id FROM account WHERE sp_code=:sp AND account_no=:no")
                .param("sp", SP).param("no", no).query(Long.class).single();
    }

    /** Invois dengan atau tanpa issued_to_name. */
    private String invois(long akaunId, String penerima) {
        String no = "INV-" + System.nanoTime();
        jdbc.sql("""
                INSERT INTO financial_document
                  (sp_code, doc_no, doc_type, account_id, doc_date,
                   amount, tax_amount, status, title, currency, issued_to_name)
                VALUES (:sp, :no, 'INVOICE', :acc, '2026-08-01',
                        50.00, 0, 'ACTIVE', 'Invois', 'MYR', :nm)
                """)
                .param("sp", SP).param("no", no).param("acc", akaunId)
                .param("nm", penerima).update();
        em.flush();
        return no;
    }

    @Test
    @DisplayName("Invois adhoc: nama PENERIMA, bukan 'Jualan Adhoc'")
    void adhocTunjukNamaPenerima() {
        long adhoc = akaun("ADHOC-SALES", "Jualan Adhoc", "ADHOC");
        String no = invois(adhoc, "KAMAL AZMAN");

        var hasil = controller.outstanding(null, no, null, null, 0, 10);

        assertThat(hasil.items()).hasSize(1);
        assertThat(hasil.items().get(0).accountName())
                .as("kerani tidak dapat mengesahkan ia invois yang betul "
                    + "kalau setiap baris berbunyi 'Jualan Adhoc'")
                .isEqualTo("KAMAL AZMAN");
        assertThat(hasil.items().get(0).accountNo())
                .as("nombor akaun KEKAL — ia menandakan invois ini bukan "
                    + "pelanggan berdaftar, dan SP memahaminya")
                .isEqualTo("ADHOC-SALES");
    }

    @Test
    @DisplayName("Invois biasa: nama AKAUN kekal — cabang kedua COALESCE")
    void biasaTunjukNamaAkaun() {
        // Tanpa ujian ini hanya laluan adhoc diuji, dan invois biasa boleh
        // kehilangan namanya secara senyap.
        long acc = akaun("BIASA-1", "SITI PELANGGAN", null);
        String no = invois(acc, null);

        var hasil = controller.outstanding(null, no, null, null, 0, 10);

        assertThat(hasil.items()).hasSize(1);
        assertThat(hasil.items().get(0).accountName()).isEqualTo("SITI PELANGGAN");
    }

    @Test
    @DisplayName("issued_to_name KOSONG dilayan seperti tiada")
    void kosongJatuhKeNamaAkaun() {
        // NULLIF menangani rentetan kosong. Tanpanya baris akan memaparkan
        // ruang kosong dan bukan nama.
        long acc = akaun("BIASA-2", "AHMAD PELANGGAN", null);
        String no = invois(acc, "");

        var hasil = controller.outstanding(null, no, null, null, 0, 10);

        assertThat(hasil.items().get(0).accountName()).isEqualTo("AHMAD PELANGGAN");
    }
}
