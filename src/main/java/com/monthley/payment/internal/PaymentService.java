package com.monthley.payment.internal;

import com.monthley.document.api.DocumentPort;
import com.monthley.document.api.NewReceipt;
import com.monthley.ledger.api.*;
import com.monthley.payment.api.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Terima bayaran, peruntuk FIFO, post ke ledger.
 *
 * Aliran:
 *   1. semak minimum amount SP
 *   2. cipta resit sebagai DOKUMEN (financial_document type RECEIPT)
 *   3. FIFO agih ke invois (hormati selective gate)
 *   4. setiap agihan → fi_allocation (debit=invois, credit=resit)
 *   5. post ledger: Dr Bank / Cr AR (+ Cr Deposit jika lebih)
 *
 * Pembatalan = contra ledger + status REVERSED pada allocation + batal resit.
 */
@Service
class PaymentService implements PaymentPort {

    private final PaymentRepository payments;
    private final AllocationRepository allocations;
    private final DocumentPort documents;
    private final LedgerPort ledger;
    private final AllocationGuard guard;

    @PersistenceContext
    private EntityManager em;

    PaymentService(PaymentRepository payments, AllocationRepository allocations,
                   DocumentPort documents, LedgerPort ledger, AllocationGuard guard) {
        this.payments = payments;
        this.allocations = allocations;
        this.documents = documents;
        this.ledger = ledger;
        this.guard = guard;
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<OutstandingInvoice> outstandingFor(Long accountId) {
        List<Object[]> rows = em.createNativeQuery("""
            SELECT d.id, d.doc_no, d.account_id, p.name_, d.doc_date, d.due_date,
                   (d.amount + d.tax_amount) AS total,
                   COALESCE(a.paid, 0) AS paid
            FROM financial_document d
            LEFT JOIN fi_period p ON p.period_id = d.period_id
            LEFT JOIN (
                SELECT debit_document_id, SUM(amount) AS paid
                FROM fi_allocation WHERE status = 'ACTIVE'
                GROUP BY debit_document_id
            ) a ON a.debit_document_id = d.id
            WHERE d.account_id = :acc
              AND d.doc_type IN ('INVOICE','DEBIT_NOTE')
              AND d.status <> 'CANCELLED'
              AND (d.amount + d.tax_amount) - COALESCE(a.paid,0) > 0.005
            ORDER BY COALESCE(d.due_date, d.doc_date) ASC, d.period_id ASC, d.doc_no ASC
            """)
            .setParameter("acc", accountId)
            .getResultList();

        List<OutstandingInvoice> result = new ArrayList<>();
        for (Object[] r : rows) {
            BigDecimal total = (BigDecimal) r[6];
            BigDecimal paid = (BigDecimal) r[7];
            result.add(new OutstandingInvoice(
                    ((Number) r[0]).longValue(), (String) r[1],
                    r[2] == null ? null : ((Number) r[2]).longValue(),
                    (String) r[3],
                    toLocalDate(r[4]),
                    toLocalDate(r[5]),
                    total, paid, total.subtract(paid)));
        }
        return result;
    }

    @Override
    @Transactional
    public PaymentResult receivePayment(NewPayment req) {
        BigDecimal min = minPaymentAmount(req.spCode());
        if (min.signum() > 0 && req.amount().compareTo(min) < 0) {
            throw new PaymentBelowMinimumException(req.amount(), min);
        }

        // Idempotency (ADR 0004): kalau key ni sudah diproses, pulang resit sedia
        // ada — JANGAN proses lagi (elak double-entry).
        if (req.idempotencyKey() != null && !req.idempotencyKey().isBlank()) {
            var existing = payments.findBySpCodeAndIdempotencyKey(req.spCode(), req.idempotencyKey());
            if (existing.isPresent()) {
                Payment pmt = existing.get();
                return new PaymentResult(pmt.getId(), "RCP-" + pmt.getReceiptDocumentId(),
                        pmt.getAllocatedAmount(), pmt.getDepositAmount());
            }
        }

        // Calon invois (hormati selective gate)
        List<OutstandingInvoice> candidates = outstandingFor(req.payerAccountId());
        if (allowSelective(req.spCode())
                && req.targetDocumentIds() != null && !req.targetDocumentIds().isEmpty()) {
            candidates = candidates.stream()
                    .filter(i -> req.targetDocumentIds().contains(i.documentId()))
                    .toList();
        }

        // FIFO
        FifoAllocator.Result alloc = FifoAllocator.allocate(req.amount(), candidates);
        BigDecimal allocated = alloc.allocations().stream()
                .map(FifoAllocator.Allocation::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 1. Cipta resit sebagai dokumen
        Long receiptDocId = documents.createReceipt(new NewReceipt(
                req.spCode(), req.payerAccountId(), LocalDate.now(),
                "Resit bayaran", req.amount()));

        // 2. Rekod payment (detail kaedah/ref)
        Payment payment = new Payment(req.spCode(), receiptDocId,
                req.payerAccountId(), req.amount(), req.method(), req.paymentRefNo());
        payment.setTotals(allocated, alloc.deposit());

        // 3. Post ledger: Dr Bank / Cr AR (+ Cr Deposit)
        List<PostingLine> pl = new ArrayList<>();
        pl.add(PostingLine.debit(GlAccounts.BANK, req.amount(), null));
        if (allocated.signum() > 0) {
            pl.add(PostingLine.credit(GlAccounts.ACCOUNTS_RECEIVABLE, allocated, null));
        }
        if (alloc.deposit().signum() > 0) {
            pl.add(PostingLine.credit(GlAccounts.CUSTOMER_DEPOSIT, alloc.deposit(), null));
        }
        Long journalId = ledger.post(new PostingRequest(
                req.spCode(), LocalDate.now(), SourceType.PAYMENT, receiptDocId,
                "Resit doc " + receiptDocId, pl, null));
        payment.setJournalEntryId(journalId);
        payment.setIdempotencyKey(req.idempotencyKey());
        try {
            payments.saveAndFlush(payment);
        } catch (org.springframework.dao.DataIntegrityViolationException dup) {
            // Race: request lain menang dgn key sama. Pulang resit yang berjaya.
            if (req.idempotencyKey() != null && !req.idempotencyKey().isBlank()) {
                var won = payments.findBySpCodeAndIdempotencyKey(req.spCode(), req.idempotencyKey());
                if (won.isPresent()) {
                    Payment w = won.get();
                    return new PaymentResult(w.getId(), "RCP-" + w.getReceiptDocumentId(),
                            w.getAllocatedAmount(), w.getDepositAmount());
                }
            }
            throw dup;
        }

        // 4. fi_allocation setiap agihan (debit=invois, credit=resit)
        for (FifoAllocator.Allocation a : alloc.allocations()) {
            // Invariant + kunci pesimis SATU tempat (elak drift family 1/2).
            guard.checkAndLock(a.documentId(), a.amount());
            PaymentAllocation pa = new PaymentAllocation(
                    req.spCode(), req.payerAccountId(),
                    a.documentId(), receiptDocId, a.amount());
            allocations.save(pa);
        }

        return new PaymentResult(payment.getId(), "RCP-" + receiptDocId, allocated, alloc.deposit());
    }

    @Override
    @Transactional
    public void cancelReceipt(Long receiptId, String reason) {
        Payment payment = payments.findById(receiptId)
                .orElseThrow(() -> new IllegalArgumentException("Resit tak wujud: " + receiptId));
        if (payment.getStatus() == Payment.Status.CANCELLED) {
            throw new IllegalStateException("Resit sudah dibatalkan: " + receiptId);
        }
        if (payment.getJournalEntryId() != null) {
            ledger.reverse(payment.getJournalEntryId(), reason);
        }
        // Nyah-aktif semua allocation resit ni (status REVERSED) → invois terbuka semula
        em.createNativeQuery(
            "UPDATE fi_allocation SET status='REVERSED' WHERE credit_document_id = :rcp")
            .setParameter("rcp", payment.getReceiptDocumentId())
            .executeUpdate();
        // Batalkan dokumen resit
        documents.cancelDocument(payment.getReceiptDocumentId());
        payment.markCancelled();
    }

    private static java.time.LocalDate toLocalDate(Object o) {
        if (o == null) return null;
        if (o instanceof java.time.LocalDate ld) return ld;
        if (o instanceof java.sql.Date d) return d.toLocalDate();
        return java.time.LocalDate.parse(o.toString());
    }

    // --- setting SP ---
    private BigDecimal minPaymentAmount(String spCode) {
        try {
            Object v = em.createNativeQuery(
                "SELECT min_pymt_amount FROM service_provider WHERE sp_code = :sp")
                .setParameter("sp", spCode).getSingleResult();
            return v == null ? BigDecimal.ZERO : new BigDecimal(v.toString());
        } catch (RuntimeException e) {
            return BigDecimal.ZERO;
        }
    }

    private boolean allowSelective(String spCode) {
        try {
            Object v = em.createNativeQuery(
                "SELECT allow_selective FROM service_provider WHERE sp_code = :sp")
                .setParameter("sp", spCode).getSingleResult();
            return v != null && ("1".equals(v.toString()) || "true".equalsIgnoreCase(v.toString()));
        } catch (RuntimeException e) {
            return false;
        }
    }
}
