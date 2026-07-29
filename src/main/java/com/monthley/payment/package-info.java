/**
 * Payment — peruntukan FIFO (knock-off), resit sebagai dokumen, pembatalan contra.
 *
 * notification::api dan statement::api dibenarkan untuk e-mel resit
 * SAHAJA. Modul ini yang memegang kedua-duanya dalam skop selepas
 * bayaran, jadi ia yang mencantumkannya:
 *
 *   statement    -> butiran resit untuk badan e-mel
 *   document     -> token pautan awam
 *   notification -> penghantaran
 *
 * notification tidak boleh memanggil statement (kedua-duanya { shared }),
 * dan tiada modul penyelaras diperlukan untuk satu aliran.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Payment & Allocation",
        allowedDependencies = { "shared", "ledger::api", "document::api",
                                "notification::api", "statement::api" })
package com.monthley.payment;
