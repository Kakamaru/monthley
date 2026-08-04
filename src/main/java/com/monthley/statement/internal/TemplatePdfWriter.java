package com.monthley.statement.internal;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.io.ByteArrayOutputStream;
import java.util.Map;

/**
 * Render mana-mana templat Thymeleaf kepada PDF.
 *
 * Enjin dikongsi: fon terbenam, mod pantas, gaya yang konsisten. Modul
 * lain menyimpan TEMPLAT mereka sendiri dan memanggil ini melalui
 * StatementRenderPort — tanpa masuk ke statement.internal, yang
 * mencipta kitaran modul.
 *
 * Fon MESTI dibenamkan: Helvetica terbina PDFBox ialah WinAnsi sahaja,
 * dan aksara di luarnya dilukis sebagai '#' TANPA ralat.
 */
@Component
class TemplatePdfWriter {

    private final TemplateEngine templateEngine;
    private final StatementPdfWriter fonPembekal;

    TemplatePdfWriter(TemplateEngine templateEngine, StatementPdfWriter fonPembekal) {
        this.templateEngine = templateEngine;
        this.fonPembekal = fonPembekal;
    }

    byte[] render(String template, Map<String, Object> vars) {
        Context ctx = new Context();
        vars.forEach(ctx::setVariable);

        String html = templateEngine.process(template, ctx);

        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            PdfRendererBuilder b = new PdfRendererBuilder();
            b.useFastMode();
            fonPembekal.daftarFon(b);
            b.withHtmlContent(html, null);
            b.toStream(os);
            b.run();
            return os.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Gagal render templat " + template, e);
        }
    }
}
