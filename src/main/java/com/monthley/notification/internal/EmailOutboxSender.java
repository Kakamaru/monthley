package com.monthley.notification.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.List;

/**
 * Meninjau outbox dan menghantar (ADR 0014).
 *
 * SATU TRANSAKSI SETIAP BARIS, bukan satu untuk batch. Kalau e-mel
 * ke-30 gagal, dua puluh sembilan yang sebelumnya kekal SENT — mereka
 * sudah keluar dan menggulungnya bermakna menghantar semula.
 *
 * Itu sebabnya kaedah berjadual TIDAK @Transactional, dan penghantaran
 * duduk dalam bean BERASINGAN: @Transactional berkuat kuasa melalui
 * proksi Spring, dan memanggil kaedah dalam kelas yang sama
 * melangkaunya.
 *
 * Batch dan kekerapan boleh dikonfigur. Sepuluh ribu penyata dengan
 * batch 50 setiap 30 saat mengambil kira-kira seratus minit — semua
 * sampai sebelum pagi kalau larian bermula tengah malam. Nilai sebenar
 * bergantung pada had penyedia, yang belum diukur.
 */
@Component
class EmailOutboxSender {

    private static final Logger log = LoggerFactory.getLogger(EmailOutboxSender.class);

    private final EmailOutboxRepository outbox;
    private final EmailOutboxDispatcher dispatcher;
    private final int batchSize;

    EmailOutboxSender(EmailOutboxRepository outbox,
                      EmailOutboxDispatcher dispatcher,
                      @Value("${monthley.outbox.batch-size:50}") int batchSize) {
        this.outbox = outbox;
        this.dispatcher = dispatcher;
        this.batchSize = batchSize;
    }

    /**
     * fixedDelayString, bukan fixedRate: larian seterusnya bermula
     * selepas yang ini SELESAI. Dengan fixedRate, penyedia yang perlahan
     * menyebabkan larian bertindih dan baris yang sama diambil dua kali.
     */
    @Scheduled(fixedDelayString = "${monthley.outbox.interval-ms:30000}")
    void tinjau() {
        List<EmailOutbox> batch = outbox.findByStatusOrderByCreatedAtAsc(
                EmailOutbox.Status.PENDING, Limit.of(batchSize));

        if (batch.isEmpty()) {
            return;
        }

        int hantar = 0, gagal = 0;
        for (EmailOutbox baris : batch) {
            if (dispatcher.hantarSatu(baris.getId())) hantar++; else gagal++;
        }
        log.info("Outbox: {} dihantar, {} gagal, {} dalam batch", hantar, gagal, batch.size());
    }
}
