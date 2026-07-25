package com.monthley.statement.internal;

import com.monthley.statement.api.StatementModel;
import com.monthley.statement.api.StatementRenderPort;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import org.springframework.core.io.ClassPathResource;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Render StatementModel kepada PDF melalui templat Thymeleaf.
 *
 * Templat dan bukan HTML dalam Java: susun atur akan diubah berkali-kali
 * oleh manusia. Itulah satu-satunya hujah kukuh yang Pentaho ada — kita
 * mengambil kelebihan itu tanpa mengambil enjinnya, dan tanpa
 * membenarkan query masuk ke dalam templat.
 *
 * AMARAN 1: openhtmltopdf menghurai XHTML secara KETAT. Entiti HTML
 * bernama menyebabkan kegagalan render penuh. Gunakan rujukan aksara
 * BERANGKA, dan hantar setiap nilai pangkalan data melalui th:text
 * (yang meng-escape ampersand) — tidak pernah th:utext.
 *
 * AMARAN 2: font mesti DIBENAMKAN. Helvetica terbina PDFBox ialah
 * WinAnsi sahaja; sebarang aksara di luarnya dilukis sebagai '#' TANPA
 * RALAT. Ia bukan sekadar tanda semak — nama pelanggan dan SP ialah data
 * pengguna, dan kerosakan senyap pada nama orang tidak boleh diterima.
 * DejaVu Sans (lesen bebas) merangkumi Latin lanjutan dan simbol.
 */
@Component
class StatementPdfWriter implements StatementRenderPort {

    private static final String TEMPLATE = "statement/statement";

    private static final String[] FONTS = {
            "DejaVuSans.ttf", "DejaVuSans-Bold.ttf", "DejaVuSans-Oblique.ttf",
            "DejaVuSansMono.ttf"
    };

    private final TemplateEngine templateEngine;

    /**
     * openhtmltopdf memerlukan File, bukan InputStream, jadi font disalin
     * sekali daripada classpath ke direktori sementara. Ia berfungsi sama ada
     * aplikasi dijalankan daripada jar atau direktori kelas.
     */
    private final File[] fontFiles;

    StatementPdfWriter(TemplateEngine templateEngine) {
        this.templateEngine = templateEngine;
        this.fontFiles = new File[FONTS.length];
        try {
            Path dir = Files.createTempDirectory("monthley-fonts");
            dir.toFile().deleteOnExit();
            for (int i = 0; i < FONTS.length; i++) {
                Path out = dir.resolve(FONTS[i]);
                try (InputStream in = new ClassPathResource("fonts/" + FONTS[i])
                        .getInputStream()) {
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
                out.toFile().deleteOnExit();
                fontFiles[i] = out.toFile();
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Font penyata tidak dapat disediakan; PDF akan merosakkan "
                    + "aksara bukan-WinAnsi secara senyap", e);
        }
    }

    @Override
    public byte[] renderPdf(StatementModel model) {
        var h = model.header();
        Context ctx = new Context();
        ctx.setVariable("m", model);
        ctx.setVariable("h", h);
        ctx.setVariable("fmt", new StatementFormatter(h.language(), h.dateFormat()));

        String html = templateEngine.process(TEMPLATE, ctx);

        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            PdfRendererBuilder b = new PdfRendererBuilder();
            b.useFastMode();
            b.useFont(fontFiles[0], "DejaVu Sans", 400,
                    PdfRendererBuilder.FontStyle.NORMAL, true);
            b.useFont(fontFiles[1], "DejaVu Sans", 700,
                    PdfRendererBuilder.FontStyle.NORMAL, true);
            b.useFont(fontFiles[2], "DejaVu Sans", 400,
                    PdfRendererBuilder.FontStyle.ITALIC, true);
            // Lajur nombor mesti sejajar: mata mengimbas turun penyata
            // kewangan, dan lebar berubah menyukarkannya.
            b.useFont(fontFiles[3], "DejaVu Sans Mono", 400,
                    PdfRendererBuilder.FontStyle.NORMAL, true);
            b.withHtmlContent(html, null);
            b.toStream(os);
            b.run();
            return os.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Gagal render penyata untuk akaun " + model.accountId(), e);
        }
    }
}
