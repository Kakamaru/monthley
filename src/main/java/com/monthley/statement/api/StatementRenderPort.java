package com.monthley.statement.api;

/** Penulis penyata. Satu model, banyak format (ADR 0010 keputusan 7). */
public interface StatementRenderPort {

    /** PDF. Templat boleh diedit tanpa recompile. */
    byte[] renderPdf(StatementModel model);

    /**
     * XLSX. Model yang SAMA seperti PDF — hanya penulis berbeza
     * (ADR 0010 keputusan 7).
     */
    byte[] renderXlsx(StatementModel model);

    /**
     * PDF dengan nama fail piawai. Gunakan ini daripada renderPdf apabila
     * menghantar kepada pengguna, supaya penamaan tidak menyimpang antara
     * ikon akaun, portal pelanggan dan tab Laporan.
     */
    /** Resit PDF. */
    byte[] renderReceiptPdf(ReceiptModel model);

    /** Invois PDF. */
    byte[] renderInvoicePdf(InvoiceModel model);

    /**
     * Banyak invois dalam SATU PDF.
     *
     * Satu render, bukan satu render setiap invois: setiap pembina
     * memuatkan tujuh fon dan memulakan enjin semula, dan melakukannya
     * 1,400 kali jauh lebih mahal daripada satu dokumen 1,400 muka.
     */
    byte[] renderInvoiceBulkPdf(java.util.List<InvoiceModel> models);

    /**
     * Render mana-mana templat Thymeleaf kepada PDF.
     *
     * Modul lain mempunyai laporan mereka sendiri — senarai akaun,
     * tunggakan — dan mereka memerlukan enjin PDF yang SAMA: fon
     * terbenam, kepala SP, gaya yang konsisten.
     *
     * Tanpa ini, satu-satunya cara ialah modul itu memanggil
     * statement.internal, yang mencipta kitaran apabila statement sudah
     * bergantung padanya (ADR 0010; berlaku dua kali).
     *
     * Templat hidup dalam modul yang memilikinya; enjin dikongsi.
     */
    byte[] renderTemplatePdf(String template, java.util.Map<String, Object> vars);

    /** Kepala SP untuk laporan peringkat SP — tiada akaun tertentu. */
    StatementHeader headerForSp(String spCode);

    /**
     * Pemformat tarikh dan wang untuk kepala tertentu.
     *
     * StatementPort.formatterFor menerima StatementModel; laporan
     * peringkat SP tiada model, hanya kepala.
     */
    StatementTextFormat formatterFor(StatementHeader header);

    default StatementFile renderInvoicePdfFile(InvoiceModel m) {
        String name = ("invois-" + m.invoiceNo() + ".pdf")
                .replaceAll("[^A-Za-z0-9._-]", "_");
        return new StatementFile(name, "application/pdf", renderInvoicePdf(m));
    }

    default StatementFile renderReceiptPdfFile(ReceiptModel m) {
        String name = ("resit-" + m.receiptNo() + ".pdf")
                .replaceAll("[^A-Za-z0-9._-]", "_");
        return new StatementFile(name, "application/pdf", renderReceiptPdf(m));
    }

    default StatementFile renderXlsxFile(StatementModel m) {
        String akaun = m.header().accountNo() == null
                ? String.valueOf(m.accountId())
                : m.header().accountNo();
        String name = ("penyata-" + akaun + "-" + m.from() + "-" + m.to() + ".xlsx")
                .replaceAll("[^A-Za-z0-9._-]", "_");
        return new StatementFile(name,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                renderXlsx(m));
    }

    default StatementFile renderPdfFile(StatementModel m) {
        String akaun = m.header().accountNo() == null
                ? String.valueOf(m.accountId())
                : m.header().accountNo();
        String name = ("penyata-" + akaun + "-" + m.from() + "-" + m.to() + ".pdf")
                .replaceAll("[^A-Za-z0-9._-]", "_");
        return new StatementFile(name, "application/pdf", renderPdf(m));
    }
}
