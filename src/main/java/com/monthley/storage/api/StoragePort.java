package com.monthley.storage.api;

import java.io.InputStream;
import java.time.Duration;

/**
 * Simpan dan dapatkan fail.
 *
 * Modul lain tidak tahu MinIO wujud — mereka meminta kunci objek dan
 * mendapat URL.
 */
public interface StoragePort {

    /** Fail yang sesiapa boleh lihat: poster kempen. */
    String PUBLIC = "monthley-public";

    /** Fail yang memerlukan kebenaran: gambar aduan, lampiran. */
    String PRIVATE = "monthley-private";

    /**
     * Muat naik dan pulangkan kunci objek.
     *
     * @param bucket  PUBLIC atau PRIVATE
     * @param key     laluan dalam baldi, cth 'campaigns/SP0001/abc123.jpg'
     */
    String put(String bucket, String key, InputStream data, long size, String contentType);

    /**
     * URL untuk fail AWAM.
     *
     * Kekal — tiada tandatangan, tiada tempoh luput. Poster kempen dikongsi
     * dalam WhatsApp dan mesti berfungsi berbulan kemudian.
     */
    String publicUrl(String key);

    /**
     * URL bertandatangan untuk fail PERIBADI.
     *
     * Luput supaya pautan yang bocor tidak kekal sah. Gambar aduan milik
     * pengadu dan SP; sesiapa yang mendapat URL selepas tempoh itu
     * mendapat ralat.
     */
    String signedUrl(String key, Duration tempoh);

    void delete(String bucket, String key);
}
