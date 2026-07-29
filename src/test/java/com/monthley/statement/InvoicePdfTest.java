package com.monthley.statement;

import com.monthley.document.api.DocumentPort;
import com.monthley.document.api.NewDocumentLine;
import com.monthley.document.api.NewInvoice;
import com.monthley.ledger.internal.ChartOfAccountSeeder;
import com.monthley.shared.TenantContext;
import com.monthley.statement.api.StatementPort;
import com.monthley.statement.api.StatementRenderPort;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invois PDF.
 *
 * RINGKASAN TIGA LAJUR: Baki Sebelum + Caj Baharu = Jumlah Perlu Dibayar.
 *
 * 'Baki Sebelum' ialah baki tepat SEBELUM dokumen ini, bukan baki awal
 * bulan. Bukti daripada dua invois split legacy pada tarikh yang sama:
 * I20204912 menunjukkan 1,606.15 dan I20204913 menunjukkan 1,676.15 —
 * yang kedua memasukkan yang pertama.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class InvoicePdfTest {

    private static final String SP = "SPIV";

    @Autowired StatementPort statements;
    @Autowired StatementRenderPort renderer;
    @Autowired DocumentPort documents;
    @Autowired ChartOfAccountSeeder seeder;
    @Autowired JdbcClient jdbc;
    @PersistenceContext EntityManager em;

    private long acc;

    @BeforeEach
    void seed() {
        jdbc.sql("""
                INSERT IGNORE INTO service_provider (sp_code, name, status, version)
                VALUES (:sp, 'JMB Ujian Invois', 'ACTIVE', 0)
                """).param("sp", SP).update();
        seeder.seedFor(SP);
        jdbc.sql("""
                INSERT IGNORE INTO sp_document_setting (sp_code, invoice_title, version)
                VALUES (:sp, 'INVOIS', 0)
                """).param("sp", SP).update();
        jdbc.sql("""
                INSERT IGNORE INTO sp_billing_setting (sp_code, currency, language, version)
                VALUES (:sp, 'MYR', 'ms', 0)
                """).param("sp", SP).update();

        String no = "IV-" + System.nanoTime();
        jdbc.sql("""
                INSERT INTO account (sp_code, account_no, account_name, billto_name, status)
                VALUES (:sp, :no, 'ROZANNA BINTI RIDUAN', 'ROZANNA BINTI RIDUAN', 'ACTIVE')
                """).param("sp", SP).param("no", no).update();
        acc = jdbc.sql("SELECT id FROM account WHERE sp_code=:sp AND account_no=:no")
                .param("sp", SP).param("no", no).query(Long.class).single();

        TenantContext.set(SP);
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    /**
     * Produk sebenar. idem_key ialah (akaun, produk, tempoh) — dua invois
     * dengan produk NULL dalam tempoh yang sama bertembung, dan itulah
     * yang berlaku dalam percubaan pertama ujian ini.
     *
     * Dua invois split legacy pada tarikh sama memang produk berbeza:
     * MAINTENANCE FEE B dan SINKING FUND B.
     */
    private long produk(String kod) {
        jdbc.sql("""
                INSERT IGNORE INTO product
                  (sp_code, code, name, charge_frequency, unit_rate,
                   main_product, mandatory, prorated, late_penalty, status, version)
                VALUES (:sp, :k, :k, 'MONTHLY', 50.00, 0,0,0,0,'ACTIVE',0)
                """).param("sp", SP).param("k", kod).update();
        return jdbc.sql("SELECT id FROM product WHERE sp_code=:sp AND code=:k")
                .param("sp", SP).param("k", kod).query(Long.class).single();
    }

    private long period(int bulan) {
        LocalDate mula = LocalDate.of(2026, bulan, 1);
        return jdbc.sql("SELECT period_id FROM fi_period WHERE start_dt=:s AND end_dt=:e LIMIT 1")
                .param("s", mula)
                .param("e", mula.withDayOfMonth(mula.lengthOfMonth()))
                .query(Long.class).single();
    }

    /** @param items keterangan:amaun:bulan */
    private long invois(String docNo, int bulanDok, String... items) {
        long pid = period(bulanDok);
        LocalDate d = LocalDate.of(2026, bulanDok, 1);
        List<NewDocumentLine> lines = new ArrayList<>();
        for (String it : items) {
            String[] p = it.split(":");
            LocalDate m = LocalDate.of(2026, Integer.parseInt(p[2]), 1);
            lines.add(new NewDocumentLine(produk(p[0]), acc, period(Integer.parseInt(p[2])),
                    p[0], BigDecimal.ONE, new BigDecimal(p[1]), BigDecimal.ONE,
                    new BigDecimal(p[1]), BigDecimal.ZERO,
                    m, m.withDayOfMonth(m.lengthOfMonth()), false));
        }
        long id = documents.createInvoice(new NewInvoice(SP, acc, pid,
                d, d.plusDays(14), docNo, lines)).orElseThrow();
        em.flush();
        return id;
    }

    private String teks(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFTextStripper s = new PDFTextStripper();
            s.setSortByPosition(true);
            return s.getText(doc).replaceAll("\\s+", " ");
        }
    }

    @Test
    @DisplayName("INVARIAN: baki sebelum + caj baharu = jumlah perlu dibayar")
    void ringkasanTigaLajur() {
        invois("IV-1", 7, "MAINTENANCE FEE B:70.00:7");
        long kedua = invois("IV-2", 7, "SINKING FUND B:7.00:7");

        var m = statements.invoice(SP, kedua);

        assertThat(m.balanceBefore())
                .as("invois kedua memasukkan yang pertama — bukan baki awal bulan")
                .isEqualByComparingTo("70.00");
        assertThat(m.newCharges()).isEqualByComparingTo("7.00");
        assertThat(m.totalDue()).isEqualByComparingTo("77.00");
    }

    @Test
    @DisplayName("invois PERTAMA akaun: baki sebelum sifar")
    void invoisPertama() {
        long id = invois("IV-3", 7, "INSURANCE:350.00:7");
        var m = statements.invoice(SP, id);

        assertThat(m.balanceBefore()).isEqualByComparingTo("0.00");
        assertThat(m.totalDue()).isEqualByComparingTo("350.00");
    }

    @Test
    @DisplayName("tempoh baris DISEMBUNYIKAN bila semua baris tempoh sama")
    void tempohTersembunyiBilaSama() {
        long id = invois("IV-4", 7,
                "INSURANCE:350.00:7", "MAINTENANCE FEE A:80.00:7", "SINKING FUND A:8.00:7");

        var m = statements.invoice(SP, id);
        assertThat(m.showItemPeriods())
                .as("tempoh sudah ada di kepala; mengulanginya pada setiap "
                    + "baris ialah bunyi")
                .isFalse();
    }

    @Test
    @DisplayName("tempoh baris DIPAPARKAN bila baris merangkumi bulan berbeza")
    void tempohDipaparBilaBerbeza() {
        long id = invois("IV-5", 7,
                "PARKING:50.00:1", "PARKING:50.00:2", "PARKING:50.00:3");

        var m = statements.invoice(SP, id);
        assertThat(m.showItemPeriods())
                .as("tiga baris 'PARKING' tanpa tempoh kelihatan sama — masalah "
                    + "yang sama seperti penyata (ADR 0011)")
                .isTrue();
    }

    @Test
    @DisplayName("PDF: nombor, BILL TO, item, ringkasan, typo legacy dibetulkan")
    void pdfLengkap() throws Exception {
        long id = invois("IV-6", 7, "INSURANCE:350.00:7", "MAINTENANCE FEE A:80.00:7");

        var m = statements.invoice(SP, id);
        byte[] pdf = renderer.renderInvoicePdf(m);
        Files.write(Path.of("/tmp/invois-ujian.pdf"), pdf);

        String t = teks(pdf);
        // 'IV-6' ialah TITLE yang dihantar ke createInvoice, bukan doc_no.
        // Nombor sebenar dijana oleh DocumentNumberService.
        assertThat(t).contains(m.invoiceNo());
        assertThat(t)
                .as("statementTitle ialah tajuk PENYATA; invois yang "
                    + "menggunakannya memaparkan 'Statement of Account'")
                .contains("INVOIS").doesNotContain("Statement of Account");
        assertThat(t).contains("ROZANNA BINTI RIDUAN");
        assertThat(t).contains("INSURANCE").contains("MAINTENANCE FEE A");
        assertThat(t).contains("Ringkasan Invois");
        assertThat(t).contains("Jumlah Perlu Dibayar");

        assertThat(t)
                .as("legacy menulis 'Account Namer'")
                .contains("Nama Akaun").doesNotContain("Namer");
        // Kaki: elemen running() memerlukan @page yang memanggilnya.
        // Percubaan pertama menakrifkan elemen tanpa memaparkannya, dan
        // ujian tidak menangkapnya kerana ia tidak menyemak kaki.
        assertThat(t)
                .as("kaki hilang bermakna running() tidak dipanggil oleh @page")
                .contains("Monthley.com");

        assertThat(t)
                .as("Adjustments dan New Charges Due dibuang — pelarasan ialah "
                    + "dokumen berasingan, ruangan itu sentiasa 0.00")
                .doesNotContain("Adjustment");
    }
}
