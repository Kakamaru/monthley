package com.monthley.statement.internal;

import com.monthley.ledger.api.CollectionReportPort;
import com.monthley.statement.api.StatementHeader;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.io.ByteArrayOutputStream;

/**
 * Senarai Kutipan sebagai PDF.
 *
 * SATU templat untuk dua bentuk. Fail berasingan bermakna header SP,
 * gaya dan kaki disalin dua kali, dan salinan kedua akan menyimpang —
 * tepat masalah yang kami baru selesaikan dengan .more-item.
 *
 * Fon dikongsi dengan StatementPdfWriter: resit, penyata dan laporan
 * mesti kelihatan sama. Fon MESTI dibenamkan — Helvetica terbina PDFBox
 * ialah WinAnsi sahaja, dan aksara di luarnya dilukis sebagai '#' TANPA
 * ralat.
 */
@Component
class CollectionPdfWriter {

    private static final String TEMPLATE = "statement/collection";

    private final TemplateEngine templateEngine;
    private final StatementPdfWriter fonPembekal;

    CollectionPdfWriter(TemplateEngine templateEngine, StatementPdfWriter fonPembekal) {
        this.templateEngine = templateEngine;
        this.fonPembekal = fonPembekal;
    }

    byte[] render(CollectionReportPort.Result r, StatementHeader h) {
        Context ctx = new Context();
        ctx.setVariable("r", r);
        ctx.setVariable("h", h);
        ctx.setVariable("fmt", new StatementFormatter(h.language(), h.dateFormat()));

        String html = templateEngine.process(TEMPLATE, ctx);

        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            PdfRendererBuilder b = new PdfRendererBuilder();
            b.useFastMode();
            fonPembekal.daftarFon(b);
            b.withHtmlContent(html, null);
            b.toStream(os);
            b.run();
            return os.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Gagal render laporan kutipan", e);
        }
    }
}
