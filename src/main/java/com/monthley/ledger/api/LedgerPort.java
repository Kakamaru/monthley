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

    /**
     * Cipta carta akaun standard untuk SP baru. Idempotent — skip kalau sudah ada.
     * Dipanggil oleh platform semasa onboarding.
     */
    void seedChartOfAccounts(String spCode);

    /**
     * Kod GL untuk id akaun chart_of_accounts, dalam skop SP.
     *
     * Untuk pemanggil yang menyimpan id (cth product.income_gl_account_id) tetapi
     * perlu kod untuk PostingLine. Campak kalau id tidak wujud — produk yang
     * menunjuk akaun dipadam ialah data rosak; ia patut menghentikan larian,
     * bukan jatuh senyap ke akaun lain. Konsisten dengan resolusi kod post().
     *
     * NULL bukan urusan method ini — pemanggil putuskan makna null (biasanya
     * jatuh ke default) sebelum memanggil.
     *
     * @throws IllegalStateException jika glAccountId tidak wujud untuk SP ini
     */
    String glCodeFor(String spCode, Long glAccountId);
}
