package com.monthley.memo.internal;

import com.monthley.shared.Access;
import com.monthley.shared.ModuleGuard;
import com.monthley.shared.TenantContext;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Memo — sisi SP.
 *
 *   GET    /api/v1/memos
 *   POST   /api/v1/memos
 *   PUT    /api/v1/memos/{id}
 *   POST   /api/v1/memos/{id}/publish
 *   POST   /api/v1/memos/{id}/unpublish
 *   DELETE /api/v1/memos/{id}
 */
@RestController
@RequestMapping("/api/v1/memos")
class MemoController {

    private final MemoRepository memos;
    private final ModuleGuard modules;

    @PersistenceContext
    private EntityManager em;

    MemoController(MemoRepository memos, ModuleGuard modules) {
        this.memos = memos;
        this.modules = modules;
    }

    record MemoRow(Long id, String title, String body, String status,
                   LocalDateTime publishedAt, LocalDate expiresOn,
                   boolean expired, long audienceCount) {}

    record SaveRequest(String title, String body, LocalDate expiresOn) {}

    @GetMapping
    @SuppressWarnings("unchecked")
    List<MemoRow> list() {
        Access.requireAnyRole("melihat memo", "SP_ADMIN", "CLERK");
        String sp = sp();

        // Bilangan penerima dikira sekali untuk semua baris: setiap memo
        // pergi kepada SEMUA pelanggan SP, jadi nombornya sama.
        long penerima = ((Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM account "
                + "WHERE sp_code = :sp AND status = 'ACTIVE' AND payer_user_id IS NOT NULL")
                .setParameter("sp", sp).getSingleResult()).longValue();

        List<Object[]> rows = em.createNativeQuery("""
                SELECT id, title, body, status, published_at, expires_on,
                       (expires_on IS NOT NULL AND expires_on < CURDATE()) AS luput
                FROM   memo_notice WHERE sp_code = :sp
                ORDER  BY status = 'DRAFT' DESC, COALESCE(published_at, created_at) DESC
                """).setParameter("sp", sp).getResultList();

        List<MemoRow> out = new ArrayList<>();
        for (Object[] r : rows) {
            out.add(new MemoRow(((Number) r[0]).longValue(), (String) r[1], (String) r[2],
                    (String) r[3], toDt(r[4]), toDate(r[5]), bool(r[6]), penerima));
        }
        return out;
    }

    @PostMapping
    @Transactional
    ResponseEntity<?> create(@RequestBody SaveRequest r) {
        Access.requireRole("SP_ADMIN", "mencipta memo");
        modules.require(ModuleGuard.MEMO, "mencipta memo");

        MemoNotice m = new MemoNotice(sp(), wajib(r.title(), "Tajuk"),
                wajib(r.body(), "Isi memo"));
        m.setExpiresOn(r.expiresOn());
        return ResponseEntity.ok(Map.of("id", memos.save(m).getId()));
    }

    @PutMapping("/{id}")
    @Transactional
    ResponseEntity<?> update(@PathVariable Long id, @RequestBody SaveRequest r) {
        Access.requireRole("SP_ADMIN", "mengubah memo");
        modules.require(ModuleGuard.MEMO, "mengubah memo");

        MemoNotice m = ambil(id);
        m.setTitle(wajib(r.title(), "Tajuk"));
        m.setBody(wajib(r.body(), "Isi memo"));
        m.setExpiresOn(r.expiresOn());
        return ResponseEntity.ok(Map.of("message", "Memo dikemas kini."));
    }

    /**
     * Terbitkan — memo menjadi kelihatan kepada pelanggan.
     *
     * Memo yang sudah luput tidak boleh diterbitkan: ia akan terbit dan
     * hilang serentak, dan SP menyangka penerbitan gagal.
     */
    @PostMapping("/{id}/publish")
    @Transactional
    ResponseEntity<?> publish(@PathVariable Long id) {
        Access.requireRole("SP_ADMIN", "menerbitkan memo");
        modules.require(ModuleGuard.MEMO, "menerbitkan memo");

        MemoNotice m = ambil(id);
        if (m.getExpiresOn() != null && m.getExpiresOn().isBefore(LocalDate.now())) {
            throw new IllegalStateException(
                    "Tarikh luput sudah berlalu. Kemas kini tarikh sebelum menerbitkan.");
        }
        m.publish();
        return ResponseEntity.ok(Map.of("message", "Memo diterbitkan."));
    }

    /** Tarik balik — memo hilang dari portal pelanggan tetapi tidak dipadam. */
    @PostMapping("/{id}/unpublish")
    @Transactional
    ResponseEntity<?> unpublish(@PathVariable Long id) {
        Access.requireRole("SP_ADMIN", "menarik balik memo");
        modules.require(ModuleGuard.MEMO, "menarik balik memo");

        ambil(id).unpublish();
        return ResponseEntity.ok(Map.of("message", "Memo ditarik balik ke draf."));
    }

    /**
     * Padam.
     *
     * Memo boleh dipadam sepenuhnya — berbeza daripada kategori atau
     * pembekal, tiada apa yang merujuknya. Memo yang sudah diterbitkan
     * perlu ditarik balik dahulu: memadamnya terus bermakna pelanggan
     * yang sedang membacanya mendapat ralat.
     */
    @DeleteMapping("/{id}")
    @Transactional
    ResponseEntity<?> delete(@PathVariable Long id) {
        Access.requireRole("SP_ADMIN", "memadam memo");
        modules.require(ModuleGuard.MEMO, "memadam memo");

        MemoNotice m = ambil(id);
        if (m.getStatus() == MemoNotice.Status.PUBLISHED) {
            throw new IllegalStateException(
                    "Tarik balik memo ini dahulu sebelum memadamnya.");
        }
        memos.delete(m);
        return ResponseEntity.ok(Map.of("message", "Memo dipadam."));
    }

    // ---------- helper ----------

    private MemoNotice ambil(Long id) {
        return memos.findByIdAndSpCode(id, sp()).orElseThrow(
                () -> new IllegalStateException("Memo tidak wujud: " + id));
    }

    private static String wajib(String v, String label) {
        String t = v == null ? "" : v.trim();
        if (t.isBlank()) throw new IllegalStateException(label + " wajib diisi.");
        return t;
    }

    private static boolean bool(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        return ((Number) v).intValue() != 0;
    }

    private static LocalDate toDate(Object v) {
        if (v == null) return null;
        if (v instanceof LocalDate d) return d;
        if (v instanceof java.sql.Date d) return d.toLocalDate();
        return null;
    }

    private static LocalDateTime toDt(Object v) {
        if (v == null) return null;
        if (v instanceof LocalDateTime d) return d;
        if (v instanceof java.sql.Timestamp t) return t.toLocalDateTime();
        return null;
    }

    private String sp() {
        String sp = TenantContext.get();
        if (sp == null || sp.isBlank()) {
            throw new IllegalStateException("Header X-SP-Id diperlukan");
        }
        return sp;
    }
}
