package com.monthley.document.api;

/**
 * Jana nombor dokumen berturutan untuk modul di luar `document`.
 *
 * Tiga lapisan ADR 0012 kekal, tetapi tanggungjawabnya dibahagi berbeza
 * daripada dokumen jualan:
 *
 *   tetapan (prefix/saiz/mula)  — PEMANGGIL, dari tetapan modulnya sendiri
 *   kaunter berkunci            — di sini (SELECT ... FOR UPDATE)
 *   jaminan unik                — PEMANGGIL, UNIQUE pada jadual modulnya
 *
 * Tetapan dihantar masuk dan bukan dicari di sini kerana modul `document`
 * tidak sepatutnya tahu modul mana yang memanggilnya. Kalau tidak,
 * DocumentNumberService.tetapan() memerlukan cabang baharu bagi setiap
 * modul yang ditambah — dan SP yang tidak melanggan modul terpaksa membawa
 * lajur tetapannya dalam sp_document_setting.
 *
 * <p><b>Keunikan bukan urusan port ini.</b> Semakan dalaman hanya melihat
 * financial_document, jadi nombor yang dijana di sini boleh berlanggar
 * dengan baris sedia ada dalam jadual modul. Pemanggil MESTI mempunyai
 * kekangan UNIQUE pada lajur nombornya.
 *
 * <p>Mesti dipanggil dalam transaksi yang sedia ada: kunci baris dipegang
 * sehingga commit, iaitu selepas dokumen dimasukkan.
 */
public interface DocumentNumberPort {

    /**
     * Nombor seterusnya untuk (SP, jenis turutan).
     *
     * @param seqType pengecam turutan, cth "EXP_PV" atau "EXP_CASH".
     *                Guna prefiks modul supaya ia tidak berlanggar dengan
     *                jenis dokumen jualan.
     * @param prefix  awalan nombor, dari tetapan modul pemanggil
     * @param padding bilangan digit
     * @param start   nombor mula bila turutan dicipta atau prefix berubah
     */
    String next(String spCode, String seqType, String prefix, int padding, long start);

    /**
     * Nilai kaunter MENTAH — tanpa prefix atau padding.
     *
     * Untuk pemanggil yang membina formatnya sendiri. Rujukan gerbang
     * menggunakan base36 supaya rujukan kekal pendek: ruang rujukan pada
     * penyata bank terhad, dan prefix SP sudah memakan sebahagiannya.
     *
     * Kunci baris yang sama dengan next(); mesti dipanggil dalam transaksi
     * sedia ada.
     */
    long nextValue(String spCode, String seqType);
}
