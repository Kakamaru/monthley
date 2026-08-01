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
 *   STATEMENT          P4 — perlukan statement_access_token (P3)
 *   REMINDER           P7
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

    /** Nilai param mengikut kunci, tanpa mengira lajur mana ia duduk. */
    private static String nilai(EmailOutbox baris, String kunci) {
        if (kunci.equals(baris.getParam1Key())) return baris.getParam1Val();
        if (kunci.equals(baris.getParam2Key())) return baris.getParam2Val();
        return null;
    }
}
