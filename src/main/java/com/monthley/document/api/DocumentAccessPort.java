package com.monthley.document.api;

import java.util.Optional;

/**
 * Pautan awam kepada dokumen — tiada log masuk diperlukan.
 *
 * Pelanggan yang menerima e-mel resit mungkin TIADA akaun portal.
 * Pautan mesti berfungsi tanpa JWT dan tanpa TenantContext.
 *
 * CASE-006: legacy mencipta dokumen 'P' HANTU untuk setiap e-mel
 * semata-mata untuk mendapat UUID pautan — 51 rekod bukan-kewangan
 * dalam jadual kewangan satu akaun. Token duduk dalam jadualnya sendiri.
 */
public interface DocumentAccessPort {

    /**
     * Token untuk dokumen ini, cipta jika belum ada.
     *
     * SATU token per dokumen: menghantar semula resit yang sama
     * menghasilkan pautan yang SAMA, dan e-mel lama kekal berfungsi.
     * Skrin Finance Documents mempunyai 'Resend Document' yang bergantung
     * pada ini.
     */
    String tokenFor(String spCode, long documentId, DocumentType type);

    /**
     * Selesaikan token kepada dokumen, dan rekod bahawa ia dilihat.
     *
     * Kosong jika token tidak wujud ATAU sudah dibatalkan — pemanggil
     * tidak boleh membezakan kedua-duanya, supaya token tidak boleh
     * dibilang.
     */
    Optional<ResolvedDocument> resolve(String token);

    record ResolvedDocument(String spCode, long documentId, DocumentType type) {}

    /** Matikan pautan — dipanggil apabila dokumen dibatalkan. */
    void revoke(long documentId);

    /**
     * Jenis, nombor dan status dokumen.
     *
     * Pemanggil di luar modul ini memerlukan ketiga-tiganya untuk
     * memutuskan cara merender dan sama ada boleh dihantar. Mereka tidak
     * sepatutnya menyoal financial_document sendiri.
     */
    Optional<DocumentInfo> describe(String spCode, long documentId);

    record DocumentInfo(DocumentType type, String docNo, boolean cancelled) {}
}
