package com.monthley.statement;

import com.monthley.statement.api.StatementModel;
import com.monthley.statement.api.StatementPort;
import com.monthley.statement.api.StatementRow;
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
 * Sub-baris padanan (ADR 0010 keputusan 4).
 *
 * Padanan ialah DETAIL: ia menjawab "resit mana membayar invois mana",
 * dan TIDAK menggerakkan lajur baki. Jika ia menggerakkan baki, kita
 * mengira alokasi dua kali — ADR 0009 pecah.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StatementMatchTest {

    @Autowired StatementPort statement;
    @Autowired JdbcClient jdbc;

    private String sp;
    private long acc;
    private long invId;
    private long rcpId;

    private long doc(String docNo, String type, String date, String amount, String title) {
        jdbc.sql("""
                INSERT INTO financial_document
                  (sp_code, doc_no, doc_type, account_id, doc_date,
                   amount, tax_amount, status, title, currency)
                VALUES (:sp, :no, :type, :acc, :date, :amt, 0, 'ACTIVE', :title, 'MYR')
                """)
                .param("sp", sp).param("no", docNo).param("type", type)
                .param("acc", acc).param("date", LocalDate.parse(date))
                .param("amt", new BigDecimal(amount)).param("title", title)
                .update();
        return jdbc.sql("SELECT id FROM financial_document WHERE sp_code=:sp AND doc_no=:no")
                .param("sp", sp).param("no", docNo).query(Long.class).single();
    }

    private long line(long docId, String descr, String amount, String periodStart) {
        jdbc.sql("""
                INSERT INTO financial_document_line
                  (document_id, description, quantity, unit_price, amount,
                   tax_amount, period_start, period_end, active)
                VALUES (:doc, :d, 1, :amt, :amt, 0, :ps, :pe, 1)
                """)
                .param("doc", docId).param("d", descr)
                .param("amt", new BigDecimal(amount))
                .param("ps", LocalDate.parse(periodStart))
                .param("pe", LocalDate.parse(periodStart).plusMonths(1).minusDays(1))
                .update();
        return jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
    }

    private void alloc(long debitDoc, Long debitLine, long creditDoc, String amount) {
        jdbc.sql("""
                INSERT INTO fi_allocation
                  (sp_code, account_id, debit_document_id, debit_document_line_id,
                   credit_document_id, amount, status)
                VALUES (:sp, :acc, :dd, :dl, :cd, :amt, 'ACTIVE')
                """)
                .param("sp", sp).param("acc", acc)
                .param("dd", debitDoc).param("dl", debitLine)
                .param("cd", creditDoc).param("amt", new BigDecimal(amount))
                .update();
    }

    @BeforeEach
    void seed() {
        sp = jdbc.sql("SELECT sp_code FROM service_provider ORDER BY sp_code LIMIT 1")
                .query(String.class).single();

        String accountNo = "MATCH-TEST-" + System.nanoTime();
        jdbc.sql("""
                INSERT INTO account (sp_code, account_no, account_name)
                VALUES (:sp, :no, 'Ujian Padanan Penyata')
                """).param("sp", sp).param("no", accountNo).update();
        acc = jdbc.sql("SELECT id FROM account WHERE sp_code=:sp AND account_no=:no")
                .param("sp", sp).param("no", accountNo).query(Long.class).single();

        // Satu invois RM150 dengan TIGA baris bulanan — corak INV000021
        invId = doc("T-INV-1", "INVOICE", "2026-03-01", "150.00", "Parking 2026");
        long l1 = line(invId, "Parking", "50.00", "2026-01-01");
        long l2 = line(invId, "Parking", "50.00", "2026-02-01");
        long l3 = line(invId, "Parking", "50.00", "2026-03-01");

        // Resit RM150 membayar ketiga-tiga baris
        rcpId = doc("T-RCP-1", "RECEIPT", "2026-03-15", "150.00", "Bayaran");
        alloc(invId, l1, rcpId, "50.00");
        alloc(invId, l2, rcpId, "50.00");
        alloc(invId, l3, rcpId, "50.00");
    }

    private StatementRow row(StatementModel m, String docNo) {
        return m.rows().stream().filter(r -> docNo.equals(r.docNo())).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("baris RESIT menunjukkan invois yang dibayarnya, dengan tempoh SETIAP BARIS")
    void resitTunjukInvois() {
        StatementModel m = statement.forYear(sp, acc, 2026);
        var rcp = row(m, "T-RCP-1");

        assertThat(rcp.matches()).hasSize(3);
        assertThat(rcp.matches()).allSatisfy(x ->
                assertThat(x.documentNo()).isEqualTo("T-INV-1"));

        // Tempoh datang dari BARIS, bukan dokumen — tiga bulan BERBEZA.
        // Sebagai TARIKH: fakta, bukan teks yang ditaip manusia.
        assertThat(rcp.matches()).extracting(x -> x.periodStart())
                .doesNotContainNull()
                .doesNotHaveDuplicates()
                .hasSize(3);
    }

    @Test
    @DisplayName("baris INVOIS menunjukkan PECAHAN CAJnya, bukan resit yang membayarnya")
    void invoisTunjukPecahanCaj() {
        StatementModel m = statement.forYear(sp, acc, 2026);
        var inv = row(m, "T-INV-1");

        // Sub-baris sentiasa menjawab: dokumen ini terdiri daripada apa.
        // Invois RM150 dengan tiga baris bulanan mesti menunjukkan
        // ketiga-tiganya — jika tidak pelanggan melihat 'Invois M01' dan
        // tidak tahu dia dicaj untuk apa.
        assertThat(inv.matches()).hasSize(3);
        assertThat(inv.matches()).extracting(x -> x.periodStart())
                .doesNotContainNull()
                .doesNotHaveDuplicates();

        // documentNo null: ia baris dokumen itu sendiri, bukan rujukan
        // kepada dokumen lain.
        assertThat(inv.matches()).allSatisfy(x ->
                assertThat(x.documentNo()).isNull());

        // Arah bertentangan TIDAK dipaparkan: resit sudah menyenaraikan
        // apa yang dibayarnya, dan mengulanginya memaksa pembaca
        // menghubungkan satu bayaran dua kali.
        assertThat(inv.matches()).extracting(x -> x.documentNo())
                .doesNotContain("T-RCP-1");
    }

    @Test
    @DisplayName("invois SATU baris tidak dipecahkan — sub-baris akan mengulang dirinya")
    void invoisSatuBarisTiadaSubBaris() {
        long d = doc("T-INV-2", "INVOICE", "2026-06-01", "75.00", "Yuran Jun");
        line(d, "Yuran", "75.00", "2026-06-01");

        var inv = row(statement.forYear(sp, acc, 2026), "T-INV-2");
        assertThat(inv.matches()).isEmpty();
    }

    @Test
    @DisplayName("sub-baris TIDAK menggerakkan baki")
    void padananTidakGerakBaki() {
        StatementModel m = statement.forYear(sp, acc, 2026);

        // 150 invois - 150 resit = 0. Enam sub-baris RM50 mesti tidak
        // mengubahnya; jika ia dikira, baki akan tersasar RM300.
        assertThat(m.closingBalance()).isEqualByComparingTo("0.00");

        BigDecimal jalan = m.openingBalance();
        for (StatementRow r : m.rows()) {
            jalan = jalan.add(r.amount());
            assertThat(r.runningBalance()).isEqualByComparingTo(jalan);
        }
    }

    @Test
    @DisplayName("jumlah padanan tidak melebihi amaun dokumen")
    void padananTidakMelebihiDokumen() {
        StatementModel m = statement.forYear(sp, acc, 2026);

        for (StatementRow r : m.rows()) {
            if (r.matches().isEmpty()) continue;
            BigDecimal jumlah = r.matches().stream()
                    .map(x -> x.amount())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(jumlah)
                    .as("padanan %s melebihi nilai dokumen — CASE-002", r.docNo())
                    .isLessThanOrEqualTo(r.amount().abs());
        }
    }
}
