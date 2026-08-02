package com.monthley.notification.internal;

import com.monthley.notification.api.EmailOutboxPort.Kind;
import com.monthley.notification.api.EmailPort;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Merender baris outbox mengikut jenisnya (ADR 0014).
 *
 * Melontar untuk jenis yang belum dilaksana — bukan pulang senyap.
 * Baris yang "berjaya" tanpa e-mel keluar ditandakan SENT dan tiada
 * siapa tahu pelanggan tidak menerima apa-apa.
 *
 *   GENERATION_REPORT  P2 — SIAP
 *   STATEMENT          P4 — SIAP
 *   REMINDER           P7
 *
 * SEMUA DATA DATANG DARIPADA BARIS. Renderer tidak menyoal penyata,
 * tidak menjana token, tidak mengira baki — modul notification
 * mempunyai allowedDependencies = { shared }, dan menambah
 * statement::api di sini mencipta kitaran (statement sudah bergantung
 * padanya untuk e-mel resit).
 *
 * Itu juga lebih betul: baki pada masa BERATUR ialah baki yang e-mel
 * patut laporkan. Menyoalnya semasa menghantar memberi nombor yang
 * berbeza seratus minit kemudian, dan e-mel akan bercanggah dengan
 * dirinya sendiri.
 */
@Component
class DefaultEmailOutboxRenderer implements EmailOutboxRenderer {

    private final EmailPort email;

    DefaultEmailOutboxRenderer(EmailPort email) {
        this.email = email;
    }

    @Override
    public void render(EmailOutbox baris) {
        Kind kind = Kind.valueOf(baris.getKind());
        switch (kind) {
            case GENERATION_REPORT -> laporanPenjanaan(baris);
            case STATEMENT -> penyata(baris);
            default -> throw new UnsupportedOperationException(
                    "Renderer untuk " + kind + " belum dilaksana (ADR 0014). "
                    + "Baris " + baris.getId() + " kekal dalam gilir.");
        }
    }

    /**
     * param1 = nama SP, param2 = ringkasan larian.
     *
     * Ringkasan disimpan sebagai rentetan tunggal kerana outbox
     * mempunyai dua lajur params sahaja dan laporan memerlukan lima
     * nilai. Bentuk: 'tarikh|akaun|invois|jumlah|tempoh,tempoh'.
     *
     * Alternatifnya menambah lajur untuk satu jenis notifikasi, atau
     * menyoal semula kiraan semasa menghantar — yang bermakna nombor
     * BERUBAH antara larian dan penghantaran kalau kerani menjana
     * sekali lagi.
     */
    private void laporanPenjanaan(EmailOutbox baris) {
        // Dibaca ikut KUNCI, bukan kedudukan. Kedudukan bergantung pada
        // susunan Map yang dihantar pemanggil, dan satu pemanggil yang
        // menggunakan Map.of menyimpannya terbalik tanpa apa-apa
        // menjerit sehingga renderer gagal.
        String spName = nilai(baris, "p_sp_name");
        String ringkasan = nilai(baris, "p_summary");
        String[] f = (ringkasan == null ? "" : ringkasan).split("\\|", -1);
        if (f.length < 5) {
            throw new IllegalStateException(
                    "Ringkasan laporan tidak lengkap pada baris " + baris.getId());
        }

        List<String> tempoh = f[4].isBlank() ? List.of() : List.of(f[4].split(","));

        email.sendGenerationReport(baris.getToEmail(), spName, f[0],
                Integer.parseInt(f[1]), Integer.parseInt(f[2]), f[3], tempoh);
    }

    /**
     * param1 = nama penerima, param2 = butiran berpisah.
     *
     * Bentuk: 'spName|akaunNo|tempoh|baki|spEmail|spPhone|url'
     *
     * Tujuh nilai dalam satu lajur kerana outbox mempunyai dua pasang
     * kunci/nilai sahaja. Menambah lajur untuk satu jenis notifikasi
     * bermakna jadual berkembang setiap kali jenis baharu muncul.
     *
     * cc datang daripada LAJUR cc_email, bukan daripada rentetan ini —
     * ia sudah ada tempatnya, dan menyimpannya di dua tempat bermakna
     * dua tempat untuk menyimpang.
     */
    private void penyata(EmailOutbox baris) {
        String nama = nilai(baris, "p_nama");
        String butiran = nilai(baris, "p_stmt");
        String[] f = (butiran == null ? "" : butiran).split("\\|", -1);
        if (f.length < 7) {
            throw new IllegalStateException(
                    "Butiran penyata tidak lengkap pada baris " + baris.getId());
        }

        email.sendStatement(baris.getToEmail(), baris.getCcEmail(),
                nama, f[0], f[1], f[2], f[3], f[4], f[5], f[6]);
    }

    /** Nilai param mengikut kunci, tanpa mengira lajur mana ia duduk. */
    private static String nilai(EmailOutbox baris, String kunci) {
        if (kunci.equals(baris.getParam1Key())) return baris.getParam1Val();
        if (kunci.equals(baris.getParam2Key())) return baris.getParam2Val();
        return null;
    }
}
