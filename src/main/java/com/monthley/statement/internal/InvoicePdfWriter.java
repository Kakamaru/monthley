package com.monthley.statement.internal;

import com.monthley.statement.api.InvoiceModel;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.io.ByteArrayOutputStream;

/**
 * Invois sebagai PDF.
 *
 * Berkongsi fon dengan StatementPdfWriter melalui daftarFon() — invois,
 * resit dan penyata mesti kelihatan sama, dan senarai fon yang disalin
 * akan menyimpang.
 */
@Component
class InvoicePdfWriter {

    private static final String TEMPLATE = "statement/invoice";

    private final TemplateEngine templateEngine;
    private final StatementPdfWriter fonts;

    InvoicePdfWriter(TemplateEngine templateEngine, StatementPdfWriter fonts) {
        this.templateEngine = templateEngine;
        this.fonts = fonts;
    }

    byte[] render(InvoiceModel m) {
        var h = m.header();
        Context ctx = new Context();
        ctx.setVariable("m", m);
        ctx.setVariable("h", h);
        ctx.setVariable("fmt", new StatementFormatter(h.language(), h.dateFormat()));

        String html = templateEngine.process(TEMPLATE, ctx);

        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            PdfRendererBuilder b = new PdfRendererBuilder();
            b.useFastMode();
            fonts.daftarFon(b);
            b.withHtmlContent(html, null);
            b.toStream(os);
            b.run();
            return os.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Gagal render invois " + m.invoiceNo(), e);
        }
    }
}
