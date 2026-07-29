package com.monthley.document.internal;

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

/**
 * Finance Documents — carian.
 *
 * SATU skrin untuk semua jenis dokumen. SP menggunakannya setiap hari
 * untuk mencari dan mencetak semula.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class FinanceDocumentSearchTest {

    private static final String SP = "SPFD";
    private static final String LAIN = "SPFX";

    @Autowired FinanceDocumentController controller;
    @Autowired JdbcClient jdbc;
    @PersistenceContext EntityManager em;

    private long acc;

    @BeforeEach
    void seed() {
        for (String sp : new String[]{SP, LAIN}) {
            jdbc.sql("""
                    INSERT IGNORE INTO service_provider (sp_code, name, status, version)
                    VALUES (:sp, 'SP Ujian Dokumen', 'ACTIVE', 0)
                    """).param("sp", sp).update();
        }
        // Tetapan SP menentukan lajur 'Title'.
        jdbc.sql("""
                INSERT INTO sp_document_setting (sp_code, invoice_title, receipt_title, version)
                VALUES (:sp, 'INVOIS', 'RESIT', 0)
                ON DUPLICATE KEY UPDATE invoice_title='INVOIS', receipt_title='RESIT'
                """).param("sp", SP).update();

        acc = akaun(SP, "FD-" + System.nanoTime(), "SITI AMINAH");

        dokumen(SP, acc, "FD-INV-1", "INVOICE",  "2026-07-01", "500.00", null);
        dokumen(SP, acc, "FD-RCP-1", "RECEIPT",  "2026-07-15", "300.00", "TRF-98765");
        dokumen(SP, acc, "FD-DN-1",  "DEBIT_NOTE", "2026-08-01", "50.00", null);

        long accLain = akaun(LAIN, "FX-" + System.nanoTime(), "ORANG LAIN");
        dokumen(LAIN, accLain, "FX-INV-1", "INVOICE", "2026-07-01", "999.00", null);

        em.flush();
        em.clear();

        TenantContext.set(SP);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("clerk", "n/a",
                        List.of(new SimpleGrantedAuthority("SP_" + SP + "_CLERK"))));
    }

    @AfterEach
    void clear() { TenantContext.clear(); SecurityContextHolder.clearContext(); }

    private long akaun(String sp, String no, String nama) {
        jdbc.sql("""
                INSERT INTO account (sp_code, account_no, account_name, billto_name, status)
                VALUES (:sp, :no, :nama, :nama, 'ACTIVE')
                """).param("sp", sp).param("no", no).param("nama", nama).update();
        return jdbc.sql("SELECT id FROM account WHERE sp_code=:sp AND account_no=:no")
                .param("sp", sp).param("no", no).query(Long.class).single();
    }

    private void dokumen(String sp, long accountId, String docNo, String type,
                         String tarikh, String amaun, String payRef) {
        jdbc.sql("""
                INSERT INTO financial_document
                  (sp_code, doc_no, doc_type, account_id, doc_date,
                   amount, tax_amount, status, title, currency, payment_ref_no)
                VALUES (:sp, :no, :type, :acc, :d, :amt, 0, 'ACTIVE',
                        'Keterangan dokumen', 'MYR', :ref)
                """)
                .param("sp", sp).param("no", docNo).param("type", type)
                .param("acc", accountId).param("d", LocalDate.parse(tarikh))
                .param("amt", new java.math.BigDecimal(amaun))
                .param("ref", payRef)
                .update();
    }

    @Test
    @DisplayName("carian kosong memulangkan semua dokumen SP ini SAHAJA")
    void asasDanPengasinganPenyewa() {
        var r = controller.search(null, null, null, null, null, null, null, 0, 50);

        assertThat(r.items()).hasSize(3);
        assertThat(r.items()).extracting(FinanceDocumentController.DocumentRow::docNo)
                .as("dokumen SP lain tidak boleh muncul")
                .doesNotContain("FX-INV-1");
    }

    @Test
    @DisplayName("lajur Title datang daripada TETAPAN SP, bukan document.title")
    void titleDaripadaTetapan() {
        var r = controller.search(null, null, null, null, null, null, null, 0, 50);

        var inv = r.items().stream()
                .filter(d -> "FD-INV-1".equals(d.docNo())).findFirst().orElseThrow();
        var rcp = r.items().stream()
                .filter(d -> "FD-RCP-1".equals(d.docNo())).findFirst().orElseThrow();

        assertThat(inv.title())
                .as("document.title ialah 'Keterangan dokumen'; lajur ini "
                    + "sepatutnya label JENIS daripada tetapan")
                .isEqualTo("INVOIS");
        assertThat(rcp.title()).isEqualTo("RESIT");
    }

    @Test
    @DisplayName("nota debit/kredit dapat label lalai — tiada tetapan untuknya")
    void notaLabelLalai() {
        var r = controller.search(null, null, null, "DEBIT_NOTE", null, null, null, 0, 50);
        assertThat(r.items()).hasSize(1);
        assertThat(r.items().get(0).title()).isEqualTo("Nota Debit");
    }

    @Test
    @DisplayName("tapis: no. dokumen separa, tidak sensitif huruf besar")
    void tapisDocNo() {
        assertThat(controller.search("fd-rcp", null, null, null, null, null, null, 0, 50)
                .items()).hasSize(1);
    }

    @Test
    @DisplayName("tapis: nama akaun ATAU no. akaun")
    void tapisAkaun() {
        assertThat(controller.search(null, "siti", null, null, null, null, null, 0, 50)
                .items()).hasSize(3);
        assertThat(controller.search(null, "tiada-siapa", null, null, null, null, null, 0, 50)
                .items()).isEmpty();
    }

    @Test
    @DisplayName("tapis: payment ref — no. transaksi bank")
    void tapisPayRef() {
        var r = controller.search(null, null, null, null, "98765", null, null, 0, 50);
        assertThat(r.items()).hasSize(1);
        assertThat(r.items().get(0).docNo()).isEqualTo("FD-RCP-1");
    }

    @Test
    @DisplayName("tapis: julat tarikh dikeluarkan")
    void tapisTarikh() {
        var julai = controller.search(null, null, null, null, null,
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), 0, 50);
        assertThat(julai.items()).hasSize(2);

        var ogos = controller.search(null, null, null, null, null,
                LocalDate.of(2026, 8, 1), null, 0, 50);
        assertThat(ogos.items()).hasSize(1);
    }

    @Test
    @DisplayName("tersusun TERBARU dahulu — SP mencari yang baru dikeluarkan")
    void tersusunTerbaru() {
        var r = controller.search(null, null, null, null, null, null, null, 0, 50);
        assertThat(r.items()).extracting(FinanceDocumentController.DocumentRow::docNo)
                .containsExactly("FD-DN-1", "FD-RCP-1", "FD-INV-1");
    }

    @Test
    @DisplayName("pagination: jumlah ialah keseluruhan, bukan saiz halaman")
    void pagination() {
        var r = controller.search(null, null, null, null, null, null, null, 0, 2);
        assertThat(r.items()).hasSize(2);
        assertThat(r.total()).isEqualTo(3);
    }
}
