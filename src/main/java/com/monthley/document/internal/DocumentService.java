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

    DocumentService(FinancialDocumentRepository documents,
                    DocumentLineRepository lines,
                    DocumentNumberService numbers) {
        this.documents = documents;
        this.lines = lines;
        this.numbers = numbers;
    }

    @Override
    @Transactional
    public Optional<Long> createInvoice(NewInvoice inv) {
        for (NewDocumentLine l : inv.lines()) {
            boolean exists = lines.existsByAccountIdAndProductIdAndPeriodStartAndActiveTrue(
                    l.accountId(), l.productId(), l.periodStart());
            if (exists) return Optional.empty();
        }

        String docNo = numbers.next(inv.spCode(), "INVOICE");
        FinancialDocument doc = new FinancialDocument(
                inv.spCode(), docNo, DocumentType.INVOICE, inv.accountId(),
                inv.docDate(), inv.period(), inv.dueDate(), inv.title());

        for (NewDocumentLine l : inv.lines()) {
            doc.addLine(new FinancialDocumentLine(
                    l.productId(), l.accountId(), l.description(),
                    l.quantity(), l.unitPrice(), l.amount(), l.taxAmount(),
                    l.periodStart(), l.periodEnd()));
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
    public void cancelDocument(Long documentId) {
        documents.findById(documentId).ifPresent(FinancialDocument::markCancelled);
    }
}
