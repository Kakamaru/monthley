package com.monthley.statement;

import com.monthley.ledger.internal.ChartOfAccountSeeder;
import com.monthley.document.api.DocumentPort;
import com.monthley.document.api.NewDocumentLine;
import com.monthley.document.api.NewInvoice;
import com.monthley.payment.api.NewPayment;
import com.monthley.payment.api.PaymentMethod;
import com.monthley.payment.api.PaymentPort;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Resit PDF.
 *
 * DUA TARIKH, dua maksud — 'Tarikh Resit' ialah bila bayaran diterima,
 * 'Tarikh Dikeluarkan' ialah bila resit dicetak. Legacy membezakannya
 * dan pembetulan tarikh bayaran (374e3c4) menjadikannya mungkin.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ReceiptPdfTest {

    private static final String SP = "SPRC";

    @Autowired StatementPort statements;
    @Autowired StatementRenderPort renderer;
    @Autowired PaymentPort payment;
    @Autowired DocumentPort documents;
    @Autowired ChartOfAccountSeeder seeder;
    @Autowired JdbcClient jdbc;
    @PersistenceContext EntityManager em;

    private long acc;

    @BeforeEach
    void seed() {
        jdbc.sql("""
                INSERT IGNORE INTO service_provider (sp_code, name, status, version)
                VALUES (:sp, 'JMB Ujian Resit', 'ACTIVE', 0)
                """).param("sp", SP).update();
        seeder.seedFor(SP);
        jdbc.sql("""
                INSERT IGNORE INTO sp_document_setting (sp_code, version)
                VALUES (:sp, 0)
                """).param("sp", SP).update();
        jdbc.sql("""
                INSERT IGNORE INTO sp_billing_setting (sp_code, currency, language, version)
                VALUES (:sp, 'MYR', 'ms', 0)
                """).param("sp", SP).update();

        String no = "RC-" + System.nanoTime();
        jdbc.sql("""
                INSERT INTO account (sp_code, account_no, account_name, billto_name, status)
                VALUES (:sp, :no, 'SITI RAHMAH', 'SITI RAHMAH', 'ACTIVE')
                """).param("sp", SP).param("no", no).update();
        acc = jdbc.sql("SELECT id FROM account WHERE sp_code=:sp AND account_no=:no")
                .param("sp", SP).param("no", no).query(Long.class).single();

        TenantContext.set(SP);
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    private long periodJulai() {
        return jdbc.sql("SELECT period_id FROM fi_period "
                        + "WHERE start_dt='2026-07-01' AND end_dt='2026-07-31' LIMIT 1")
                .query(Long.class).single();
    }

    private void invois(String docNo, String amaun) {
        long pid = periodJulai();
        var line = new NewDocumentLine(null, acc, pid, "Yuran Penyelenggaraan",
                BigDecimal.ONE, new BigDecimal(amaun), BigDecimal.ONE,
                new BigDecimal(amaun), BigDecimal.ZERO,
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), false);
        documents.createInvoice(new NewInvoice(SP, acc, pid,
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31),
                docNo, List.of(line))).orElseThrow();
    }

    private long bayar(String amaun, LocalDate tarikh, PaymentMethod cara, String ref) {
        var r = payment.receivePayment(new NewPayment(SP, acc, new BigDecimal(amaun),
                cara, ref, List.of(), null, tarikh));
        em.flush();
        return r.receiptDocumentId();
    }

    private String teks(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFTextStripper s = new PDFTextStripper();
            s.setSortByPosition(true);
            return s.getText(doc).replaceAll("\\s+", " ");
        }
    }

    @Test
    @DisplayName("resit memaparkan DUA tarikh berbeza: terima lawan dikeluarkan")
    void duaTarikh() throws Exception {
        invois("RC-INV-1", "150.00");
        em.flush();
        LocalDate tigaHariLepas = LocalDate.now().minusDays(3);
        long resitId = bayar("150.00", tigaHariLepas, PaymentMethod.CASH, null);

        var m = statements.receipt(SP, resitId);

        assertThat(m.receiptDate())
                .as("bila bayaran DITERIMA")
                .isEqualTo(tigaHariLepas);
        assertThat(m.issuedAt().toLocalDate())
                .as("bila resit DICETAK — pembetulan 374e3c4 menjadikan ini mungkin")
                .isEqualTo(LocalDate.now());
    }

    @Test
    @DisplayName("INVARIAN: item + advance = amaun dibayar")
    void itemCampurAdvanceSamaAmaun() {
        invois("RC-INV-2", "100.00");
        em.flush();
        // Bayar RM250 atas invois RM100 — RM150 menjadi advance.
        long resitId = bayar("250.00", null, PaymentMethod.TRANSFER, "TRF-99");

        var m = statements.receipt(SP, resitId);

        BigDecimal jumlahItem = m.items().stream()
                .map(i -> i.amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(jumlahItem.add(m.advance()))
                .as("tanpa baris advance, resit menunjukkan RM100 sedangkan "
                    + "RM250 diterima")
                .isEqualByComparingTo(m.amountPaid());
        assertThat(m.advance()).isEqualByComparingTo("150.00");
    }

    @Test
    @DisplayName("PDF: nombor resit, kaedah dalam bahasa SP, dan item")
    void pdfLengkap() throws Exception {
        invois("RC-INV-3", "80.00");
        em.flush();
        long resitId = bayar("80.00", null, PaymentMethod.CASH, null);

        var m = statements.receipt(SP, resitId);
        byte[] pdf = renderer.renderReceiptPdf(m);
        Files.write(Path.of("/tmp/resit-ujian.pdf"), pdf);

        String t = teks(pdf);
        assertThat(t).contains("RESIT");
        assertThat(t).contains(m.receiptNo());
        assertThat(t).contains("SITI RAHMAH");
        assertThat(t).contains("Yuran Penyelenggaraan");
        assertThat(t)
                .as("pelanggan tidak sepatutnya melihat nama enum 'CASH'")
                .contains("Tunai")
                .doesNotContain("CASH");
    }

    @Test
    @DisplayName("No. Rujukan muncul untuk pindahan, tiada untuk tunai")
    void rujukanBersyarat() throws Exception {
        invois("RC-INV-4", "60.00");
        em.flush();

        long transfer = bayar("60.00", null, PaymentMethod.TRANSFER, "TRF-12345");
        String tTransfer = teks(renderer.renderReceiptPdf(statements.receipt(SP, transfer)));
        assertThat(tTransfer).contains("TRF-12345").contains("No. Rujukan");

        long tunai = bayar("40.00", null, PaymentMethod.CASH, null);
        String tTunai = teks(renderer.renderReceiptPdf(statements.receipt(SP, tunai)));
        assertThat(tTunai)
                .as("baris kosong tidak dicetak — legacy memaparkannya hanya "
                    + "apabila ada nilai")
                .doesNotContain("No. Rujukan");
    }
}
