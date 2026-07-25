/**
 * Statement — penyata akaun (ADR 0010).
 *
 * Unjuran BACA-SAHAJA. Modul ini tidak memiliki data dan tidak menulis
 * apa-apa. Ia membaca VIEW sahaja (account_document_entry,
 * account_balance) dan TIDAK PERNAH jadual asas — VIEW ialah kontrak
 * baca yang diterbitkan.
 *
 * Ia tidak pernah memeriksa doc_type untuk menentukan tanda; itu kerja
 * account_document_entry.signed_amount. Satu takrifan, satu tempat
 * (cara-kerja.md guard 6).
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Account Statement",
        allowedDependencies = { "shared" })
package com.monthley.statement;
