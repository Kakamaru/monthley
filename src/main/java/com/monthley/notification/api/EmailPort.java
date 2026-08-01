package com.monthley.notification.api;

/**
 * Satu-satunya pintu menghantar e-mel.
 * Modul lain tak perlu tahu penyedia (Resend) atau template.
 */
public interface EmailPort {

    /** Sahkan e-mel selepas daftar. */
    void sendVerification(String to, String name, String verifyUrl);

    /** Pautan reset kata laluan. */
    void sendPasswordReset(String to, String name, String resetUrl);

    /** Selamat datang selepas e-mel disahkan. */
    void sendWelcome(String to, String name, String portalUrl);

    /** Jemputan pautkan akaun (SP link akaun ke email belum berdaftar). */
    void sendInvitation(String to, String spName, String registerUrl);

    /**
     * Laporan penjanaan bil kepada admin SP.
     *
     * BUKAN dokumen: tiada nombor, tiada amaun tunggal, tiada pautan
     * awam. resendDocument menjangka ketiga-tiganya, jadi ia tidak boleh
     * dipakai semula di sini.
     *
     * Legacy menghantarnya dan ia berguna: tanpa laporan, larian tengah
     * malam yang gagal separuh jalan tidak diketahui sehingga seseorang
     * perasan invois hilang.
     *
     * @param tempoh tempoh yang BENAR-BENAR dibilkan, bukan bulan larian
     *               — POSTPAID pada Julai membilkan Jun
     */
    void sendGenerationReport(String to, String spName, String tarikh,
                              int akaunDiimbas, int invoisDikeluarkan,
                              String jumlah, java.util.List<String> tempoh);

    /**
     * Resit selepas bayaran — PAUTAN, bukan lampiran.
     *
     * PDF tidak dilampirkan: e-mel menjadi berat, resit yang dibatalkan
     * kekal dalam peti masuk selama-lamanya, dan penghantaran pukal
     * kemudian akan menjana ratusan PDF sebelum menghantar.
     *
     * Pautan berfungsi tanpa log masuk — pelanggan yang menerima e-mel
     * mungkin tiada akaun portal (DocumentAccessPort, V42).
     *
     * Panggil SELEPAS transaksi bayaran commit. Menghantar dari dalam
     * transaksi menahan kunci baris sepanjang panggilan HTTP ke penyedia
     * e-mel.
     */
    void sendReceipt(String to, String name, String spName,
                     String receiptNo, String amount, String tarikh,
                     String receiptUrl);

    /**
     * Hantar semula dokumen — resit atau invois — kepada satu atau lebih
     * alamat.
     *
     * BEBERAPA PENERIMA: dialog Resend membenarkan kerani menambah
     * alamat. Alamat pada akaun mungkin salah, atau pelanggan mahu
     * salinan ke alamat kedua.
     *
     * SATU kaedah untuk kedua-dua jenis. Badan e-mel hampir sama —
     * butiran dan butang 'Lihat'. Yang berbeza cuma label dan ayat
     * pembuka, dan dua templat yang sembilan puluh peratus sama akan
     * menyimpang.
     *
     * @param docLabel 'Resit' atau 'Invois' daripada tetapan SP
     */
    void resendDocument(java.util.List<String> to, String name, String spName,
                        String docLabel, String docNo, String amount,
                        String tarikh, String url);
}
