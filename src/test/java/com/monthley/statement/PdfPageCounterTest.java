package com.monthley.statement;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR 0010 P1 — DISAHKAN 25 Julai 2026: counter(page)/counter(pages)
 * berfungsi dalam kotak margin openhtmltopdf; render satu-pass memadai.
 * Dikekalkan sebagai ujian regresi: jika naik taraf openhtmltopdf
 * memecahkan counter, ujian ini yang menangkapnya.
 */
class PdfPageCounterTest {

    private static final int BARIS = 200;

    private String html() {
        StringBuilder rows = new StringBuilder();
        for (int i = 1; i <= BARIS; i++) {
            rows.append("<tr><td>").append(i)
                .append("</td><td>01/0").append((i % 9) + 1).append("/2026</td>")
                .append("<td>Maintenance ").append(i).append(", 2026</td>")
                .append("<td class='r'>30.00</td>")
                .append("<td class='r'>").append(i * 30).append(".00</td></tr>\n");
        }
        return """
            <!DOCTYPE html>
            <html><head><meta charset="utf-8"/><style>
            @page {
              size: A4;
              margin: 22mm 12mm 18mm 12mm;
              @top-center    { content: element(kepala); }
              @bottom-center { content: element(kaki); }
            }
            #kepala { position: running(kepala); font-size: 9pt; font-weight: bold; }
            #kaki   { position: running(kaki);   font-size: 8pt; }
            .pg:before  { content: counter(page); }
            .tot:before { content: counter(pages); }
            body  { font-family: sans-serif; font-size: 8pt; }
            table { width: 100%; border-collapse: collapse; }
            thead { display: table-header-group; }
            th, td { border-bottom: 0.5pt solid #999; padding: 2pt 3pt; }
            .r { text-align: right; }
            </style></head><body>
            <div id="kepala">PENYATA AKAUN \u2014 SPIKE</div>
            <div id="kaki">Page <span class="pg"></span> of <span class="tot"></span></div>
            <table><thead><tr>
              <th>No.</th><th>Tarikh</th><th>Keterangan</th>
              <th class="r">Amaun</th><th class="r">Baki</th>
            </tr></thead><tbody>
            """ + rows + """
            </tbody></table></body></html>
            """;
    }

    @Test
    @DisplayName("counter(pages) dalam kotak margin — 'Page N of M' betul pada setiap muka")
    void pageCounterDalamKotakMargin() throws Exception {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        PdfRendererBuilder b = new PdfRendererBuilder();
        b.useFastMode();
        b.withHtmlContent(html(), null);
        b.toStream(os);
        b.run();

        byte[] pdf = os.toByteArray();
        Files.write(Path.of("/tmp/spike-page-counter.pdf"), pdf);

        try (PDDocument doc = Loader.loadPDF(pdf)) {
            int jumlahMuka = doc.getNumberOfPages();
            assertThat(jumlahMuka)
                .as("200 baris mesti pecah kepada beberapa muka")
                .isGreaterThan(3);

            PDFTextStripper stripper = new PDFTextStripper();
            // kandungan :before dilukis berasingan; susun ikut kedudukan
            // visual, bukan turutan lukisan
            stripper.setSortByPosition(true);
            for (int muka = 1; muka <= jumlahMuka; muka++) {
                stripper.setStartPage(muka);
                stripper.setEndPage(muka);
                String teks = stripper.getText(doc).replaceAll("\\s+", " ");
                assertThat(teks)
                    .as("muka %d daripada %d", muka, jumlahMuka)
                    .contains("Page " + muka + " of " + jumlahMuka);
            }
            System.out.println(">>> SPIKE OK: " + jumlahMuka + " muka, counter(pages) betul");
        }
    }
}
