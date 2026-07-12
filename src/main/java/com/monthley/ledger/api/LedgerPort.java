package com.monthley.ledger.api;

/**
 * SATU-SATUNYA pintu untuk menulis ke general ledger.
 *
 * Tiada modul boleh INSERT terus ke journal_entry / journal_line.
 * Spring Modulith enforce ini pada waktu ujian (ModularityTests).
 */
public interface LedgerPort {

    /**
     * Post journal seimbang. Mesti dipanggil dalam transaction pemanggil.
     *
     * @return id journal_entry yang dicipta
     * @throws UnbalancedJournalException jika SUM(debit) != SUM(credit)
     */
    Long post(PostingRequest request);

    /**
     * Balikkan journal sedia ada dengan entri contra.
     * Menggunakan amaun TERSIMPAN — tidak mengira semula (elak rounding drift).
     */
    Long reverse(Long journalEntryId, String reason);
}
