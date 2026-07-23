package com.monthley.payment.internal;

import com.monthley.document.api.DocumentPort;
import com.monthley.document.api.DocumentType;
import com.monthley.document.api.NewAdjustmentDoc;
import com.monthley.ledger.api.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Account Adjustment — Credit Note (Reduction) & Debit Note (Additional).
 * Rujuk docs/decisions/0003-account-adjustment.md.
 *
 * REDUCTION  = kredit nota: kurang tunggakan. Cipta CREDIT_NOTE + alokasi ke
 *              invois sasaran (via AllocationGuard). Ledger Dr Hasil / Cr AR.
 * ADDITIONAL = debit nota: tambah tunggakan. Cipta DEBIT_NOTE (masuk baki).
 *              Ledger Dr AR / Cr Hasil. Tiada alokasi.
 *
 * Elak 4 family drift: doc immutable (bukan edit invois), invariant+kunci
 * pesimis (guard), idempotency (sourceRef).
 */
@Service
public class AdjustmentService {

    public enum Kind { ADDITIONAL, REDUCTION }

    private final DocumentPort documents;
    private final LedgerPort ledger;
    private final AllocationGuard guard;
    private final AllocationRepository allocations;
    private final LineAllocationWriter lineWriter;

    AdjustmentService(DocumentPort documents, LedgerPort ledger,
                      AllocationGuard guard, AllocationRepository allocations,
                      LineAllocationWriter lineWriter) {
        this.documents = documents;
        this.ledger = ledger;
        this.guard = guard;
        this.allocations = allocations;
        this.lineWriter = lineWriter;
    }

    public record NewAdjustment(
            String spCode, Long accountId, Kind kind,
            BigDecimal amount, Long targetInvoiceId, String remarks,
            String sourceRef) {}

    public record AdjustmentResult(Long documentId, String docType) {}

    @Transactional
    public AdjustmentResult adjust(NewAdjustment req) {
        if (req.amount() == null || req.amount().signum() <= 0) {
            throw new IllegalArgumentException("Amaun adjustment mesti > 0.");
        }
        return req.kind() == Kind.REDUCTION ? reduction(req) : additional(req);
    }

    /** Kredit nota — Dr Hasil / Cr AR; alokasi ke invois sasaran. */
    private AdjustmentResult reduction(NewAdjustment req) {
        if (req.targetInvoiceId() == null) {
            throw new IllegalArgumentException("Reduction mesti sasar invois.");
        }
        // Invariant + kunci pesimis SEBELUM cipta apa-apa (elak over-allocation).
        guard.checkAndLock(req.targetInvoiceId(), req.amount());

        Long cnId = documents.createAdjustment(new NewAdjustmentDoc(
                req.spCode(), req.accountId(), DocumentType.CREDIT_NOTE,
                LocalDate.now(), remarkTitle("Kredit Nota", req.remarks()),
                req.amount(), req.sourceRef()));

        // Alokasi: debit=invois sasaran, credit=kredit nota (kurang baki invois).
        // Pecah mengikut line (ADR 0006) — kredit nota knock line invois sasaran.
        lineWriter.write(req.spCode(), req.accountId(),
                req.targetInvoiceId(), cnId, req.amount());

        // Ledger: Dr Hasil / Cr AR (subledger = akaun).
        ledger.post(new PostingRequest(
                req.spCode(), LocalDate.now(), SourceType.ADJUSTMENT, cnId,
                "Kredit Nota " + cnId,
                List.of(
                        PostingLine.debit(GlAccounts.SERVICE_INCOME, req.amount(), null),
                        new PostingLine(GlAccounts.ACCOUNTS_RECEIVABLE,
                                java.math.BigDecimal.ZERO, req.amount(),
                                req.accountId(), null, "Kredit nota AR")),
                null));

        return new AdjustmentResult(cnId, "CREDIT_NOTE");
    }

    /** Debit nota — Dr AR / Cr Hasil; dokumen baru masuk baki. */
    private AdjustmentResult additional(NewAdjustment req) {
        Long dnId = documents.createAdjustment(new NewAdjustmentDoc(
                req.spCode(), req.accountId(), DocumentType.DEBIT_NOTE,
                LocalDate.now(), remarkTitle("Debit Nota", req.remarks()),
                req.amount(), req.sourceRef()));

        // Ledger: Dr AR (subledger = akaun) / Cr Hasil.
        ledger.post(new PostingRequest(
                req.spCode(), LocalDate.now(), SourceType.ADJUSTMENT, dnId,
                "Debit Nota " + dnId,
                List.of(
                        PostingLine.debit(GlAccounts.ACCOUNTS_RECEIVABLE, req.amount(), req.accountId()),
                        PostingLine.credit(GlAccounts.SERVICE_INCOME, req.amount(), null)),
                null));

        return new AdjustmentResult(dnId, "DEBIT_NOTE");
    }

    private static String remarkTitle(String base, String remarks) {
        return (remarks == null || remarks.isBlank()) ? base : base + " — " + remarks;
    }
}
