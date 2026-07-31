package com.monthley.account.internal;

import com.monthley.shared.TenantContext;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Skrin dan PDF mesti menunjukkan penyata yang SAMA (ADR 0010).
 *
 * Termasuk jejak audit pembatalan: PDF, XLSX dan portal dibina daripada
 * SATU StatementModel. Menampal dua sahaja bermakna portal memaparkan
 * 0.00 tanpa nombor asal dan tanpa siapa membatalkannya.
 *
 * Versi terdahulu endpoint ini membina penyatanya sendiri: satu baris per
 * alokasi, plus baris 'advance' yang dikarang daripada (resit - alokasi).
 * Baris itu tidak wujud sebagai rekod — ia corak legacy yang CASE-004
 * bedah dan ADR 0010 tolak. Ujian ini menghalangnya kembali.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StatementEndpointAlignmentTest {

    @Autowired AccountController controller;
    @Autowired StatementPort statements;
    @Autowired JdbcClient jdbc;

    private String sp;
    private long acc;

    private long doc(String no, String type, String amt, String date) {
        jdbc.sql("""
                INSERT INTO financial_document
                  (sp_code, doc_no, doc_type, account_id, doc_date,
                   amount, tax_amount, status, title, currency)
                VALUES (:sp, :no, :t, :acc, :d, :a, 0, 'ACTIVE', :ti, 'MYR')
                """)
                .param("sp", sp).param("no", no).param("t", type)
                .param("acc", acc).param("d", LocalDate.parse(date))
                .param("a", new BigDecimal(amt)).param("ti", type)
                .update();
        return jdbc.sql("SELECT id FROM financial_document WHERE sp_code=:sp AND doc_no=:no")
                .param("sp", sp).param("no", no).query(Long.class).single();
    }

    @BeforeEach
    void seed() {
        // SP sendiri, bukan yang kebetulan wujud. Meminjam SP pertama
        // dalam jadual menjadikan keputusan ujian bergantung pada data
        // yang ada — dan gagal sepenuhnya dalam DB kosong.
        jdbc.sql("""
                INSERT IGNORE INTO service_provider (sp_code, name, status, version)
                VALUES (:sp, 'SP Ujian Selaras', 'ACTIVE', 0)
                """).param("sp", "SALN").update();
        sp = "SALN";
        String no = "ALIGN-" + System.nanoTime();
        jdbc.sql("""
                INSERT INTO account (sp_code, account_no, account_name, status)
                VALUES (:sp, :no, 'Ujian Selaras', 'ACTIVE')
                """).param("sp", sp).param("no", no).update();
        acc = jdbc.sql("SELECT id FROM account WHERE sp_code=:sp AND account_no=:no")
                .param("sp", sp).param("no", no).query(Long.class).single();

        long inv = doc("AL-INV", "INVOICE", "300.00", "2026-02-01");
        long rcp = doc("AL-RCP", "RECEIPT", "500.00", "2026-03-01");  // RM200 advance
        jdbc.sql("""
                INSERT INTO fi_allocation
                  (sp_code, account_id, debit_document_id, credit_document_id, amount, status)
                VALUES (:sp, :acc, :dd, :cd, 300.00, 'ACTIVE')
                """).param("sp", sp).param("acc", acc)
                .param("dd", inv).param("cd", rcp).update();

        TenantContext.set(sp);
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    @Test
    @DisplayName("skrin dan model bersetuju pada baki penutup")
    void bakiSama() {
        var skrin = controller.statement(acc, 2026, 0, 100);
        var model = statements.forYear(sp, acc, 2026);

        assertThat(skrin.closingBalance()).isEqualByComparingTo(model.closingBalance());
        assertThat(skrin.closingBalance()).isEqualByComparingTo("-200.00");
    }

    @Test
    @DisplayName("TIADA baris 'advance' dikarang — resit muncul SEKALI, amaun penuh")
    void tiadaBarisAdvance() {
        var skrin = controller.statement(acc, 2026, 0, 100);

        assertThat(skrin.lines())
                .as("dua dokumen bermakna dua baris, bukan tiga")
                .hasSize(2);

        var resit = skrin.lines().stream()
                .filter(l -> "AL-RCP".equals(l.docNo())).toList();
        assertThat(resit).hasSize(1);
        assertThat(resit.get(0).amount())
                .as("resit penuh RM500, bukan RM300 + baris advance RM200")
                .isEqualByComparingTo("-500.00");

        assertThat(skrin.lines())
                .extracting(AccountController.StatementLine::item)
                .noneMatch(x -> x != null && x.toLowerCase().contains("advance"));
    }

    @Test
    @DisplayName("alokasi menjadi sub-baris, bukan baris sendiri")
    void alokasiJadiSubBaris() {
        var resit = controller.statement(acc, 2026, 0, 100).lines().stream()
                .filter(l -> "AL-RCP".equals(l.docNo())).findFirst().orElseThrow();

        assertThat(resit.matches()).hasSize(1);
        assertThat(resit.matches().get(0).docNo()).isEqualTo("AL-INV");
        assertThat(resit.matches().get(0).amount()).isEqualByComparingTo("300.00");
    }

    @Test
    @DisplayName("tahun null bermakna semua rekod, bukan ralat")
    void semuaRekodKekalAda() {
        var semua = controller.statement(acc, null, 0, 100);
        assertThat(semua.lines()).hasSize(2);
        assertThat(semua.year()).isNull();
    }
}
