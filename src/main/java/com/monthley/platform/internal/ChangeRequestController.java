package com.monthley.platform.internal;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Peti masuk permohonan — superadmin sahaja.
 *
 * SATU skrin untuk semua jenis perubahan: tambah modul, henti modul, dan
 * tukar pelan. Jadual berasingan bagi setiap jenis bermakna tiga peti
 * masuk yang melakukan kerja sama (ADR 0016).
 *
 * Kelulusan mencipta HAK dan BIL dalam satu transaksi melalui
 * ModuleEntitlementService — ia satu-satunya tempat hak berubah.
 */
@RestController
@RequestMapping("/api/v1/platform/change-requests")
class ChangeRequestController {

    private final ModuleEntitlementService entitlements;

    @PersistenceContext
    private EntityManager em;

    ChangeRequestController(ModuleEntitlementService entitlements) {
        this.entitlements = entitlements;
    }

    record RequestRow(Long id, String spCode, String spName, String type,
                      String moduleCode, String moduleName,
                      Long planProductId, String planName,
                      String status, String requestedByEmail,
                      LocalDateTime requestedAt, LocalDateTime decidedAt,
                      String decisionNote) {}

    record DecisionRequest(String note) {}

    @GetMapping
    @SuppressWarnings("unchecked")
    List<RequestRow> list(@RequestParam(required = false) String status) {
        String tapis = (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status))
                ? null : status.toUpperCase();

        List<Object[]> rows = em.createNativeQuery("""
                SELECT r.id, r.sp_code, sp.name, r.request_type,
                       r.module_code, m.name,
                       r.plan_product_id, p.name,
                       r.status, u.email,
                       r.requested_at, r.decided_at, r.decision_note
                FROM   sp_change_request r
                JOIN   service_provider sp ON sp.sp_code = r.sp_code
                LEFT   JOIN ref_module m ON m.code = r.module_code
                LEFT   JOIN product p ON p.id = r.plan_product_id
                LEFT   JOIN app_user u ON u.id = r.requested_by
                WHERE (:st IS NULL OR r.status = :st)
                ORDER  BY r.status = 'PENDING' DESC, r.requested_at DESC
                """).setParameter("st", tapis).getResultList();

        List<RequestRow> out = new ArrayList<>();
        for (Object[] r : rows) {
            out.add(new RequestRow(
                    ((Number) r[0]).longValue(), (String) r[1], (String) r[2],
                    (String) r[3], (String) r[4], (String) r[5],
                    r[6] == null ? null : ((Number) r[6]).longValue(),
                    (String) r[7], (String) r[8], (String) r[9],
                    toDt(r[10]), toDt(r[11]), (String) r[12]));
        }
        return out;
    }

    @GetMapping("/pending-count")
    Map<String, Integer> pendingCount() {
        Number n = (Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM sp_change_request WHERE status = 'PENDING'")
                .getSingleResult();
        return Map.of("count", n.intValue());
    }

    /**
     * Luluskan — hak diberi serta-merta, bil bermula 1hb bulan berikutnya.
     *
     * Permohonan dikunci sebelum diproses: dua superadmin yang menekan
     * Lulus serentak akan memberikan modul dua kali, dan sp_module
     * mempunyai kekangan unik yang akan menolak yang kedua — tetapi
     * selepas langganan pertama sudah dicipta.
     */
    @PostMapping("/{id}/approve")
    @Transactional
    ResponseEntity<?> approve(@PathVariable Long id, @RequestBody(required = false) DecisionRequest r) {
        Object[] req = ambilDanKunci(id);
        String sp = (String) req[0];
        String jenis = (String) req[1];
        String modul = (String) req[2];
        Long planProduct = req[3] == null ? null : ((Number) req[3]).longValue();

        Long oleh = superadminId();

        switch (jenis) {
            case "MODULE_ADD"  -> entitlements.grant(sp, modul, oleh);
            case "MODULE_END"  -> entitlements.revoke(sp, modul, oleh);
            case "PLAN_CHANGE" -> entitlements.changePlan(sp, planProduct, oleh);
            default -> throw new IllegalStateException("Jenis tidak dikenali: " + jenis);
        }

        tandakan(id, "APPROVED", r == null ? null : r.note(), oleh);
        return ResponseEntity.ok(Map.of("message", "Permohonan diluluskan."));
    }

    /**
     * Tolak — sebab WAJIB.
     *
     * SP nampak sebab penolakan. Tanpa itu, setiap penolakan menghasilkan
     * mesej WhatsApp bertanya kenapa (ADR 0016).
     */
    @PostMapping("/{id}/reject")
    @Transactional
    ResponseEntity<?> reject(@PathVariable Long id, @RequestBody DecisionRequest r) {
        String nota = (r == null || r.note() == null) ? null : r.note().trim();
        if (nota == null || nota.isBlank()) {
            throw new IllegalStateException("Sebab penolakan diperlukan — SP akan melihatnya.");
        }
        ambilDanKunci(id);
        tandakan(id, "REJECTED", nota, superadminId());
        return ResponseEntity.ok(Map.of("message", "Permohonan ditolak."));
    }

    // ---------- helper ----------

    private Object[] ambilDanKunci(Long id) {
        List<?> rows = em.createNativeQuery("""
                SELECT sp_code, request_type, module_code, plan_product_id, status
                FROM   sp_change_request WHERE id = :id FOR UPDATE
                """).setParameter("id", id).getResultList();

        if (rows.isEmpty()) {
            throw new IllegalStateException("Permohonan tidak wujud: " + id);
        }
        Object[] r = (Object[]) rows.get(0);
        if (!"PENDING".equals(r[4])) {
            throw new IllegalStateException(
                    "Permohonan ini sudah diputuskan (" + r[4] + ").");
        }
        return r;
    }

    private void tandakan(Long id, String status, String nota, Long oleh) {
        em.createNativeQuery("""
                UPDATE sp_change_request
                SET    status = :st, decision_note = :nota, decided_by = :by,
                       decided_at = NOW(), updated_at = NOW()
                WHERE  id = :id
                """)
                .setParameter("st", status).setParameter("nota", nota)
                .setParameter("by", oleh).setParameter("id", id)
                .executeUpdate();
    }

    /** Principal superadmin ialah "admin:<id>" (lihat AuthController.login). */
    private Long superadminId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        String name = (auth == null) ? null : auth.getName();
        if (name != null && name.startsWith("admin:")) {
            try { return Long.valueOf(name.substring(6)); }
            catch (NumberFormatException ignored) { /* jatuh ke bawah */ }
        }
        return null;
    }

    private static LocalDateTime toDt(Object v) {
        if (v == null) return null;
        if (v instanceof LocalDateTime d) return d;
        if (v instanceof java.sql.Timestamp t) return t.toLocalDateTime();
        return null;
    }
}
