package com.monthley.statement.internal;

import com.monthley.statement.api.StatementModel;
import com.monthley.statement.api.StatementRow;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Penyata sebagai XLSX — model yang SAMA seperti PDF.
 *
 * DUA SHEET RATA, bukan sub-baris berinden (ADR 0010 keputusan 8).
 * Sub-baris berfungsi pada kertas kerana kertas tidak boleh disusun semula;
 * dalam Excel, sekali pengguna sort mengikut mana-mana lajur, anak terpisah
 * daripada induk dan penyata menjadi karut.
 *
 *   Transaksi — satu baris per DOKUMEN, dengan lajur baki
 *   Padanan   — satu baris per ALOKASI, dengan nombor kedua-dua dokumen,
 *               TIADA lajur baki
 *
 * Sheet Padanan menjawab soalan yang legacy tidak pernah boleh jawab melalui
 * pivot: resit mana membayar invois mana, dan untuk produk apa.
 *
 * Tarikh ditulis sebagai TARIKH sebenar, bukan teks. Pengguna menapis dan
 * menyusun mengikutnya; teks menyusun mengikut abjad dan '10/01' datang
 * sebelum '02/02'.
 */
@Component
class StatementXlsxWriter {

    byte[] renderXlsx(StatementModel m) {
        try (Workbook wb = new XSSFWorkbook();
             ByteArrayOutputStream os = new ByteArrayOutputStream()) {

            Gaya g = new Gaya(wb, m.header().dateFormat());
            transaksi(wb, g, m);
            padanan(wb, g, m);
            wb.write(os);
            return os.toByteArray();

        } catch (Exception e) {
            throw new IllegalStateException(
                    "Gagal render XLSX untuk akaun " + m.accountId(), e);
        }
    }

    // ── Sheet 1: Transaksi ───────────────────────────────────────────

    private void transaksi(Workbook wb, Gaya g, StatementModel m) {
        Sheet sh = wb.createSheet("Transaksi");
        var h = m.header();
        int r = 0;

        r = kepala(sh, g, m, r);

        // Dua lajur pembatalan sebagai lajur SENDIRI, bukan teks dalam
        // Catatan: XLSX dibuka untuk ditapis dan dijumlah, dan nombor
        // dalam sel teks tidak boleh dikira. Kosong untuk baris aktif.
        String[] tajuk = {"No.", "Tarikh", "Dokumen", "No. Dokumen",
                          "Keterangan", "Catatan", "Status",
                          "Dibatalkan Pada", "Amaun Asal", "Amaun", "Baki"};
        int barisTajuk = r;
        Row hd = sh.createRow(r++);
        for (int c = 0; c < tajuk.length; c++) {
            Cell cell = hd.createCell(c);
            cell.setCellValue(tajuk[c]);
            cell.setCellStyle(g.tajuk);
        }

        int no = 1;
        for (StatementRow row : m.rows()) {
            Row x = sh.createRow(r++);
            x.createCell(0).setCellValue(no++);
            tarikh(x, 1, row.docDate(), g);
            x.createCell(2).setCellValue(row.docType());
            x.createCell(3).setCellValue(row.docNo());
            x.createCell(4).setCellValue(row.description());
            x.createCell(5).setCellValue(row.remark() == null ? "" : row.remark());
            x.createCell(6).setCellValue(row.cancelled() ? "Batal" : "Aktif");
            // Tarikh sebenar, bukan teks — prinsip sama seperti lajur
            // Tarikh. Jam digugurkan; masa tepat ada pada PDF.
            if (row.cancelled() && row.cancelledAt() != null) {
                tarikh(x, 7, row.cancelledAt().toLocalDate(), g);
                wang(x, 8, row.originalAmount(), g);
            }
            wang(x, 9, row.amount(), g);
            wang(x, 10, row.runningBalance(), g);
        }

        // Baris jumlah — pengguna menjangka melihatnya tanpa menulis formula.
        Row jum = sh.createRow(r);
        Cell lbl = jum.createCell(0);
        lbl.setCellValue("Baki Akhir (" + h.currency() + ")");
        lbl.setCellStyle(g.tebal);
        sh.addMergedRegion(new CellRangeAddress(r, r, 0, 9));
        Cell nilai = jum.createCell(10);
        nilai.setCellValue(m.closingBalance().doubleValue());
        nilai.setCellStyle(g.wangTebal);

        // autoSizeColumn mengambil kira SEMUA sel dalam lajur, termasuk
        // tajuk besar pada baris 1 dan julat tarikh pada blok kepala. Itu
        // menjadikan lajur 'No.' selebar 'Penyata Akaun — JMB Ujian'.
        // Lebar ditetapkan secara eksplisit; unit POI ialah 1/256 aksara.
        int[] lebar = {6, 12, 12, 16, 30, 22, 9, 16, 14, 14, 14};
        for (int c = 0; c < lebar.length; c++) {
            sh.setColumnWidth(c, lebar[c] * 256);
        }
        // Beku di bawah baris tajuk supaya ia kekal kelihatan semasa skrol.
        sh.createFreezePane(0, barisTajuk + 1);
    }

    // ── Sheet 2: Padanan ─────────────────────────────────────────────

    private void padanan(Workbook wb, Gaya g, StatementModel m) {
        Sheet sh = wb.createSheet("Padanan");
        int r = 0;

        Row nota = sh.createRow(r++);
        nota.createCell(0).setCellValue(
                "Resit mana membayar invois mana. Alokasi TIDAK menggerakkan "
                + "baki — baki digerakkan oleh dokumen.");
        r++;

        String[] tajuk = {"Tarikh", "Dokumen", "No. Dokumen",
                          "Dipadankan Dengan", "Produk", "Tempoh", "Amaun"};
        Row hd = sh.createRow(r++);
        for (int c = 0; c < tajuk.length; c++) {
            Cell cell = hd.createCell(c);
            cell.setCellValue(tajuk[c]);
            cell.setCellStyle(g.tajuk);
        }

        int bil = 0;
        for (StatementRow row : m.rows()) {
            for (var p : row.matches()) {
                Row x = sh.createRow(r++);
                tarikh(x, 0, row.docDate(), g);
                x.createCell(1).setCellValue(row.docType());
                x.createCell(2).setCellValue(row.docNo());
                x.createCell(3).setCellValue(p.documentNo() == null ? "" : p.documentNo());
                x.createCell(4).setCellValue(p.productName() == null ? "" : p.productName());
                x.createCell(5).setCellValue(tempohTeks(p.periodStart(), p.periodEnd()));
                wang(x, 6, p.amount(), g);
                bil++;
            }
        }

        if (bil == 0) {
            sh.createRow(r).createCell(0).setCellValue("Tiada padanan dalam tempoh ini.");
        }
        int[] lebar = {12, 12, 16, 18, 26, 24, 14};
        for (int c = 0; c < lebar.length; c++) {
            sh.setColumnWidth(c, lebar[c] * 256);
        }
        sh.createFreezePane(0, 3);
    }

    // ── Bantuan ──────────────────────────────────────────────────────

    private int kepala(Sheet sh, Gaya g, StatementModel m, int r) {
        var h = m.header();
        Cell t = sh.createRow(r++).createCell(0);
        t.setCellValue(h.statementTitle() + " — " + h.spName());
        t.setCellStyle(g.besar);

        baris(sh, r++, "Akaun", h.accountNo());
        baris(sh, r++, "Pemegang Akaun", h.accountName());
        baris(sh, r++, "Tempoh", m.from() + " hingga " + m.to());
        barisWang(sh, g, r++, "Baki Awal", m.openingBalance());
        barisWang(sh, g, r++, "Baki Akhir", m.closingBalance());
        barisWang(sh, g, r++, "Tunggakan", m.arrears());
        r++;
        return r;
    }

    private void baris(Sheet sh, int r, String label, String nilai) {
        Row x = sh.createRow(r);
        x.createCell(0).setCellValue(label);
        x.createCell(1).setCellValue(nilai == null ? "" : nilai);
    }

    private void barisWang(Sheet sh, Gaya g, int r, String label, BigDecimal v) {
        Row x = sh.createRow(r);
        x.createCell(0).setCellValue(label);
        wang(x, 1, v, g);
    }

    private void wang(Row x, int c, BigDecimal v, Gaya g) {
        Cell cell = x.createCell(c);
        cell.setCellValue(v == null ? 0d : v.doubleValue());
        cell.setCellStyle(g.wang);
    }

    private void tarikh(Row x, int c, LocalDate d, Gaya g) {
        Cell cell = x.createCell(c);
        if (d != null) cell.setCellValue(d);
        cell.setCellStyle(g.tarikh);
    }

    private String tempohTeks(LocalDate a, LocalDate b) {
        if (a == null) return "";
        return b == null ? a.toString() : a + " - " + b;
    }

    /**
     * Terjemah corak Java kepada corak Excel.
     *
     * Kedua-duanya serupa tetapi tidak sama: Excel tidak memahami 'yyyy'
     * dalam semua konteks dan menggunakan huruf kecil untuk bulan.
     * Corak yang tidak dikenali jatuh ke dd/mm/yyyy, sama seperti
     * StatementFormatter jatuh ke dd/MM/yyyy.
     */
    private static String excelDateFormat(String java) {
        if (java == null || java.isBlank()) return "dd/mm/yyyy";
        return java.replace("MMMM", "mmmm")
                   .replace("MMM", "mmm")
                   .replace("MM", "mm")
                   .replace("dd", "dd")
                   .replace("yyyy", "yyyy");
    }

    /** Gaya dicipta SEKALI per buku — POI mengehadkan bilangan gaya. */
    private static final class Gaya {
        final CellStyle tajuk, wang, wangTebal, tarikh, tebal, besar;

        Gaya(Workbook wb, String corakTarikh) {
            DataFormat fmt = wb.createDataFormat();

            Font fTebal = wb.createFont();
            fTebal.setBold(true);

            Font fBesar = wb.createFont();
            fBesar.setBold(true);
            fBesar.setFontHeightInPoints((short) 14);

            tajuk = wb.createCellStyle();
            tajuk.setFont(fTebal);
            tajuk.setBorderBottom(BorderStyle.THIN);

            tebal = wb.createCellStyle();
            tebal.setFont(fTebal);

            besar = wb.createCellStyle();
            besar.setFont(fBesar);

            // Negatif dalam kurungan, ikut konvensyen perakaunan — sama
            // seperti PDF.
            wang = wb.createCellStyle();
            wang.setDataFormat(fmt.getFormat("#,##0.00;(#,##0.00)"));

            wangTebal = wb.createCellStyle();
            wangTebal.setDataFormat(fmt.getFormat("#,##0.00;(#,##0.00)"));
            wangTebal.setFont(fTebal);

            // Ikut sp_billing_setting.date_format, sama seperti PDF. Corak
            // yang ditulis keras di sini bermakna PDF dan XLSX menunjukkan
            // tarikh berbeza untuk SP yang tidak menggunakan dd/MM/yyyy —
            // corak CASE-008, kali ini diperkenalkan oleh penulis Excel.
            //
            // Java DateTimeFormatter dan Excel menggunakan huruf berbeza:
            // Java 'MM' ialah bulan, Excel 'mm' ialah bulan dalam konteks
            // tarikh tetapi minit selepas jam. Tiada jam di sini, jadi
            // huruf kecil selamat.
            tarikh = wb.createCellStyle();
            tarikh.setDataFormat(fmt.getFormat(excelDateFormat(corakTarikh)));
        }
    }
}
