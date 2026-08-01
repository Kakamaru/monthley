/**
 * Billing — jana invois berulang. Cipta dokumen (document) + post ke ledger.
 *
 * notification::api dan statement::api dibenarkan untuk e-mel invois
 * ADHOC sahaja — corak sama seperti payment untuk e-mel resit. Modul ini
 * yang memegang invois dalam skop selepas ia dicipta, jadi ia yang
 * mencantumkan ketiga-tiganya:
 *
 *   statement    -> butiran invois untuk badan e-mel
 *   document     -> token pautan awam
 *   notification -> penghantaran
 *
 * Invois BERULANG tidak dihantar dari sini. Jana bil pukal menyentuh
 * ratusan akaun dalam satu transaksi; menghantar e-mel di dalamnya
 * bermakna satu penyedia e-mel yang perlahan menahan kunci baris untuk
 * keseluruhan larian. Itu aliran berbeza dan memerlukan baris gilir.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Billing Engine",
        allowedDependencies = {
                "shared",
                "ledger::api",
                "catalog::api",
                "account::api",
                "document::api",
                "tenancy::api",
                "notification::api",
                "statement::api",
                "payment::api" })   // AdvancePort — knock advance semasa jana bil (ADR 0009)
package com.monthley.billing;
