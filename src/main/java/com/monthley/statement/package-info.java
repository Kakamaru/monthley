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
 *
 * document::api dibenarkan untuk DocumentAccessPort sahaja — pautan awam
 * memerlukan penyelesaian token, dan modul ini yang merender dokumen
 * yang token itu tunjuk. Ia TIDAK menulis melalui DocumentPort.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Account Statement",
        allowedDependencies = { "shared", "document::api" })
package com.monthley.statement;
