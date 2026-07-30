package com.monthley.document.api;

import java.time.LocalDate;
import java.util.List;

/**
 * Permintaan cipta invois.
 *
 * @param periodId period LARIAN — aras charge_frequency AKAUN.
 *                 Berbeza dari NewDocumentLine.periodId, yang ialah period
 *                 LIPUTAN pada aras charge_frequency PRODUK.
 *                 Rujuk docs/domain/billing-rules.md §3
 */
public record NewInvoice(
        String spCode,
        Long accountId,
        long periodId,
        LocalDate docDate,
        LocalDate dueDate,
        String title,
        List<NewDocumentLine> lines,
        /**
         * Langkau semakan pendua idem_key.
         *
         * Untuk invois BERULANG semakan itu penting: ia menghalang
         * Januari dijana dua kali. Untuk invois ADHOC ia salah — semua
         * berkongsi satu akaun ADHOC-SALES, jadi dua pembeli yang membeli
         * buku yang sama kelihatan seperti pendua dan yang kedua
         * digugurkan SENYAP.
         *
         * DB tetap melindungi: idem_key menjadi NULL apabila period_start
         * NULL, dan UNIQUE membenarkan berbilang NULL.
         */
        boolean skipDuplicateCheck) {

    /** Invois berulang — semakan pendua dikuatkuasakan. */
    public NewInvoice(String spCode, Long accountId, long periodId,
                      LocalDate docDate, LocalDate dueDate, String title,
                      List<NewDocumentLine> lines) {
        this(spCode, accountId, periodId, docDate, dueDate, title, lines, false);
    }
}
