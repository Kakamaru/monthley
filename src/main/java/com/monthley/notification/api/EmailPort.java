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
}
