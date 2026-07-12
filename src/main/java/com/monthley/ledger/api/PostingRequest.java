package com.monthley.ledger.api;

import java.time.LocalDate;
import java.util.List;

/**
 * Permintaan posting ke general ledger.
 * Invariant: SUM(debit) mesti sama SUM(credit) — disemak oleh LedgerPort.
 */
public record PostingRequest(
        String spCode,
        LocalDate entryDate,
        SourceType sourceType,
        Long sourceDocumentId,
        String description,
        List<PostingLine> lines,
        /** Untuk journal contra — merujuk entry asal. Null untuk posting biasa. */
        Long reversesEntryId) {
}
