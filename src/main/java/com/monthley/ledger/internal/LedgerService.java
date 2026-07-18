package com.monthley.ledger.internal;

import com.monthley.ledger.api.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Implementasi LedgerPort — satu-satunya jalan menulis ke general ledger.
 *
 * Dua jaminan teras:
 *   1. Setiap journal MESTI seimbang (Σ debit = Σ credit) — jika tidak, tolak.
 *   2. Pembatalan = entri contra guna amaun TERSIMPAN, bukan kira semula.
 */
@Service
class LedgerService implements LedgerPort {

    private static final BigDecimal TOLERANCE = new BigDecimal("0.005");

    private final JournalEntryRepository entries;
    private final ChartOfAccountRepository accounts;
    private final ChartOfAccountSeeder seeder;

    LedgerService(JournalEntryRepository entries, ChartOfAccountRepository accounts,
                  ChartOfAccountSeeder seeder) {
        this.entries = entries;
        this.accounts = accounts;
        this.seeder = seeder;
    }

    @Override
    @Transactional
    public Long post(PostingRequest req) {
        validateBalanced(req);

        JournalEntry entry = new JournalEntry(
                req.spCode(),
                nextEntryNo(req.spCode()),
                req.entryDate(),
                req.sourceType(),
                req.sourceDocumentId(),
                req.description(),
                req.reversesEntryId());

        for (PostingLine l : req.lines()) {
            Long glId = glId(req.spCode(), l.glAccountCode());
            JournalLine line = (l.debit().signum() > 0)
                    ? JournalLine.debit(glId, l.debit(), l.subLedgerAccountId(), l.description())
                    : JournalLine.credit(glId, l.credit(), l.productId(), l.description());
            entry.addLine(line);
        }

        return entries.save(entry).getId();
    }

    @Override
    @Transactional
    public Long reverse(Long journalEntryId, String reason) {
        JournalEntry original = entries.findById(journalEntryId)
                .orElseThrow(() -> new IllegalArgumentException("Journal tak wujud: " + journalEntryId));

        if (original.getStatus() == JournalEntry.Status.REVERSED) {
            throw new IllegalStateException("Journal sudah dibalikkan: " + journalEntryId);
        }

        JournalEntry contra = new JournalEntry(
                original.getSpCode(),
                nextEntryNo(original.getSpCode()),
                original.getEntryDate(),
                SourceType.CANCELLATION,
                original.getSourceDocumentId(),
                "Reversal: " + reason,
                original.getId());

        // Balikkan setiap leg — debit jadi kredit, guna amaun TERSIMPAN
        for (JournalLine l : original.getLines()) {
            JournalLine contraLine = (l.getDebitAmount().signum() > 0)
                    ? JournalLine.credit(l.getGlAccountId(), l.getDebitAmount(), null, "reversal")
                    : JournalLine.debit(l.getGlAccountId(), l.getCreditAmount(), l.getSubLedgerAccountId(), "reversal");
            contra.addLine(contraLine);
        }

        original.markReversed();
        return entries.save(contra).getId();
    }

    private void validateBalanced(PostingRequest req) {
        if (req.lines() == null || req.lines().size() < 2) {
            throw new UnbalancedJournalException(BigDecimal.ZERO, BigDecimal.ZERO);
        }
        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        for (PostingLine l : req.lines()) {
            debit = debit.add(l.debit());
            credit = credit.add(l.credit());
        }
        if (debit.subtract(credit).abs().compareTo(TOLERANCE) > 0) {
            throw new UnbalancedJournalException(debit, credit);
        }
    }

    private String nextEntryNo(String spCode) {
        // Ringkas buat sekarang; nanti guna document_number_sequence (row lock)
        return "JE" + String.format("%010d", entries.lastId(spCode) + 1);
    }

    /** Resolve kod GL (cth "1100") kepada id, dalam skop SP. */
    private Long glId(String spCode, String glAccountCode) {
        return accounts.findBySpCodeAndCode(spCode, glAccountCode)
                .orElseThrow(() -> new IllegalStateException(
                        "Akaun GL '" + glAccountCode + "' tak wujud untuk SP " + spCode
                        + ". Seed carta akaun dulu."))
                .getId();
    }

    @Override
    @Transactional(readOnly = true)
    public String glCodeFor(String spCode, Long glAccountId) {
        return accounts.findByIdAndSpCode(glAccountId, spCode)
                .orElseThrow(() -> new IllegalStateException(
                        "Akaun GL id " + glAccountId + " tak wujud untuk SP " + spCode
                        + ". Betulkan tetapan GL produk."))
                .getCode();
    }

    @Override
    public void seedChartOfAccounts(String spCode) {
        seeder.seedFor(spCode);
    }
}
