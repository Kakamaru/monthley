package com.monthley.document.internal;

import com.monthley.document.api.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
class DocumentService implements DocumentPort {

    private final FinancialDocumentRepository documents;
    private final DocumentLineRepository lines;
    private final DocumentNumberService numbers;

    private final DocumentAccessService access;

    DocumentService(FinancialDocumentRepository documents,
                    DocumentLineRepository lines,
                    DocumentNumberService numbers,
                    DocumentAccessService access) {
        this.documents = documents;
        this.lines = lines;
        this.numbers = numbers;
        this.access = access;
    }

    @Override
    @Transactional
    public Optional<Long> createInvoice(NewInvoice inv) {
        // Semua-atau-tiada: satu baris sudah wujud -> gugurkan seluruh invois.
        // Selamat (dokumen & ledger dua-dua tiada) tetapi terlalu konservatif:
        // akaun tahunan yang Januarinya sudah dijana akan hilang 11 bulan lain.
        // TODO: billing patut tapis baris SEBELUM bina dokumen DAN ledger —
        //       satu senarai, dua penggunaan. Menapis di sini sahaja akan buat
        //       ledger tak padan dokumen.
        for (NewDocumentLine l : inv.lines()) {
            if (inv.skipDuplicateCheck()) break;
            boolean exists = l.onceOnly()
                    ? lines.existsByAccountIdAndProductIdAndOnceOnlyTrueAndActiveTrue(
                            l.accountId(), l.productId())
                    : lines.existsByAccountIdAndProductIdAndPeriodStartAndActiveTrue(
                            l.accountId(), l.productId(), l.periodStart());
            if (exists) return Optional.empty();
        }

        String docNo = numbers.next(inv.spCode(), "INVOICE");
        FinancialDocument doc = new FinancialDocument(
                inv.spCode(), docNo, DocumentType.INVOICE, inv.accountId(),
                inv.docDate(), inv.periodId(), inv.dueDate(), inv.title());

        for (NewDocumentLine l : inv.lines()) {
            doc.addLine(new FinancialDocumentLine(
                    l.productId(), l.accountId(), l.periodId(), l.description(),
                    l.quantity(), l.unitPrice(), l.prorationRatio(), l.amount(), l.taxAmount(),
                    l.periodStart(), l.periodEnd(), l.onceOnly()));
        }
        doc.recomputeTotals();
        return Optional.of(documents.save(doc).getId());
    }

    @Override
    @Transactional
    public Long createReceipt(NewReceipt r) {
        String docNo = numbers.next(r.spCode(), "RECEIPT");
        FinancialDocument doc = new FinancialDocument(
                r.spCode(), docNo, DocumentType.RECEIPT, r.accountId(),
                r.docDate(), null, null, r.title());
        doc.setReceiptAmount(r.amount());
        return documents.save(doc).getId();
    }

    @Override
    @Transactional
    public Long createAdjustment(NewAdjustmentDoc adj) {
        // Idempotency: kalau sourceRef sudah wujud utk SP ni, pulang id sedia ada.
        if (adj.sourceRef() != null) {
            Optional<FinancialDocument> existing =
                    documents.findBySpCodeAndSourceRef(adj.spCode(), adj.sourceRef());
            if (existing.isPresent()) return existing.get().getId();
        }

        String type = adj.docType() == DocumentType.CREDIT_NOTE ? "CREDIT_NOTE" : "DEBIT_NOTE";
        String docNo = numbers.next(adj.spCode(), type);
        FinancialDocument doc = new FinancialDocument(
                adj.spCode(), docNo, adj.docType(), adj.accountId(),
                adj.docDate(), null, null, adj.title());
        doc.setReceiptAmount(adj.amount());   // set amount, tax 0
        doc.setSourceRef(adj.sourceRef());
        return documents.save(doc).getId();
    }

    @Override
    @Transactional
    public java.math.BigDecimal lockAndGetTotal(Long documentId) {
        return documents.findByIdForUpdate(documentId)
                .map(FinancialDocument::getTotal)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Dokumen tak wujud: " + documentId));
    }

    @Override
    @Transactional
    public void cancelDocument(Long documentId) {
        cancelDocument(documentId, null, null);
    }

    @Override
    @Transactional
    public void cancelDocument(Long documentId, String reason, Long cancelledBy) {
        documents.findById(documentId).ifPresent(d -> {
            d.markCancelled(reason, cancelledBy);
            // Pautan awam mesti berhenti berfungsi. Tanpa ini pelanggan
            // membuka pautan e-mel dan melihat dokumen yang dibatalkan
            // seolah-olah sah — dan menganggapnya bukti bayaran.
            access.revoke(documentId);
        });
    }
}
