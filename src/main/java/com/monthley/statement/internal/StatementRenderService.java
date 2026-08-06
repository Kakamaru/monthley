package com.monthley.statement.internal;

import com.monthley.statement.api.StatementModel;
import com.monthley.statement.api.StatementRenderPort;
import org.springframework.stereotype.Service;

/**
 * Satu pintu untuk merender penyata; mendelegasikan kepada penulis format.
 *
 * Penulis TIDAK melaksanakan port secara langsung. Kelas bernama
 * StatementPdfWriter yang juga menghasilkan XLSX ialah nama yang menipu,
 * dan pemanggil seterusnya akan mencari fail yang salah.
 *
 * Setiap penulis menerima StatementModel yang SAMA (ADR 0010 keputusan 7).
 * Jika satu format memerlukan medan yang tiada dalam model, itu tanda model
 * tidak lengkap — bukan sebab untuk penulis menyoal pangkalan data sendiri.
 */
@Service
class StatementRenderService implements StatementRenderPort {

    private final StatementPdfWriter pdf;
    private final StatementXlsxWriter xlsx;
    private final ReceiptPdfWriter receipt;
    private final InvoicePdfWriter invoice;
    private final TemplatePdfWriter templatePdf;
    private final StatementQuery query;

    StatementRenderService(StatementPdfWriter pdf, StatementXlsxWriter xlsx,
                           ReceiptPdfWriter receipt, InvoicePdfWriter invoice,
                           TemplatePdfWriter templatePdf, StatementQuery query) {
        this.pdf = pdf;
        this.xlsx = xlsx;
        this.receipt = receipt;
        this.invoice = invoice;
        this.templatePdf = templatePdf;
        this.query = query;
    }

    @Override
    public byte[] renderPdf(StatementModel model) {
        return pdf.renderPdf(model);
    }

    @Override
    public byte[] renderXlsx(StatementModel model) {
        return xlsx.renderXlsx(model);
    }

    @Override
    public byte[] renderReceiptPdf(com.monthley.statement.api.ReceiptModel model) {
        return receipt.render(model);
    }

    @Override
    public byte[] renderInvoicePdf(com.monthley.statement.api.InvoiceModel model) {
        return invoice.render(model);
    }

    /** Bilangan invois setiap render. Lihat komen di bawah. */
    private static final int SAIZ_KELOMPOK = 25;

    /**
     * Banyak invois dalam satu PDF, dirender BERKELOMPOK.
     *
     * Percubaan pertama merender kesemuanya dalam SATU dokumen untuk
     * mengelakkan kos memuat fon berulang. Ia mati dengan
     * OutOfMemoryError pada 118 invois — openhtmltopdf membina seluruh
     * DOM dan pokok render dalam heap, dan kos fon yang saya risaukan
     * itu kecil berbanding heap yang meletup.
     *
     * Kini 25 invois setiap render, setiap kelompok ditulis ke fail
     * sementara, digabung dengan cache CAKERA. Memori kekal terbatas
     * sama ada 118 atau 1,400 invois.
     */
    @Override
    public byte[] renderInvoiceBulkPdf(
            java.util.List<com.monthley.statement.api.InvoiceModel> models) {
        if (models.isEmpty()) {
            throw new IllegalArgumentException("Tiada invois untuk dicetak.");
        }

        var h = models.get(0).header();
        var fmt = new StatementFormatter(h.language(), h.dateFormat());

        java.nio.file.Path dir = null;
        try {
            dir = java.nio.file.Files.createTempDirectory("monthley-invois");
            java.util.List<java.io.File> kepingan = new java.util.ArrayList<>();

            for (int i = 0; i < models.size(); i += SAIZ_KELOMPOK) {
                var kelompok = models.subList(i,
                        Math.min(i + SAIZ_KELOMPOK, models.size()));

                java.util.Map<String, Object> vars = new java.util.LinkedHashMap<>();
                vars.put("senarai", kelompok);
                vars.put("fmt", fmt);

                java.io.File f = dir.resolve("k" + i + ".pdf").toFile();
                java.nio.file.Files.write(f.toPath(),
                        templatePdf.render("statement/invoice-bulk", vars));
                kepingan.add(f);
            }

            if (kepingan.size() == 1) {
                return java.nio.file.Files.readAllBytes(kepingan.get(0).toPath());
            }

            java.io.File gabung = dir.resolve("gabung.pdf").toFile();
            var merger = new org.apache.pdfbox.multipdf.PDFMergerUtility();
            merger.setDestinationFileName(gabung.getAbsolutePath());
            for (java.io.File f : kepingan) merger.addSource(f);
            // Cache CAKERA: menggabung 1,400 muka dalam memori
            // menghidupkan semula masalah yang berkelompok ini
            // selesaikan.
            merger.mergeDocuments(
                    org.apache.pdfbox.io.IOUtils.createTempFileOnlyStreamCache());

            return java.nio.file.Files.readAllBytes(gabung.toPath());

        } catch (Exception e) {
            throw new IllegalStateException("Gagal render invois pukal", e);
        } finally {
            buang(dir);
        }
    }

    /** Fail sementara mesti dibuang walaupun render gagal. */
    private static void buang(java.nio.file.Path dir) {
        if (dir == null) return;
        try (var s = java.nio.file.Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder())
             .forEach(p -> { try { java.nio.file.Files.deleteIfExists(p); }
                             catch (Exception ignore) { } });
        } catch (Exception ignore) { }
    }

    @Override
    public byte[] renderTemplatePdf(String template, java.util.Map<String, Object> vars) {
        return templatePdf.render(template, vars);
    }

    @Override
    public com.monthley.statement.api.StatementHeader headerForSp(String spCode) {
        return query.headerSp(spCode);
    }

    @Override
    public com.monthley.statement.api.StatementTextFormat formatterFor(
            com.monthley.statement.api.StatementHeader h) {
        return new StatementFormatter(h.language(), h.dateFormat());
    }
}
