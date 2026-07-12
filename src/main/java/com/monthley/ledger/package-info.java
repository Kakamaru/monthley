/**
 * Ledger — double-entry accounting. SOURCE OF TRUTH untuk semua baki.
 *
 * PERATURAN EMAS: tiada modul lain boleh menulis terus ke journal_line.
 * Semua posting mesti melalui {@code ledger.api.LedgerPort}.
 * Ini yang menjamin integriti kewangan bila modul bertambah.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Ledger (Double-Entry)",
        allowedDependencies = { "shared" })
package com.monthley.ledger;
