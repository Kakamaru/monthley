package com.monthley.account;

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
 * /accounts/my mesti bersetuju dengan VIEW account_balance (ADR 0009).
 *
 * Formula terdahulu menjumlahkan invois tolak alokasi, yang BUTA kepada
 * kredit yang belum dipadankan. Pada data produksi 26 Julai 2026 ia
 * memberitahu M04 bahawa dia berhutang RM200 LEBIH daripada yang sebenar
 * — meminta wang yang bukan hak kita — dan menunjukkan kredit M06
 * sebagai sifar.
 *
 * Ujian ini mengunci senario itu supaya ia tidak boleh kembali.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MyAccountsBalanceTest {

    @Autowired JdbcClient jdbc;

    private String sp;
    private long acc;

    private long doc(String docNo, String type, String amount) {
        jdbc.sql("""
                INSERT INTO financial_document
                  (sp_code, doc_no, doc_type, account_id, doc_date,
                   amount, tax_amount, status, currency)
                VALUES (:sp, :no, :type, :acc, :date, :amt, 0, 'ACTIVE', 'MYR')
                """)
                .param("sp", sp).param("no", docNo).param("type", type)
                .param("acc", acc).param("date", LocalDate.of(2026, 7, 21))
                .param("amt", new BigDecimal(amount))
                .update();
        return jdbc.sql("SELECT id FROM financial_document WHERE sp_code=:sp AND doc_no=:no")
                .param("sp", sp).param("no", docNo).query(Long.class).single();
    }

    private void alloc(long debitDoc, long creditDoc, String amount) {
        jdbc.sql("""
                INSERT INTO fi_allocation
                  (sp_code, account_id, debit_document_id, credit_document_id,
                   amount, status)
                VALUES (:sp, :acc, :dd, :cd, :amt, 'ACTIVE')
                """)
                .param("sp", sp).param("acc", acc)
                .param("dd", debitDoc).param("cd", creditDoc)
                .param("amt", new BigDecimal(amount))
                .update();
    }

    @BeforeEach
    void seed() {
        sp = jdbc.sql("SELECT sp_code FROM service_provider ORDER BY sp_code LIMIT 1")
                .query(String.class).single();
        String no = "MYBAL-" + System.nanoTime();
        jdbc.sql("""
                INSERT INTO account (sp_code, account_no, account_name, status)
                VALUES (:sp, :no, 'Ujian Baki Portal', 'ACTIVE')
                """).param("sp", sp).param("no", no).update();
        acc = jdbc.sql("SELECT id FROM account WHERE sp_code=:sp AND account_no=:no")
                .param("sp", sp).param("no", no).query(Long.class).single();

        // Corak M04: invois RM300 habis dibayar, DAN resit RM500 yang
        // hanya RM300 dialokasi. Baki sebenar = 300 - 800 = -500.
        long inv = doc("T-INV", "INVOICE", "300.00");
        long rcp1 = doc("T-RCP1", "RECEIPT", "300.00");
        doc("T-RCP2", "RECEIPT", "500.00");   // langsung tidak dialokasi
        alloc(inv, rcp1, "300.00");
    }

    /** Formula LAMA — kekal di sini sebagai pembanding, bukan sebagai sumber. */
    private BigDecimal formulaLama() {
        return jdbc.sql("""
                SELECT COALESCE(SUM((d.amount + d.tax_amount) - COALESCE((
                          SELECT SUM(al.amount) FROM fi_allocation al
                          WHERE al.debit_document_id = d.id AND al.status='ACTIVE'), 0)), 0)
                FROM financial_document d
                WHERE d.account_id = :a AND d.doc_type IN ('INVOICE','DEBIT_NOTE')
                  AND d.status <> 'CANCELLED'
                """).param("a", acc).query(BigDecimal.class).single();
    }

    private BigDecimal viewBalance() {
        return jdbc.sql("SELECT COALESCE(balance,0) FROM account_balance WHERE account_id=:a")
                .param("a", acc).query(BigDecimal.class).single();
    }

    @Test
    @DisplayName("kredit belum dialokasi TERMASUK dalam baki — bukan diabaikan")
    void kreditBelumDialokasiDikira() {
        // 300 debit - 800 kredit = -500 (pelanggan ada kredit RM500)
        assertThat(viewBalance()).isEqualByComparingTo("-500.00");
    }

    @Test
    @DisplayName("formula lama menyimpang: ia melaporkan sifar sedangkan pelanggan berkredit")
    void formulaLamaMenyimpang() {
        assertThat(formulaLama())
                .as("invois-tolak-alokasi buta kepada kredit belum dipadankan")
                .isEqualByComparingTo("0.00");

        assertThat(formulaLama())
                .as("inilah sebab /accounts/my mesti guna VIEW, bukan formula ini")
                .isNotEqualByComparingTo(viewBalance());
    }

    @Test
    @DisplayName("tunggakan tidak boleh negatif; baki boleh")
    void tunggakanLawanBaki() {
        BigDecimal arrears = formulaLama();   // formula tunggakan, dinamakan betul
        assertThat(arrears).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(viewBalance()).isNegative();
    }
}
