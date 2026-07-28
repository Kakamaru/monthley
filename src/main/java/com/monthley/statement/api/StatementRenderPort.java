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
