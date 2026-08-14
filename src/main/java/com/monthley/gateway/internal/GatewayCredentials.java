package com.monthley.gateway.internal;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Kelayakan gerbang per SP, dinyahsulit atas permintaan.
 *
 * Kunci HIDUP disulitkan dalam sp_payment_setting.gateway_key_enc dan
 * hanya dinyahsulit ketika hendak memanggil gerbang. Ia TIDAK PERNAH
 * dipulangkan kepada klien, dilog, atau dimasukkan ke dalam mesej ralat.
 *
 * gateway_key lama (teks biasa) dibaca sebagai sandaran supaya SP yang
 * belum berpindah masih berfungsi — tetapi apa-apa yang DITULIS sentiasa
 * disulitkan.
 */
@Component
class GatewayCredentials {

    private final SecretCipher cipher;

    @PersistenceContext
    private EntityManager em;

    GatewayCredentials(SecretCipher cipher) {
        this.cipher = cipher;
    }

    record Creds(String gateway, String secretKey, String categoryCode, boolean sandbox) {}

    @Transactional(readOnly = true)
    Creds forSp(String spCode) {
        List<?> rows = em.createNativeQuery("""
                SELECT gateway, gateway_key_enc, gateway_key, category_code,
                       sandbox, online_payment
                FROM   sp_payment_setting WHERE sp_code = :sp
                """).setParameter("sp", spCode).getResultList();

        if (rows.isEmpty()) {
            throw new IllegalStateException(
                    "Tetapan bayaran tidak wujud untuk " + spCode + ".");
        }
        Object[] r = (Object[]) rows.get(0);

        if (!bool(r[5])) {
            throw new IllegalStateException(
                    "Bayaran dalam talian tidak diaktifkan untuk organisasi ini.");
        }

        String enc = (String) r[1];
        String plain = (String) r[2];

        // Yang disulitkan didahulukan; teks biasa ialah sandaran untuk SP
        // yang belum berpindah.
        String kunci = (enc != null && !enc.isBlank()) ? cipher.decrypt(enc) : plain;

        if (kunci == null || kunci.isBlank()) {
            throw new IllegalStateException(
                    "Kunci gerbang belum ditetapkan untuk organisasi ini.");
        }

        String kategori = (String) r[3];
        if (kategori == null || kategori.isBlank()) {
            throw new IllegalStateException(
                    "Kod kategori gerbang belum ditetapkan untuk organisasi ini.");
        }

        return new Creds((String) r[0], kunci, kategori, bool(r[4]));
    }

    @Transactional(readOnly = true)
    boolean isSandbox(String spCode) {
        List<?> r = em.createNativeQuery(
                "SELECT sandbox FROM sp_payment_setting WHERE sp_code = :sp")
                .setParameter("sp", spCode).getResultList();
        // Lalai SANDBOX bila tiada tetapan: pemasangan yang salah
        // konfigurasi patut gagal ke arah tidak menyentuh wang sebenar.
        return r.isEmpty() || bool(r.get(0));
    }

    /** Simpan kelayakan — sentiasa disulitkan, tidak pernah teks biasa. */
    @Transactional
    void save(String spCode, String gateway, String secretKey,
              String categoryCode, boolean sandbox) {
        em.createNativeQuery("""
                UPDATE sp_payment_setting
                SET    gateway = :gw,
                       gateway_key_enc = :enc,
                       gateway_key = NULL,
                       category_code = :cat,
                       sandbox = :sb
                WHERE  sp_code = :sp
                """)
                .setParameter("gw", gateway)
                .setParameter("enc", cipher.encrypt(secretKey))
                .setParameter("cat", categoryCode)
                .setParameter("sb", sandbox ? 1 : 0)
                .setParameter("sp", spCode)
                .executeUpdate();
    }

    private static boolean bool(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        return ((Number) v).intValue() != 0;
    }
}
