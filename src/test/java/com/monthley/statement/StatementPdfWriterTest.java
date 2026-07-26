package com.monthley.statement;

import com.monthley.statement.api.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Render PDF (ADR 0010 P3b). */
@SpringBootTest
@ActiveProfiles("test")
class StatementPdfWriterTest {

    @Autowired StatementRenderPort writer;

    private StatementHeader header(String spName, String lang, boolean lengkap) {
        return new StatementHeader(
                "Penyata Akaun", "MYR", lang, "dd/MM/yyyy", "SST",
                spName,
                lengkap ? "199801234567" : null,
                lengkap ? "Blok A10-G-10" : null,
                lengkap ? "Jalan Mewah 4" : null,
                null,
                lengkap ? "68000" : null,
                lengkap ? "Ampang" : null,
                lengkap ? "Selangor" : null,
                lengkap ? "Malaysia" : null,
                lengkap ? "0342801287" : null,
                lengkap ? "www.contoh.com" : null,
                lengkap ? "admin@contoh.com" : null,
                null, null, null,
                lengkap ? "MAYBANK" : null,
                lengkap ? "514383405851" : null,
                lengkap ? "BP BLK A7" : null,
                "FA10-1-10", "YONG YOENG CHAN", "YONG YOENG CHAN",
                "YONG YOENG CHAN",
                lengkap ? "eva@contoh.com" : null,
                lengkap ? "No. 13-15 Puncak Saujana" : null,
                null, null,
                lengkap ? "43000" : null,
                lengkap ? "Selangor" : null,
                lengkap ? "Malaysia" : null);
    }

    private StatementModel model(StatementHeader h) {
        var rows = List.of(
                new StatementRow(LocalDate.of(2026, 1, 10), "INVOICE", "INV-001",
                        "Penyelenggaraan & Kumpulan Wang Langsai", null, false,
                        new BigDecimal("106.00"), new BigDecimal("106.00"), List.of()),
                new StatementRow(LocalDate.of(2026, 2, 10), "INVOICE", "INV-002",
                        "Tersilap jana", "Dibatalkan oleh admin", true,
                        BigDecimal.ZERO, new BigDecimal("106.00"), List.of()),
                new StatementRow(LocalDate.of(2026, 3, 15), "RECEIPT", "RCP-001",
                        "Bayaran diterima", null, false,
                        new BigDecimal("-106.00"), BigDecimal.ZERO,
                        List.of(new StatementMatch("INV-001", "Penyelenggaraan",
                                        LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31),
                                        new BigDecimal("100.00")),
                                new StatementMatch("INV-001", "Kumpulan Wang Langsai",
                                        LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28),
                                        new BigDecimal("6.00")))));
        return new StatementModel(h, "TST", 1L,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                BigDecimal.ZERO, rows, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private String teks(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFTextStripper s = new PDFTextStripper();
            s.setSortByPosition(true);
            return s.getText(doc).replaceAll("\\s+", " ");
        }
    }

    @Test
    @DisplayName("nama SP dengan & tidak memecahkan render — data pengguna sebenar")
    void ampersandSelamat() throws Exception {
        byte[] pdf = writer.renderPdf(model(
                header("Maintenance & Sinking Fund <JMB>", "ms", true)));
        Files.write(Path.of("/tmp/statement-ampersand.pdf"), pdf);

        // Uji ESCAPING, bukan susun atur. Teks sel membalut baris, dan
        // setSortByPosition membaca merentas baris dahulu — jadi frasa
        // panjang tidak wujud sebagai teks bersebelahan. Yang penting
        // ialah ampersand terselamat dan render tidak gagal.
        String t = teks(pdf);
        assertThat(t).contains("Maintenance & Sinking Fund");
        // Serpihan PENDEK: teks sel membalut baris dan saiz fon boleh
        // berubah, jadi frasa panjang bukan ujian escaping — ia ujian
        // susun atur yang menyamar.
        assertThat(t).contains("Penyelenggaraan &");
        assertThat(t).contains("<JMB>");
        assertThat(t).doesNotContain("&amp;").doesNotContain("&lt;");
    }

    @Test
    @DisplayName("medan NULL dilangkau, bukan dicetak sebagai 'null'")
    void nullDilangkau() throws Exception {
        byte[] pdf = writer.renderPdf(model(header("JMB Ringkas", "ms", false)));
        assertThat(teks(pdf)).doesNotContain("null");
    }

    @Test
    @DisplayName("sub-baris muncul dengan tempoh disetempatkan mengikut language")
    void subBarisDanBahasa() throws Exception {
        assertThat(teks(writer.renderPdf(model(header("JMB Melayu", "ms", true)))))
                .contains("Januari 2026").contains("Februari 2026");
        assertThat(teks(writer.renderPdf(model(header("JMB English", "en", true)))))
                .contains("January 2026").contains("February 2026");
    }

    @Test
    @DisplayName("dokumen batal ditanda; amaun sifar tidak menggerakkan baki")
    void batalDitanda() throws Exception {
        String t = teks(writer.renderPdf(model(header("JMB", "ms", true))));
        assertThat(t).contains("Dibatalkan oleh admin");
        assertThat(t).contains("Muka 1 / 1");

        // Tanda mesti jadi AKSARA, bukan teks '\\u2713'. Escape \\u ialah
        // escape Java; dalam templat ia dicetak secara harfiah.
        assertThat(t).doesNotContain("u2713").doesNotContain("u2715");
        assertThat(t).contains("\u2713").contains("\u2715");
    }
}
