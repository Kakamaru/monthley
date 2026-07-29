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

    StatementRenderService(StatementPdfWriter pdf, StatementXlsxWriter xlsx,
                           ReceiptPdfWriter receipt, InvoicePdfWriter invoice) {
        this.pdf = pdf;
        this.xlsx = xlsx;
        this.receipt = receipt;
        this.invoice = invoice;
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
}
