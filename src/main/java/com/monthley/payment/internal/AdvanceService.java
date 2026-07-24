package com.monthley.payment.internal;

import com.monthley.ledger.api.*;
import com.monthley.payment.api.AdvancePort;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Guna advance untuk invois baharu (ADR 0009 P3).
 *
 * Advance = resit yang belum habis dialokasi. Ia DITERBITKAN, bukan disimpan:
 *   baki advance = nilai resit - SUM(alokasi ACTIVE dari resit itu)
 * Menyimpannya sebagai lajur akan mengulangi CASE-002 (cache yang hanyut).
 *
 * Resit tertua digunakan dahulu — konsisten dengan FIFO di tempat lain.
 *
 * Kesan ledger: semasa bayaran, lebihan dikredit ke CUSTOMER_DEPOSIT
 * (liabiliti — kita berhutang perkhidmatan). Bila advance digunakan, ia
 * mesti diterbalikkan:
 *
 *     Dr Customer Deposit / Cr AR
 *
 * Tanpa posting ini, liabiliti membengkak selama-lamanya dan AR terlebih
 * nyata, walaupun baki pelanggan betul.
 */
@Service
class AdvanceService implements AdvancePort {

    private static final Logger log = LoggerFactory.getLogger(AdvanceService.class);

    @PersistenceContext
    private EntityManager em;

    private final AllocationGuard guard;
    private final LineAllocationWriter lineWriter;
    private final LedgerPort ledger;

    AdvanceService(AllocationGuard guard, LineAllocationWriter lineWriter, LedgerPort ledger) {
        this.guard = guard;
        this.lineWriter = lineWriter;
        this.ledger = ledger;
    }

    @Override
    @Transactional
    @SuppressWarnings("unchecked")
    public BigDecimal applyAdvance(String spCode, Long accountId, Long invoiceDocumentId) {
        // Berapa invois ini masih terbuka.
        BigDecimal cap = guard.docTotal(invoiceDocumentId);
        BigDecimal owed = cap.subtract(guard.sumActive(invoiceDocumentId));
        if (owed.signum() <= 0) return BigDecimal.ZERO;

        // Resit dengan baki advance, tertua dahulu.
        List<Object[]> receipts = em.createNativeQuery("""
                SELECT r.id,
                       (r.amount + r.tax_amount) - COALESCE((
                           SELECT SUM(al.amount) FROM fi_allocation al
                           WHERE al.credit_document_id = r.id AND al.status = 'ACTIVE'), 0) AS baki
                FROM financial_document r
                WHERE r.sp_code = :sp AND r.account_id = :acc
                  AND r.doc_type = 'RECEIPT' AND r.status <> 'CANCELLED'
                HAVING baki > 0
                ORDER BY r.doc_date, r.id
                """)
                .setParameter("sp", spCode)
                .setParameter("acc", accountId)
                .getResultList();

        BigDecimal used = BigDecimal.ZERO;

        for (Object[] row : receipts) {
            if (owed.signum() <= 0) break;

            Long receiptId = ((Number) row[0]).longValue();
            BigDecimal available = new BigDecimal(row[1].toString());
            BigDecimal take = owed.min(available);
            if (take.signum() <= 0) continue;

            // Invariant sisi debit — pemanggil memegang konteks urutan kunci.
            // Sisi kredit disemak dalam writer.
            guard.checkAndLock(invoiceDocumentId, take);
            lineWriter.write(spCode, accountId, invoiceDocumentId, receiptId, take);

            // Terbalikkan liabiliti deposit.
            ledger.post(new PostingRequest(
                    spCode, LocalDate.now(), SourceType.PAYMENT, invoiceDocumentId,
                    "Guna advance resit " + receiptId,
                    List.of(
                            PostingLine.debit(GlAccounts.CUSTOMER_DEPOSIT, take, accountId),
                            PostingLine.credit(GlAccounts.ACCOUNTS_RECEIVABLE, take, null)),
                    null));

            owed = owed.subtract(take);
            used = used.add(take);
        }

        if (used.signum() > 0) {
            log.info("Advance digunakan: SP {} akaun {} invois {} sebanyak {}",
                    spCode, accountId, invoiceDocumentId, used);
        }
        return used;
    }
}
