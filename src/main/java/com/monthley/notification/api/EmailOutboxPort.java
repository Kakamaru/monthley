package com.monthley.notification.api;

import java.util.Map;

/**
 * Baris gilir penghantaran (ADR 0014).
 *
 * Pemanggil menulis baris dalam transaksinya sendiri dan pulang. Tugas
 * berjadual menghantar kemudian. Jana bil yang menghasilkan sepuluh ribu
 * penyata tidak menunggu penyedia e-mel, dan kegagalan penghantaran
 * tidak menggulung invois.
 *
 * BADAN TIDAK DISIMPAN. Baris membawa jenis dan parameter; badan
 * dirender semasa menghantar. Legacy menyimpan HTML penuh — sepuluh ribu
 * salinan setiap bulan, dan pembetulan templat tidak menjejaskan baris
 * yang sudah beratur.
 */
public interface EmailOutboxPort {

    /** Jenis notifikasi. Menentukan templat dan parameter yang dijangka. */
    enum Kind {
        /** Penyata akaun selepas jana bil — satu per AKAUN. */
        STATEMENT,
        /** Ringkasan larian kepada admin SP — satu per SP. */
        GENERATION_REPORT,
        /** Peringatan tunggakan — termasuk akaun TIDAK aktif. */
        REMINDER
    }

    /**
     * Beratur satu penghantaran.
     *
     * IDEMPOTEN pada (sp, kind, refKey): larian kedua untuk tempoh yang
     * sama tidak menghasilkan baris kedua. Corak sama seperti idem_key
     * pada baris dokumen — pemanggil tidak perlu menyemak dahulu.
     *
     * @param refKey rujukan unik dalam jenisnya; 'akaun:tempoh' untuk
     *               penyata, 'sp:tempoh' untuk laporan penjanaan
     * @param cc     alamat kedua akaun; null kalau tiada
     * @param params paling banyak DUA pasang — disimpan sebagai lajur,
     *               bukan JSON (corak legacy). Lebih daripada dua
     *               ditolak: kalau ketiga diperlukan, tambah lajur
     *               dengan sengaja dan bukan secara senyap
     * @return true jika baris dicipta, false jika sudah beratur
     */
    boolean queue(String spCode, Kind kind, String refKey,
                  String to, String cc, Map<String, String> params);
}
