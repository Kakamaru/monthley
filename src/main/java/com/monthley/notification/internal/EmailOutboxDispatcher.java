package com.monthley.notification.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Menghantar SATU baris dalam transaksinya sendiri.
 *
 * BEAN BERASINGAN daripada EmailOutboxSender, bukan kaedah dalam kelas
 * yang sama. @Transactional berkuat kuasa melalui proksi Spring;
 * memanggil kaedah dalam kelas yang SAMA melangkau proksi sepenuhnya
 * dan anotasi itu tidak melakukan apa-apa.
 *
 * Draf pertama meletakkan kaedah ini dalam EmailOutboxSender dengan
 * komen yang menerangkan proksi — dan tetap memanggilnya secara
 * dalaman. Komen betul; kod tidak.
 *
 * Kesan kalau ia tidak dipisahkan: semua baris dalam satu transaksi.
 * Kegagalan pada baris ke-30 menggulung dua puluh sembilan yang sudah
 * DIHANTAR, dan larian seterusnya menghantarnya semula.
 */
@Component
class EmailOutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EmailOutboxDispatcher.class);

    private final EmailOutboxRepository outbox;
    private final EmailOutboxRenderer renderer;
    private final int maxAttempts;

    EmailOutboxDispatcher(EmailOutboxRepository outbox,
                          EmailOutboxRenderer renderer,
                          @Value("${monthley.outbox.max-attempts:5}") int maxAttempts) {
        this.outbox = outbox;
        this.renderer = renderer;
        this.maxAttempts = maxAttempts;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    boolean hantarSatu(long id) {
        EmailOutbox baris = outbox.findById(id).orElse(null);
        if (baris == null || baris.getStatus() != EmailOutbox.Status.PENDING) {
            return false;   // diambil oleh larian lain, atau sudah selesai
        }

        try {
            renderer.render(baris);
            baris.tandaHantar();
            outbox.save(baris);
            return true;

        } catch (RuntimeException e) {
            // Kekal PENDING sehingga had percubaan — kegagalan sementara
            // (penyedia tunggang, had kadar) mesti dicuba semula. FAILED
            // bermakna berhenti mencuba, bukan "gagal sekali".
            baris.tandaGagal(e.getMessage(), maxAttempts);
            outbox.save(baris);
            log.warn("Outbox {} gagal (cubaan {}): {}",
                    id, baris.getAttempts(), e.getMessage());
            return false;
        }
    }
}
