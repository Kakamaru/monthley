package com.monthley.document.internal;

import org.springframework.data.jpa.repository.JpaRepository;

interface DocumentLineRepository extends JpaRepository<FinancialDocumentLine, Long> {

    /** Baris berulang: kunci = (akaun, produk, mula liputan). Padan idem_key. */
    boolean existsByAccountIdAndProductIdAndPeriodStartAndActiveTrue(
            Long accountId, Long productId, java.time.LocalDate periodStart);

    /**
     * Baris ONE_TIME: kunci = (akaun, produk) sahaja — tiada dimensi masa.
     * periodStart untuk 1T ialah 1 Jan tahun semasa, jadi semakan di atas
     * akan terlepas caj tahun berikutnya. Padan idem_key ':ONCE'. Rujuk V18.
     */
    boolean existsByAccountIdAndProductIdAndOnceOnlyTrueAndActiveTrue(
            Long accountId, Long productId);
}
