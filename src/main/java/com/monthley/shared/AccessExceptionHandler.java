package com.monthley.shared;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class AccessExceptionHandler {

    /**
     * Peraturan perniagaan menolak permintaan — 400, bukan 500.
     *
     * 'Bayaran manual dimatikan untuk SP ini. Hidupkan Enable Manual
     * Payment dalam Tetapan -> Resit' ialah mesej yang kerani boleh
     * bertindak. Tanpa pengendali ini Spring menjadikannya 500 dan UI
     * memaparkan 'Internal Server Error' — kerani tidak tahu apa yang
     * salah mahupun apa yang perlu dibuat.
     *
     * 500 bermaksud 'sistem rosak'. Tetapan yang dimatikan dengan sengaja
     * bukan kerosakan.
     *
     * IllegalArgumentException SENGAJA tidak dikendalikan di sini. Ia
     * digunakan untuk dua perkara yang berbeza:
     *
     *   validasi input     — 'Amaun adjustment mesti > 0'
     *   keadaan mustahil   — 'Dokumen tak wujud: 123', 'anchor_month
     *                        mesti 1-12', 'Kod mod tidak dikenali'
     *
     * Yang kedua ialah PEPIJAT. Menjadikannya 400 bermakna ia kelihatan
     * seperti kesilapan pengguna dan hilang daripada pemantauan ralat.
     *
     * Pengesahan input yang perlu 400 patut menggunakan pengecualian
     * sendiri atau @Valid, bukan berkongsi jenis dengan invarian dalaman.
     */
    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, String>> tolak(IllegalStateException e) {
        String msg = e.getMessage();
        return ResponseEntity.badRequest().body(Map.of(
                "message", msg == null || msg.isBlank()
                        ? "Permintaan tidak sah." : msg));
    }

    @ExceptionHandler(Access.AccessDeniedException.class)
    ResponseEntity<Map<String, String>> denied(Access.AccessDeniedException e) {
        return ResponseEntity.status(403).body(Map.of("message", e.getMessage()));
    }
}
