package com.monthley.expenses.internal;

import com.monthley.shared.Access;
import com.monthley.shared.ModuleGuard;
import com.monthley.shared.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

import java.util.List;
import java.util.Map;

/**
 * Hak modul untuk SP semasa — dibaca oleh UI.
 *
 * UI perlukan ini untuk 'benarkan masuk, sekat transaksi' (ADR 0016):
 * menu dan skrin kekal boleh dibuka, tetapi butang tulis dikunci dan
 * sebabnya dinyatakan. Tanpa endpoint ini, UI tiada cara mengetahui hak
 * dan setiap percubaan tulis berakhir dengan 403 yang mengejutkan.
 *
 * Ia BUKAN penguatkuasaan. ModuleGuard di backend yang menguatkuasakan;
 * ini hanya memberitahu UI apa yang perlu dipaparkan.
 */
@RestController
@RequestMapping("/api/v1/modules")
class ExpModuleController {

    private final ModuleGuard modules;

    @PersistenceContext
    private EntityManager em;

    ExpModuleController(ModuleGuard modules) {
        this.modules = modules;
    }

    record ModuleStatus(String code, String name, boolean active,
                        String description, String videoUrl) {}

    /**
     * Modul yang BERKENAAN untuk sektor SP, dengan status langganannya.
     *
     * Dua penapis berbeza, dan penting bezakan:
     *
     *   SEKTOR     — modul ini masuk akal untuk jenis SP ini?
     *                Menapis KATALOG. JMB tidak pernah melihat modul
     *                sekolah, walaupun sebagai tawaran.
     *
     *   LANGGANAN  — SP ini sudah membayar untuknya?
     *                Menapis AKSES. JMB melihat Aduan sebagai tawaran,
     *                tetapi tidak boleh menggunakannya sehingga melanggan.
     *
     * Modul yang tidak dilanggan TETAP dipulangkan — itulah yang
     * membolehkan UI memaparkannya sebagai tawaran (ADR 0016). Yang
     * ditapis di sini ialah modul yang mengarut untuk sektor itu.
     *
     * business_types kosong bermakna SEMUA sektor. SP tanpa
     * business_type juga melihat semuanya: menyembunyikan segalanya
     * daripada SP yang tidak lengkap profilnya lebih teruk daripada
     * menunjukkan satu modul yang tidak berkenaan.
     */
    @GetMapping
    @SuppressWarnings("unchecked")
    List<ModuleStatus> list() {
        Access.requireAnyRole("melihat modul", "SP_ADMIN", "CLERK", "VIEWER");
        String sp = sp();

        List<Object[]> rows = em.createNativeQuery("""
                SELECT m.code, m.name, m.description, m.video_url,
                       EXISTS (SELECT 1 FROM sp_module s
                               WHERE s.sp_code = :sp AND s.module_code = m.code
                                 AND s.status = 'ACTIVE'
                                 AND s.start_date <= CURDATE()
                                 AND (s.end_date IS NULL OR s.end_date >= CURDATE())) AS aktif
                FROM   ref_module m
                JOIN   service_provider sp ON sp.sp_code = :sp
                WHERE  m.status = 'ACTIVE'
                  AND (m.business_types IS NULL OR m.business_types = ''
                       OR sp.business_type IS NULL
                       OR FIND_IN_SET(sp.business_type, m.business_types) > 0)
                ORDER  BY m.sort_order
                """).setParameter("sp", sp).getResultList();

        List<ModuleStatus> out = new java.util.ArrayList<>();
        for (Object[] r : rows) {
            out.add(new ModuleStatus((String) r[0], (String) r[1],
                    bool(r[4]), (String) r[2], (String) r[3]));
        }
        return out;
    }

    /** Semakan pantas untuk satu modul. */
    @GetMapping("/{code}")
    Map<String, Boolean> has(@org.springframework.web.bind.annotation.PathVariable String code) {
        Access.requireAnyRole("melihat modul", "SP_ADMIN", "CLERK", "VIEWER");
        return Map.of("active", modules.has(code));
    }

    // ---------- Permohonan ----------

    record RequestRow(Long id, String type, String moduleCode, String moduleName,
                      Long planProductId, String planName, String status,
                      LocalDateTime requestedAt, LocalDateTime decidedAt,
                      String decisionNote) {}

    record NewRequest(String type, String moduleCode, Long planProductId) {}

    /**
     * SP memohon perubahan — tambah modul, henti modul, atau tukar pelan.
     *
     * SP_ADMIN sahaja: ia komitmen kewangan (ADR 0016). CLERK boleh
     * merekod bayaran tetapi tidak boleh menambah kos bulanan.
     */
    @PostMapping("/request")
    @Transactional
    ResponseEntity<?> request(@RequestBody NewRequest r) {
        Access.requireRole("SP_ADMIN", "memohon perubahan modul atau pelan");
        String sp = sp();

        String jenis = r.type() == null ? "" : r.type().trim().toUpperCase();
        if (!List.of("MODULE_ADD", "MODULE_END", "PLAN_CHANGE").contains(jenis)) {
            throw new IllegalStateException("Jenis permohonan tidak sah: " + jenis);
        }

        // Satu permohonan MENUNGGU sahaja per perkara. Tanpa semakan ini,
        // SP yang menekan butang dua kali menghasilkan dua permohonan, dan
        // superadmin meluluskan kedua-duanya — modul diberi dua kali.
        Number menunggu = (Number) em.createNativeQuery("""
                SELECT COUNT(*) FROM sp_change_request
                WHERE  sp_code = :sp AND status = 'PENDING'
                  AND  request_type = :t
                  AND (module_code <=> :m)
                """).setParameter("sp", sp).setParameter("t", jenis)
                .setParameter("m", r.moduleCode())
                .getSingleResult();
        if (menunggu.intValue() > 0) {
            throw new IllegalStateException(
                    "Permohonan serupa sedang menunggu kelulusan.");
        }

        if ("MODULE_ADD".equals(jenis) || "MODULE_END".equals(jenis)) {
            if (r.moduleCode() == null || r.moduleCode().isBlank()) {
                throw new IllegalStateException("Kod modul diperlukan.");
            }
            boolean ada = modules.has(r.moduleCode());
            if ("MODULE_ADD".equals(jenis) && ada) {
                throw new IllegalStateException("Modul ini sudah aktif.");
            }
            if ("MODULE_END".equals(jenis) && !ada) {
                throw new IllegalStateException("Modul ini tidak aktif.");
            }
        } else if (r.planProductId() == null) {
            throw new IllegalStateException("Pelan diperlukan.");
        }

        em.createNativeQuery("""
                INSERT INTO sp_change_request
                  (sp_code, request_type, module_code, plan_product_id, status,
                   requested_by, requested_at, created_at, updated_at, version)
                VALUES (:sp, :t, :m, :plan, 'PENDING', :by, NOW(), NOW(), NOW(), 0)
                """)
                .setParameter("sp", sp).setParameter("t", jenis)
                .setParameter("m", r.moduleCode())
                .setParameter("plan", r.planProductId())
                .setParameter("by", currentUserId())
                .executeUpdate();

        return ResponseEntity.ok(Map.of("message",
                "Permohonan dihantar. Superadmin akan menilai dan memaklumkan keputusan."));
    }

    /** Permohonan SP semasa — status dan sebab penolakan. */
    @GetMapping("/requests")
    @SuppressWarnings("unchecked")
    List<RequestRow> requests() {
        Access.requireAnyRole("melihat permohonan", "SP_ADMIN", "CLERK");

        List<Object[]> rows = em.createNativeQuery("""
                SELECT r.id, r.request_type, r.module_code, m.name,
                       r.plan_product_id, p.name, r.status,
                       r.requested_at, r.decided_at, r.decision_note
                FROM   sp_change_request r
                LEFT   JOIN ref_module m ON m.code = r.module_code
                LEFT   JOIN product p ON p.id = r.plan_product_id
                WHERE  r.sp_code = :sp
                ORDER  BY r.requested_at DESC
                """).setParameter("sp", sp()).getResultList();

        List<RequestRow> out = new java.util.ArrayList<>();
        for (Object[] r : rows) {
            out.add(new RequestRow(
                    ((Number) r[0]).longValue(), (String) r[1], (String) r[2],
                    (String) r[3],
                    r[4] == null ? null : ((Number) r[4]).longValue(),
                    (String) r[5], (String) r[6],
                    toDt(r[7]), toDt(r[8]), (String) r[9]));
        }
        return out;
    }

    private static LocalDateTime toDt(Object v) {
        if (v == null) return null;
        if (v instanceof LocalDateTime d) return d;
        if (v instanceof java.sql.Timestamp t) return t.toLocalDateTime();
        return null;
    }

    private Long currentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new IllegalStateException("Tiada pengguna dalam konteks.");
        }
        return Long.valueOf(auth.getName());
    }

    private static boolean bool(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        return ((Number) v).intValue() != 0;
    }

    private String sp() {
        String sp = TenantContext.get();
        if (sp == null || sp.isBlank()) {
            throw new IllegalStateException("Header X-SP-Id diperlukan");
        }
        return sp;
    }
}
