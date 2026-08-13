package com.monthley.gateway.internal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Penyulitan kelayakan gerbang.
 *
 * Legacy menyimpan pymt_gateway_key sebagai teks biasa. Untuk MonthleyPay
 * itu risiko dalaman kerana gerbangnya milik sendiri; untuk ToyyibPay,
 * User Secret Key memberi akses kepada akaun bayaran SP — sesiapa yang
 * membaca satu backup boleh mencipta bil bagi pihak mana-mana SP.
 *
 * AES-GCM dan bukan AES-CBC: GCM mengesahkan ciphertext, jadi nilai yang
 * diubah dalam DB ditolak dan bukan didekripsi menjadi sampah.
 *
 * IV dijana baharu setiap kali dan disimpan bersama ciphertext. IV tetap
 * dengan kunci sama mendedahkan corak antara nilai — dua SP dengan kunci
 * yang sama akan menghasilkan ciphertext yang sama.
 */
@Component
public class SecretCipher {

    private static final String ALGO = "AES/GCM/NoPadding";
    private static final int IV_LEN = 12;      // GCM standard
    private static final int TAG_BITS = 128;

    private final SecretKeySpec kunci;
    private final SecureRandom rng = new SecureRandom();

    /**
     * @param base64Key kunci induk 32 bait, base64. Dari pembolehubah
     *                  persekitaran — TIDAK PERNAH dalam kod atau git.
     */
    SecretCipher(@Value("${monthley.secret.master-key:}") String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException(
                    "monthley.secret.master-key tidak ditetapkan. "
                    + "Jana dengan: openssl rand -base64 32");
        }
        byte[] raw = Base64.getDecoder().decode(base64Key.trim());
        if (raw.length != 32) {
            throw new IllegalStateException(
                    "Kunci induk mesti 32 bait (AES-256); dapat " + raw.length);
        }
        this.kunci = new SecretKeySpec(raw, "AES");
    }

    /** @return base64(iv || ciphertext || tag), atau null jika input null */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) return null;
        try {
            byte[] iv = new byte[IV_LEN];
            rng.nextBytes(iv);

            Cipher c = Cipher.getInstance(ALGO);
            c.init(Cipher.ENCRYPT_MODE, kunci, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("Gagal menyulitkan kelayakan.", e);
        }
    }

    public String decrypt(String encoded) {
        if (encoded == null || encoded.isEmpty()) return null;
        try {
            byte[] all = Base64.getDecoder().decode(encoded);
            byte[] iv = new byte[IV_LEN];
            System.arraycopy(all, 0, iv, 0, IV_LEN);

            Cipher c = Cipher.getInstance(ALGO);
            c.init(Cipher.DECRYPT_MODE, kunci, new GCMParameterSpec(TAG_BITS, iv));
            byte[] pt = c.doFinal(all, IV_LEN, all.length - IV_LEN);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Mesej TIDAK mengandungi ciphertext atau sebab kripto —
            // butiran kegagalan penyahsulitan membantu penyerang.
            throw new IllegalStateException(
                    "Kelayakan gerbang tidak boleh dibaca. "
                    + "Kunci induk mungkin berbeza daripada semasa ia disimpan.");
        }
    }
}
