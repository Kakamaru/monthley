package com.monthley.statement;

import com.monthley.statement.api.*;
import org.apache.poi.ss.usermodel.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Penulis XLSX (ADR 0010 P5).
 *
 * Ujian ini turut mengesahkan reka bentuk 'satu model, banyak penulis'
 * (keputusan 7): jika XLSX memerlukan medan yang tiada dalam
 * StatementModel, model itu tidak lengkap.
 */
@SpringBootTest
@ActiveProfiles("test")
class StatementXlsxWriterTest {

    @Autowired StatementRenderPort writer;

    private StatementModel model() {
        var h = new StatementHeader(
                "Penyata Akaun", "MYR", "ms", "dd/MM/yyyy", "SST",
                "JMB Ujian", null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                "M99", "SITI AMINAH", "SITI AMINAH", "SITI AMINAH",
                null, null, null, null, null, null, null);

        var rows = List.of(
                new StatementRow(LocalDate.of(2026, 1, 10), "INVOICE", "INV-X1",
                        "Parking", null, false, null, null, new BigDecimal("100.00"),
                        new BigDecimal("100.00"), new BigDecimal("100.00"), List.of()),
                // Batal: amaun SIFAR pada lajur baki, tetapi 250.00 asal
                // kekal dipaparkan (V53).
                new StatementRow(LocalDate.of(2026, 2, 10), "INVOICE", "INV-X2",
                        "Tersilap", "Dibatalkan", true,
                        java.time.LocalDateTime.of(2026, 2, 11, 9, 30), "7",
                        new BigDecimal("250.00"),
                        BigDecimal.ZERO, new BigDecimal("100.00"), List.of()),
                new StatementRow(LocalDate.of(2026, 3, 1), "RECEIPT", "RCP-X1",
                        "Bayaran", null, false, null, null, new BigDecimal("-100.00"),
                        new BigDecimal("-100.00"), BigDecimal.ZERO,
                        List.of(new StatementMatch("INV-X1", "Parking",
                                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31),
                                new BigDecimal("100.00")))));

        return new StatementModel(h, "TST", 99L,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                BigDecimal.ZERO, rows, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private Workbook buka(byte[] b) throws Exception {
        return WorkbookFactory.create(new ByteArrayInputStream(b));
    }

    @Test
    @DisplayName("dua sheet RATA: Transaksi dan Padanan")
    void duaSheetRata() throws Exception {
        byte[] x = writer.renderXlsx(model());
        Files.write(Path.of("/tmp/penyata-ujian.xlsx"), x);

        try (Workbook wb = buka(x)) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(2);
            assertThat(wb.getSheetName(0)).isEqualTo("Transaksi");
            assertThat(wb.getSheetName(1)).isEqualTo("Padanan");
        }
    }

    @Test
    @DisplayName("padanan pada sheet SENDIRI — sub-baris pecah apabila pengguna sort")
    void padananBerasingan() throws Exception {
        try (Workbook wb = buka(writer.renderXlsx(model()))) {
            Sheet p = wb.getSheet("Padanan");

            // Satu padanan dalam model; ia mesti muncul sebagai baris penuh
            // dengan KEDUA-DUA nombor dokumen, bukan baris anak berinden.
            String semua = teks(p);
            assertThat(semua).contains("RCP-X1").contains("INV-X1").contains("Parking");

            // Tiada lajur baki pada sheet padanan: alokasi tidak menggerakkan
            // baki (ADR 0009).
            assertThat(teksBaris(p, 2)).doesNotContain("Baki");
        }
    }

    @Test
    @DisplayName("tarikh ditulis sebagai TARIKH, bukan teks")
    void tarikhBukanTeks() throws Exception {
        try (Workbook wb = buka(writer.renderXlsx(model()))) {
            Sheet t = wb.getSheet("Transaksi");
            Cell c = cariBarisPertamaData(t).getCell(1);

            assertThat(c.getCellType())
                    .as("teks menyusun ikut abjad — '10/01' sebelum '02/02'")
                    .isEqualTo(CellType.NUMERIC);
            assertThat(DateUtil.isCellDateFormatted(c)).isTrue();
        }
    }

    @Test
    @DisplayName("corak tarikh IKUT tetapan SP, bukan ditulis keras")
    void corakTarikhIkutTetapan() throws Exception {
        // model() menggunakan dd/MM/yyyy
        try (Workbook wb = buka(writer.renderXlsx(model()))) {
            String f = cariBarisPertamaData(wb.getSheet("Transaksi"))
                    .getCell(1).getCellStyle().getDataFormatString();
            assertThat(f).isEqualTo("dd/mm/yyyy");
        }

        // SP yang menggunakan corak lain mesti mendapat corak itu — jika
        // tidak PDF dan XLSX menunjukkan tarikh berbeza untuk akaun yang
        // sama (CASE-008).
        var m = model();
        var mLain = new StatementModel(
                m.header().withDateFormat("dd MMM yyyy"),
                m.spCode(), m.accountId(), m.from(), m.to(),
                m.openingBalance(), m.rows(), m.closingBalance(), m.arrears());

        try (Workbook wb = buka(writer.renderXlsx(mLain))) {
            String f = cariBarisPertamaData(wb.getSheet("Transaksi"))
                    .getCell(1).getCellStyle().getDataFormatString();
            assertThat(f)
                    .as("corak ditulis keras bermakna tetapan SP diabaikan")
                    .isEqualTo("dd mmm yyyy");
        }
    }

    @Test
    @DisplayName("amaun NUMERIC supaya boleh dijumlah dan dipivot")
    void amaunNumeric() throws Exception {
        try (Workbook wb = buka(writer.renderXlsx(model()))) {
            Row r = cariBarisPertamaData(wb.getSheet("Transaksi"));
            // Amaun dan Baki bergeser ke 9 dan 10 selepas dua lajur
            // pembatalan disisipkan (V53).
            assertThat(r.getCell(9).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(r.getCell(9).getNumericCellValue()).isEqualTo(100.00);
            assertThat(r.getCell(10).getNumericCellValue()).isEqualTo(100.00);

            // Baris AKTIF: dua lajur pembatalan kosong, bukan sifar.
            // Sifar akan disertakan dalam jumlah kalau pengguna memilih
            // lajur Amaun Asal.
            assertThat(r.getCell(7)).isNull();
            assertThat(r.getCell(8)).isNull();
        }
    }

    @Test
    @DisplayName("dokumen batal DIPAPARKAN dengan status, amaun sifar")
    void batalDipapar() throws Exception {
        try (Workbook wb = buka(writer.renderXlsx(model()))) {
            String t = teks(wb.getSheet("Transaksi"));
            assertThat(t).contains("INV-X2").contains("Batal").contains("Dibatalkan");
            // Amaun ASAL mesti kekal — penyata yang menyembunyikan berapa
            // dokumen batal SEPATUTNYA tidak boleh diaudit.
            assertThat(t).contains("250");

            // Amaun Asal mesti NUMERIC, bukan teks — XLSX dibuka untuk
            // ditapis dan dijumlah.
            Row b = cariBarisBatal(wb.getSheet("Transaksi"));
            assertThat(b.getCell(8).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(b.getCell(8).getNumericCellValue()).isEqualTo(250.00);
            assertThat(b.getCell(9).getNumericCellValue()).isEqualTo(0.00);
            assertThat(b.getCell(7)).isNotNull();
        }
    }

    // ── bantuan ──────────────────────────────────────────────────────

    /** Baris INV-X2 — satu-satunya dokumen batal dalam model ujian. */
    private Row cariBarisBatal(Sheet sh) {
        for (Row r : sh) {
            Cell c3 = r.getCell(3);
            if (c3 != null && c3.getCellType() == CellType.STRING
                    && "INV-X2".equals(c3.getStringCellValue())) {
                return r;
            }
        }
        throw new IllegalStateException("baris batal tidak dijumpai");
    }

    private Row cariBarisPertamaData(Sheet sh) {
        for (Row r : sh) {
            Cell c0 = r.getCell(0);
            if (c0 != null && c0.getCellType() == CellType.NUMERIC
                    && c0.getNumericCellValue() == 1d) {
                return r;
            }
        }
        throw new IllegalStateException("baris data pertama tidak dijumpai");
    }

    private String teks(Sheet sh) {
        StringBuilder sb = new StringBuilder();
        for (Row r : sh) sb.append(teksBaris(sh, r.getRowNum())).append('\n');
        return sb.toString();
    }

    private String teksBaris(Sheet sh, int no) {
        Row r = sh.getRow(no);
        if (r == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Cell c : r) {
            if (c.getCellType() == CellType.STRING) sb.append(c.getStringCellValue()).append(' ');
            else if (c.getCellType() == CellType.NUMERIC) sb.append(c.getNumericCellValue()).append(' ');
        }
        return sb.toString();
    }
}
