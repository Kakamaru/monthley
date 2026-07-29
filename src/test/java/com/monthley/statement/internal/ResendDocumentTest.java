package com.monthley.statement.internal;

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

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Resend Document.
 *
 * Duduk dalam statement, bukan document: modul document memiliki DATA
 * dokumen dan tidak tahu bagaimana ia dirender atau dihantar.
 * Percubaan pertama meletakkannya di sana dan ModularityTests menolaknya
 * — statement sudah bergantung pada document::api untuk pautan awam,
 * jadi document -> statement ialah kitaran.
 *
 * Alamat datang daripada PERMINTAAN, bukan akaun: alamat pada akaun
 * mungkin salah, atau pelanggan mahu salinan ke alamat kedua. Dialog
 * membenarkan kerani menambah alamat ('Note: Email can be one or more').
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ResendDocumentTest {

    private static final String SP = "SPRS";

    @Autowired StatementController controller;
    @Autowired com.monthley.document.api.DocumentAccessPort access;
    @Autowired JdbcClient jdbc;
    @PersistenceContext EntityManager em;

    private long acc;
    private long resitId;
    private long batalId;

    @BeforeEach
    void seed() {
        jdbc.sql("""
                INSERT IGNORE INTO service_provider (sp_code, name, status, version)
                VALUES (:sp, 'SP Ujian Resend', 'ACTIVE', 0)
                """).param("sp", SP).update();
        jdbc.sql("""
                INSERT IGNORE INTO sp_document_setting (sp_code, receipt_title, version)
                VALUES (:sp, 'RESIT', 0)
                """).param("sp", SP).update();
        jdbc.sql("""
                INSERT IGNORE INTO sp_billing_setting (sp_code, currency, language, version)
                VALUES (:sp, 'MYR', 'ms', 0)
                """).param("sp", SP).update();

        String no = "RS-" + System.nanoTime();
        jdbc.sql("""
                INSERT INTO account (sp_code, account_no, account_name,
                                     billto_name, billto_email, status)
                VALUES (:sp, :no, 'AISYAH', 'AISYAH', 'asal@contoh.com', 'ACTIVE')
                """).param("sp", SP).param("no", no).update();
        acc = jdbc.sql("SELECT id FROM account WHERE sp_code=:sp AND account_no=:no")
                .param("sp", SP).param("no", no).query(Long.class).single();

        resitId = dokumen("RS-RCP-" + System.nanoTime(), "RECEIPT", "ACTIVE");
        batalId = dokumen("RS-RCP-X-" + System.nanoTime(), "RECEIPT", "CANCELLED");
        em.flush();

        TenantContext.set(SP);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("clerk", "n/a",
                        List.of(new SimpleGrantedAuthority("SP_" + SP + "_CLERK"))));
    }

    @AfterEach
    void clear() { TenantContext.clear(); SecurityContextHolder.clearContext(); }

    private long dokumen(String docNo, String type, String status) {
        jdbc.sql("""
                INSERT INTO financial_document
                  (sp_code, doc_no, doc_type, account_id, doc_date,
                   amount, tax_amount, status, title, currency)
                VALUES (:sp, :no, :type, :acc, :d, 120.00, 0, :st, 'Resit', 'MYR')
                """)
                .param("sp", SP).param("no", docNo).param("type", type)
                .param("acc", acc).param("d", LocalDate.of(2026, 7, 20))
                .param("st", status).update();
        return jdbc.sql("SELECT id FROM financial_document WHERE sp_code=:sp AND doc_no=:no")
                .param("sp", SP).param("no", docNo).query(Long.class).single();
    }

    @Test
    @DisplayName("BEBERAPA penerima — alamat daripada permintaan, bukan akaun")
    void beberapaPenerima() {
        var r = controller.resend(resitId,
                new StatementController.ResendRequest(
                        List.of("satu@contoh.com", "dua@contoh.com")));

        assertThat(r.sent()).isEqualTo(2);
        assertThat(r.recipients())
                .as("alamat akaun ialah asal@contoh.com; kerani menukarnya "
                    + "kerana alamat itu salah")
                .containsExactly("satu@contoh.com", "dua@contoh.com")
                .doesNotContain("asal@contoh.com");
    }

    @Test
    @DisplayName("alamat pendua dan kosong ditapis")
    void tapisPenduaDanKosong() {
        var r = controller.resend(resitId,
                new StatementController.ResendRequest(
                        List.of("  sama@contoh.com  ", "sama@contoh.com", "", "  ")));

        assertThat(r.sent()).isEqualTo(1);
        assertThat(r.recipients()).containsExactly("sama@contoh.com");
    }

    @Test
    @DisplayName("senarai kosong DITOLAK — tiada e-mel dihantar tanpa penerima")
    void senaraiKosongDitolak() {
        assertThatThrownBy(() -> controller.resend(resitId,
                new StatementController.ResendRequest(List.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("alamat e-mel diperlukan");
    }

    @Test
    @DisplayName("dokumen DIBATALKAN tidak boleh dihantar")
    void dokumenDibatalkanDitolak() {
        assertThatThrownBy(() -> controller.resend(batalId,
                new StatementController.ResendRequest(
                        List.of("sesiapa@contoh.com"))))
                .as("pelanggan akan menerima dokumen yang tidak lagi sah")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dibatalkan");
    }

    @Test
    @DisplayName("token pautan SAMA seperti penghantaran asal")
    void tokenSama() {
        String asal = access.tokenFor(SP, resitId,
                com.monthley.document.api.DocumentType.RECEIPT);
        em.flush();

        controller.resend(resitId,
                new StatementController.ResendRequest(List.of("x@contoh.com")));
        em.flush();

        String selepas = access.tokenFor(SP, resitId,
                com.monthley.document.api.DocumentType.RECEIPT);

        assertThat(selepas)
                .as("satu token per dokumen — e-mel lama mesti kekal berfungsi")
                .isEqualTo(asal);

        long bil = jdbc.sql(
                "SELECT COUNT(*) FROM document_access_token WHERE document_id = :id")
                .param("id", resitId).query(Long.class).single();
        assertThat(bil).isEqualTo(1);
    }

    @Test
    @DisplayName("dokumen SP lain tidak dijumpai")
    void dokumenSpLain() {
        assertThatThrownBy(() -> controller.resend(99999999L,
                new StatementController.ResendRequest(
                        List.of("x@contoh.com"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tidak dijumpai");
    }
}
