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
}
