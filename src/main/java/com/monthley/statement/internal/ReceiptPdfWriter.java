package com.monthley.statement.internal;

import com.monthley.statement.api.ReceiptModel;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.io.ByteArrayOutputStream;

/**
 * Resit sebagai PDF.
 *
 * Berkongsi infrastruktur dengan StatementPdfWriter: fon terbenam yang
 * sama, enjin templat yang sama, pemformat yang sama. Membina semula
 * dalam modul payment bermakna dua penulis PDF, dan yang kedua akan
 * menyimpang.
 *
 * Fon MESTI dibenamkan — lihat StatementPdfWriter untuk sebabnya:
 * Helvetica terbina PDFBox ialah WinAnsi sahaja, dan aksara di luarnya
 * dilukis sebagai '#' tanpa ralat.
 */
@Component
class ReceiptPdfWriter {

    private static final String TEMPLATE = "statement/receipt";

    private final TemplateEngine templateEngine;
    private final StatementPdfWriter fonts;

    ReceiptPdfWriter(TemplateEngine templateEngine, StatementPdfWriter fonts) {
        this.templateEngine = templateEngine;
        this.fonts = fonts;
    }

    byte[] render(ReceiptModel m) {
        var h = m.header();
        Context ctx = new Context();
        ctx.setVariable("m", m);
        ctx.setVariable("h", h);
        ctx.setVariable("fmt", new StatementFormatter(h.language(), h.dateFormat()));

        String html = templateEngine.process(TEMPLATE, ctx);

        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            PdfRendererBuilder b = new PdfRendererBuilder();
            b.useFastMode();
            // Senarai fon dikongsi, bukan disalin — resit dan penyata mesti
            // kelihatan sama.
            fonts.daftarFon(b);
            b.withHtmlContent(html, null);
            b.toStream(os);
            b.run();
            return os.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Gagal render resit " + m.receiptNo(), e);
        }
    }
}
