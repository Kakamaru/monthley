package com.monthley.statement;

import com.monthley.statement.api.StatementModel;
import com.monthley.statement.api.StatementPort;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invarian ADR 0010. Ujian ini mengunci KEPUTUSAN, bukan pelaksanaan.
 *
 * Jika mana-mana daripadanya gagal, penyata dan VIEW tidak lagi
 * bersetuju — iaitu kegagalan yang menghantui legacy selama enam tahun
 * (CASE-002).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StatementInvariantTest {

    @Autowired StatementPort statement;
    @Autowired JdbcClient jdbc;

    /** SP sedia ada; akaun BAHARU supaya tiada dokumen lain mencemari baki. */
    private String sp;
    private long acc;

    private void doc(String docNo, String type, String date,
                     String amount, String tax, String status, String title) {
        jdbc.sql("""
                INSERT INTO financial_document
                  (sp_code, doc_no, doc_type, account_id, doc_date,
                   amount, tax_amount, status, title, currency)
                VALUES (:sp, :no, :type, :acc, :date,
                        :amt, :tax, :sts, :title, 'MYR')
                """)
                .param("sp", sp).param("no", docNo).param("type", type)
                .param("acc", acc).param("date", LocalDate.parse(date))
                .param("amt", new BigDecimal(amount))
                .param("tax", new BigDecimal(tax))
                .param("sts", status).param("title", title)
                .update();
    }

    @BeforeEach
    void seed() {
        sp = jdbc.sql("SELECT sp_code FROM service_provider ORDER BY sp_code LIMIT 1")
                .query(String.class).single();

        String accountNo = "STMT-TEST-" + System.nanoTime();
        jdbc.sql("""
                INSERT INTO account (sp_code, account_no, account_name)
                VALUES (:sp, :no, 'Ujian Invarian Penyata')
                """)
                .param("sp", sp).param("no", accountNo)
                .update();
        acc = jdbc.sql("SELECT id FROM account WHERE sp_code = :sp AND account_no = :no")
                .param("sp", sp).param("no", accountNo)
                .query(Long.class).single();

        // 2025 — tutup pada 100.00
        doc("INV-A", "INVOICE", "2025-03-01", "300.00", "0.00", "ACTIVE", "Yuran Mac");
        doc("RCP-A", "RECEIPT", "2025-04-01", "200.00", "0.00", "ACTIVE", "Bayaran");

        // 2026 — invois bercukai, resit, nota debit/kredit, dan satu BATAL
        doc("INV-B", "INVOICE",     "2026-01-10", "100.00", "6.00",  "ACTIVE",    "Yuran Jan");
        doc("INV-C", "INVOICE",     "2026-02-10", "500.00", "0.00",  "CANCELLED", "Tersilap jana");
        doc("DBN-A", "DEBIT_NOTE",  "2026-03-01",  "50.00", "0.00",  "ACTIVE",    "Caj lewat");
        doc("CRN-A", "CREDIT_NOTE", "2026-04-01",  "20.00", "0.00",  "ACTIVE",    "Rebat");
        doc("RCP-B", "RECEIPT",     "2026-05-01", "136.00", "0.00",  "ACTIVE",    "Bayaran");
    }

    private BigDecimal viewBalance() {
        return jdbc.sql("SELECT COALESCE(balance,0) FROM account_balance WHERE account_id = :a")
                .param("a", acc).query(BigDecimal.class).single();
    }

    @Test
    @DisplayName("julat penuh: baki penutup == VIEW account_balance")
    void penutupSamaDenganView() {
        StatementModel m = statement.forRange(sp, acc,
                LocalDate.of(2000, 1, 1), LocalDate.of(2099, 12, 31));

        assertThat(m.closingBalance())
                .as("penyata mesti bersetuju dengan satu takrifan baki")
                .isEqualByComparingTo(viewBalance());
    }

    @Test
    @DisplayName("penutup tahun N == pembukaan tahun N+1")
    void bawaHadapanBersambung() {
        StatementModel y2025 = statement.forYear(sp, acc, 2025);
        StatementModel y2026 = statement.forYear(sp, acc, 2026);

        assertThat(y2025.closingBalance())
                .as("tiada jurang antara tahun — punca 'Sila Pilih' legacy")
                .isEqualByComparingTo(y2026.openingBalance());

        assertThat(y2025.closingBalance()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("dokumen batal DIPAPARKAN tetapi tidak menggerakkan baki")
    void batalPaparTanpaGerakBaki() {
        StatementModel m = statement.forYear(sp, acc, 2026);

        var batal = m.rows().stream().filter(r -> "INV-C".equals(r.docNo())).toList();
        assertThat(batal).as("dokumen batal mesti kelihatan (legend)").hasSize(1);
        assertThat(batal.get(0).cancelled()).isTrue();
        assertThat(batal.get(0).amount())
                .as("RM500 batal tidak boleh menggerakkan baki")
                .isEqualByComparingTo(BigDecimal.ZERO);

        // 100 + (100+6) + 50 - 20 - 136 = 100.00
        assertThat(m.closingBalance()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("cukai termasuk dalam baki; baki berjalan bersambung")
    void cukaiDanBakiBerjalan() {
        StatementModel m = statement.forYear(sp, acc, 2026);

        var invB = m.rows().stream().filter(r -> "INV-B".equals(r.docNo())).findFirst().orElseThrow();
        assertThat(invB.amount())
                .as("amount + tax_amount, bukan amount sahaja")
                .isEqualByComparingTo("106.00");

        BigDecimal jalan = m.openingBalance();
        for (var r : m.rows()) {
            jalan = jalan.add(r.amount());
            assertThat(r.runningBalance())
                    .as("baki berjalan pada %s", r.docNo())
                    .isEqualByComparingTo(jalan);
        }
        assertThat(jalan).isEqualByComparingTo(m.closingBalance());
    }

    @Test
    @DisplayName("VIEW mengenali setiap doc_type yang wujud (ELSE 0 tidak menelan apa-apa)")
    void tiadaDocTypeYangTidakDikenali() {
        List<String> asing = jdbc.sql("""
                SELECT DISTINCT doc_type FROM financial_document
                WHERE doc_type NOT IN ('INVOICE','RECEIPT','DEBIT_NOTE','CREDIT_NOTE')
                """).query(String.class).list();

        assertThat(asing)
                .as("doc_type baharu akan HILANG dari baki secara senyap "
                    + "(account_document_entry ELSE 0) — kemas kini V33 dahulu")
                .isEmpty();
    }
}
