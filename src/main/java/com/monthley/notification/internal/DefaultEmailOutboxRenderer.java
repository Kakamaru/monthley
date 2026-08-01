package com.monthley.notification.internal;

import com.monthley.notification.api.EmailOutboxPort.Kind;
import org.springframework.stereotype.Component;

/**
 * Pelaksanaan sementara — TIADA jenis disokong lagi (ADR 0014 P1b).
 *
 * Mekanik gilir diuji berasingan daripada kandungan: batch, transaksi
 * per-baris, cuba semula dan had percubaan ialah bahagian yang paling
 * mudah salah, dan mengujinya tanpa rendering sebenar menjadikan
 * kegagalan jelas.
 *
 * Melontar, bukan pulang senyap. Baris yang "berjaya" tanpa e-mel
 * keluar ialah kegagalan senyap ke arah yang salah: ia ditandakan SENT
 * dan tiada siapa tahu pelanggan tidak menerima apa-apa.
 */
@Component
class DefaultEmailOutboxRenderer implements EmailOutboxRenderer {

    @Override
    public void render(EmailOutbox baris) {
        Kind kind = Kind.valueOf(baris.getKind());
        throw new UnsupportedOperationException(
                "Renderer untuk " + kind + " belum dilaksana (ADR 0014). "
                + "Baris " + baris.getId() + " kekal dalam gilir.");
    }
}
