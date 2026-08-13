package com.monthley.gateway.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Penyulitan kelayakan gerbang.
 *
 * Kegagalan di sini bermakna kunci gerbang SP boleh dibaca oleh sesiapa
 * yang mendapat satu backup DB — dan dengan kunci itu, mereka boleh
 * mencipta bil bagi pihak SP tersebut.
 */
class SecretCipherTest {

    private static final String KUNCI = "wANY2glLQzwg5dHr/HQIYjhl6LcDR7OhIKii2H9Hf7M=";

    private SecretCipher cipher() { return new SecretCipher(KUNCI); }

    @Test
    @DisplayName("teks disulit dan dinyahsulit kembali sama")
    void pusinganPenuh() {
        SecretCipher c = cipher();
        String asal = "9pkk8bbc-bspj-onbn-zrdh-9ncu7icrdd7r";

        String enc = c.encrypt(asal);
        assertThat(enc).isNotNull().isNotEqualTo(asal);
        assertThat(c.decrypt(enc)).isEqualTo(asal);
    }

    /**
     * IV baharu setiap kali.
     *
     * Dengan IV tetap, dua SP yang menggunakan kunci gerbang yang sama
     * menghasilkan ciphertext yang sama — dan itu memberitahu penyerang
     * yang mereka berkongsi kelayakan, tanpa perlu mendekripsi apa-apa.
     */
    @Test
    @DisplayName("nilai sama menghasilkan ciphertext BERBEZA")
    void ivBaharuSetiapKali() {
        SecretCipher c = cipher();
        String teks = "kunci-rahsia";

        String a = c.encrypt(teks);
        String b = c.encrypt(teks);

        assertThat(a).isNotEqualTo(b);
        assertThat(c.decrypt(a)).isEqualTo(teks);
        assertThat(c.decrypt(b)).isEqualTo(teks);
    }

    /**
     * Ciphertext yang diubah DITOLAK.
     *
     * Ini sebab AES-GCM dan bukan AES-CBC: CBC akan mendekripsi bait yang
     * diubah menjadi sampah tanpa aduan, dan sampah itu dihantar ke
     * gerbang sebagai kunci.
     */
    @Test
    @DisplayName("ciphertext yang diubah ditolak, bukan didekripsi jadi sampah")
    void ciphertextDiubahDitolak() {
        SecretCipher c = cipher();
        String enc = c.encrypt("kunci-rahsia");

        // Tukar satu aksara di tengah.
        char[] rosak = enc.toCharArray();
        int tengah = rosak.length / 2;
        rosak[tengah] = (rosak[tengah] == 'A') ? 'B' : 'A';

        assertThatThrownBy(() -> c.decrypt(new String(rosak)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tidak boleh dibaca");
    }

    @Test
    @DisplayName("kunci induk berbeza tidak boleh membaca")
    void kunciLainTidakBoleh() {
        String enc = cipher().encrypt("kunci-rahsia");

        SecretCipher lain = new SecretCipher("aGVsbG93b3JsZGhlbGxvd29ybGRoZWxsb3dvcmxkMTI=");
        assertThatThrownBy(() -> lain.decrypt(enc))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * Mesej ralat TIDAK mendedahkan butiran kripto.
     *
     * "Tag mismatch" atau "BadPaddingException" memberitahu penyerang
     * dengan tepat sejauh mana tekaan mereka menghampiri.
     */
    @Test
    @DisplayName("mesej ralat tidak mendedahkan ciphertext atau sebab kripto")
    void mesejRalatSenyap() {
        SecretCipher c = cipher();
        String enc = c.encrypt("rahsia");

        try {
            new SecretCipher("aGVsbG93b3JsZGhlbGxvd29ybGRoZWxsb3dvcmxkMTI=").decrypt(enc);
        } catch (IllegalStateException e) {
            assertThat(e.getMessage()).doesNotContain(enc);
            assertThat(e.getMessage()).doesNotContainIgnoringCase("tag");
            assertThat(e.getMessage()).doesNotContainIgnoringCase("padding");
        }
    }

    @Test
    @DisplayName("kunci induk salah saiz ditolak semasa boot")
    void kunciSalahSaizDitolak() {
        assertThatThrownBy(() -> new SecretCipher("cGVuZGVr"))   // 6 bait
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bait");
    }

    @Test
    @DisplayName("kunci induk kosong ditolak dengan arahan menjananya")
    void kunciKosongDitolak() {
        assertThatThrownBy(() -> new SecretCipher(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("openssl rand");
    }

    @Test
    @DisplayName("null dan kosong dikendalikan tanpa ralat")
    void nilaiKosong() {
        SecretCipher c = cipher();
        assertThat(c.encrypt(null)).isNull();
        assertThat(c.encrypt("")).isNull();
        assertThat(c.decrypt(null)).isNull();
        assertThat(c.decrypt("")).isNull();
    }
}
