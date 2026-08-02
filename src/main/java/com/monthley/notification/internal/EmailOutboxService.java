package com.monthley.notification.internal;

import com.monthley.notification.api.EmailOutboxPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
class EmailOutboxService implements EmailOutboxPort {

    private static final Logger log = LoggerFactory.getLogger(EmailOutboxService.class);

    /** Dua lajur pasangan sahaja (V55). */
    private static final int MAKS_PARAM = 2;

    private final EmailOutboxRepository outbox;

    EmailOutboxService(EmailOutboxRepository outbox) {
        this.outbox = outbox;
    }

    @Override
    @Transactional
    public boolean queue(String spCode, Kind kind, String refKey,
                         String to, String cc, Map<String, String> params) {

        if (to == null || to.isBlank()) {
            return false;   // tiada alamat bukan ralat — akaun mungkin tiada e-mel
        }

        // Melontar, bukan memotong senyap: params ketiga yang hilang
        // bermakna e-mel dihantar dengan maklumat kurang dan tiada apa
        // yang menjerit.
        if (params != null && params.size() > MAKS_PARAM) {
            throw new IllegalArgumentException(
                    "Outbox menyimpan paling banyak " + MAKS_PARAM + " params; diberi "
                    + params.size() + ". Tambah lajur dalam migrasi baharu.");
        }

        var it = params == null ? java.util.Collections.<Map.Entry<String, String>>emptyIterator()
                                : params.entrySet().iterator();
        var p1 = it.hasNext() ? it.next() : null;
        var p2 = it.hasNext() ? it.next() : null;

        // SEMAK DAHULU, walaupun UNIQUE menangkapnya.
        //
        // Draf pertama bergantung sepenuhnya pada kekangan: sisip, tangkap
        // DataIntegrityViolationException, pulang false. Itu menutup lubang
        // perlumbaan tetapi merosakkan SESI Hibernate — pengecualian semasa
        // flush menjadikan setiap operasi seterusnya dalam transaksi yang
        // sama melontar AssertionFailure.
        //
        // Kesannya: kerani yang menjana bil dua kali memecahkan larian
        // kedua sepenuhnya, dan laporan penjanaan gagal beratur selepasnya.
        //
        // Semakan menangani kes NORMAL (larian kedua bagi tempoh yang
        // sama); UNIQUE kekal sebagai jaring untuk perlumbaan sebenar,
        // yang jarang dan gagal dengan bersih kerana tiada apa berlaku
        // selepasnya dalam transaksi itu.
        if (outbox.existsBySpCodeAndKindAndRefKey(spCode, kind.name(), refKey)) {
            return false;
        }

        try {
            outbox.saveAndFlush(new EmailOutbox(
                    spCode, kind.name(), refKey, to.trim(),
                    (cc == null || cc.isBlank()) ? null : cc.trim(),
                    p1 == null ? null : p1.getKey(), p1 == null ? null : p1.getValue(),
                    p2 == null ? null : p2.getKey(), p2 == null ? null : p2.getValue()));
            return true;

        } catch (DataIntegrityViolationException dup) {
            // UNIQUE(sp_code, kind, ref_key) — sudah beratur. Ini laluan
            // NORMAL untuk larian kedua bagi tempoh yang sama, bukan
            // ralat. Menyemak dahulu kemudian menyisip membiarkan lubang
            // antara semakan dan tulisan.
            log.debug("Sudah beratur: {} {}:{}", spCode, kind, refKey);
            return false;
        }
    }
}
