package com.monthley.identity.internal;

import com.monthley.identity.api.UserRole;
import com.monthley.shared.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Roles Setting (Tetapan ▸ Roles) — urus ahli & peranan dalam SP semasa.
 *
 *   GET    /api/v1/settings/roles          — senarai peranan tersedia
 *   GET    /api/v1/settings/members        — ahli SP semasa
 *   POST   /api/v1/settings/members        — tambah ahli (e-mel mesti berdaftar)
 *   PATCH  /api/v1/settings/members/{id}   — tukar peranan
 *   DELETE /api/v1/settings/members/{id}   — buang ahli
 */
@RestController
@RequestMapping("/api/v1/settings")
class SpRoleController {

    @PersistenceContext
    private EntityManager em;

    record RoleDto(String code, String name, String description) {}

    record MemberDto(Long id, Long userId, String email, String fullName,
                     String role, String roleName, String status, LocalDate joinedAt) {}

    record AddMemberRequest(@Email @NotBlank String email, @NotBlank String role) {}
    record ChangeRoleRequest(@NotBlank String role) {}
    record MessageResponse(String message) {}

    // ---------- peranan tersedia ----------

    @GetMapping("/roles")
    @SuppressWarnings("unchecked")
    List<RoleDto> roles() {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT code, name, description FROM sp_role
                WHERE status = 'ACTIVE' ORDER BY sort_order
                """).getResultList();
        List<RoleDto> out = new ArrayList<>();
        for (Object[] r : rows) {
            out.add(new RoleDto((String) r[0], (String) r[1], (String) r[2]));
        }
        return out;
    }

    // ---------- ahli SP semasa ----------

    @GetMapping("/members")
    @SuppressWarnings("unchecked")
    List<MemberDto> members() {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT m.id, u.id, u.email, u.full_name, m.role,
                       COALESCE(r.name, m.role), m.status, m.created_at
                FROM sp_membership m
                JOIN app_user u  ON u.id = m.user_id
                LEFT JOIN sp_role r ON r.code = m.role
                WHERE m.sp_code = :sp
                ORDER BY m.role, u.full_name
                """).setParameter("sp", sp()).getResultList();

        List<MemberDto> out = new ArrayList<>();
        for (Object[] r : rows) {
            out.add(new MemberDto(
                    ((Number) r[0]).longValue(), ((Number) r[1]).longValue(),
                    (String) r[2], (String) r[3], (String) r[4], (String) r[5],
                    (String) r[6], toLocalDate(r[7])));
        }
        return out;
    }

    @PostMapping("/members")
    @Transactional
    ResponseEntity<?> addMember(@Valid @RequestBody AddMemberRequest r) {
        String email = r.email().toLowerCase().trim();

        // Syarat sama seperti onboarding: mesti sudah berdaftar
        Object[] user;
        try {
            user = (Object[]) em.createNativeQuery(
                            "SELECT id, full_name FROM app_user WHERE LOWER(email) = :e AND status = 'ACTIVE'")
                    .setParameter("e", email).getSingleResult();
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(new MessageResponse(
                    "E-mel '" + email + "' belum berdaftar dengan Monthley. "
                    + "Minta mereka daftar akaun terlebih dahulu."));
        }
        Long userId = ((Number) user[0]).longValue();

        // Pendua hanya jika PERANAN SAMA — satu pengguna boleh ada
        // beberapa peranan (cth: Admin + Cashier) melalui baris berasingan.
        Number exists = (Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM sp_membership "
                        + "WHERE sp_code = :sp AND user_id = :uid AND role = :role")
                .setParameter("sp", sp()).setParameter("uid", userId)
                .setParameter("role", r.role()).getSingleResult();
        if (exists.intValue() > 0) {
            return ResponseEntity.badRequest().body(new MessageResponse(
                    "Pengguna ini sudah mempunyai peranan tersebut dalam SP ini."));
        }

        UserRole.valueOf(r.role());   // sahkan kod peranan

        em.createNativeQuery("""
                INSERT INTO sp_membership (sp_code, user_id, role, status, created_at, updated_at, version)
                VALUES (:sp, :uid, :role, 'ACTIVE', NOW(), NOW(), 0)
                """)
                .setParameter("sp", sp())
                .setParameter("uid", userId)
                .setParameter("role", r.role())
                .executeUpdate();

        return ResponseEntity.ok(new MessageResponse(
                user[1] + " ditambah sebagai " + r.role() + "."));
    }

    @PatchMapping("/members/{id}")
    @Transactional
    ResponseEntity<?> changeRole(@PathVariable Long id, @Valid @RequestBody ChangeRoleRequest r) {
        UserRole.valueOf(r.role());

        if (!"SP_ADMIN".equals(r.role()) && isLastAdmin(id)) {
            return ResponseEntity.badRequest().body(new MessageResponse(
                    "Tidak boleh tukar — ini admin terakhir SP. Lantik admin lain dahulu."));
        }

        // Elak berlanggar dengan peranan sedia ada bagi pengguna sama
        Number clash = (Number) em.createNativeQuery("""
                SELECT COUNT(*) FROM sp_membership
                WHERE sp_code = :sp AND role = :role
                  AND user_id = (SELECT user_id FROM (
                        SELECT user_id FROM sp_membership WHERE id = :id) t)
                  AND id <> :id
                """)
                .setParameter("sp", sp()).setParameter("role", r.role())
                .setParameter("id", id).getSingleResult();
        if (clash.intValue() > 0) {
            return ResponseEntity.badRequest().body(new MessageResponse(
                    "Pengguna ini sudah mempunyai peranan tersebut. Buang baris ini sahaja."));
        }

        em.createNativeQuery(
                        "UPDATE sp_membership SET role = :role, updated_at = NOW() "
                        + "WHERE id = :id AND sp_code = :sp")
                .setParameter("role", r.role()).setParameter("id", id)
                .setParameter("sp", sp()).executeUpdate();

        return ResponseEntity.ok(new MessageResponse("Peranan dikemas kini."));
    }

    @DeleteMapping("/members/{id}")
    @Transactional
    ResponseEntity<?> removeMember(@PathVariable Long id) {
        if (isLastAdmin(id)) {
            return ResponseEntity.badRequest().body(new MessageResponse(
                    "Tidak boleh buang — ini admin terakhir SP."));
        }
        em.createNativeQuery("DELETE FROM sp_membership WHERE id = :id AND sp_code = :sp")
                .setParameter("id", id).setParameter("sp", sp()).executeUpdate();
        return ResponseEntity.ok(new MessageResponse("Ahli dibuang."));
    }

    // ---------- helper ----------

    /** Elak SP jadi yatim tanpa admin. */
    private boolean isLastAdmin(Long membershipId) {
        Number isAdmin = (Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM sp_membership "
                        + "WHERE id = :id AND sp_code = :sp AND role = 'SP_ADMIN'")
                .setParameter("id", membershipId).setParameter("sp", sp()).getSingleResult();
        if (isAdmin.intValue() == 0) return false;

        Number admins = (Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM sp_membership "
                        + "WHERE sp_code = :sp AND role = 'SP_ADMIN' AND status = 'ACTIVE'")
                .setParameter("sp", sp()).getSingleResult();
        return admins.intValue() <= 1;
    }

    private String sp() {
        String sp = TenantContext.get();
        if (sp == null || sp.isBlank()) {
            throw new IllegalStateException("Header X-SP-Id diperlukan");
        }
        return sp;
    }

    private static LocalDate toLocalDate(Object o) {
        if (o == null) return null;
        if (o instanceof LocalDate d) return d;
        if (o instanceof java.time.LocalDateTime dt) return dt.toLocalDate();
        if (o instanceof java.sql.Timestamp ts) return ts.toLocalDateTime().toLocalDate();
        if (o instanceof java.sql.Date d) return d.toLocalDate();
        return null;
    }
}
