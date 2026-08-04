package com.monthley.statement.internal;

import com.monthley.account.api.AccountListPort;
import com.monthley.statement.api.StatementHeader;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.io.ByteArrayOutputStream;

/** Senarai Akaun sebagai PDF. Fon dikongsi dengan penyata. */
@Component
class AccountListPdfWriter {

    private static final String TEMPLATE = "statement/account-list";

    private final TemplateEngine templateEngine;
    private final StatementPdfWriter fonPembekal;

    AccountListPdfWriter(TemplateEngine templateEngine, StatementPdfWriter fonPembekal) {
        this.templateEngine = templateEngine;
        this.fonPembekal = fonPembekal;
    }

    byte[] render(AccountListPort.Result r, StatementHeader h) {
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
            throw new IllegalStateException("Gagal render senarai akaun", e);
        }
    }
}
