package com.monthley.platform.internal;

import com.monthley.shared.PageResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Pengurusan pengguna Monthley — superadmin sahaja.
 *   GET   /api/v1/platform/users?q=&status=&role=&page=&size=
 *   PATCH /api/v1/platform/users/{id}/password   — tukar kata laluan
 *   PATCH /api/v1/platform/users/{id}/status     — aktif / nyahaktif
 *   POST  /api/v1/platform/users/generate-password
 */
@RestController
@RequestMapping("/api/v1/platform/users")
class PlatformUserController {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String PWD_CHARS = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final PasswordEncoder encoder;

    @PersistenceContext
    private EntityManager em;

    PlatformUserController(PasswordEncoder encoder) {
        this.encoder = encoder;
    }

    // ---------- DTO ----------

    record UserRow(
            Long id, String email, String fullName, String mobile, String status,
            long spCount, String spNames, long accountCount, LocalDate createdAt) {}

    record ChangePasswordRequest(
            @NotBlank @Size(min = 6, message = "Kata laluan minimum 6 aksara") String password) {}

    record GeneratedPassword(String password) {}
    record MessageResponse(String message) {}

    // ---------- Endpoints ----------

    @GetMapping
    @SuppressWarnings("unchecked")
    PageResponse<UserRow> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String role,   // SP_ADMIN | CUSTOMER | NONE
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        String search = (q == null || q.isBlank()) ? null : "%" + q.trim().toLowerCase() + "%";

        String where = """
            WHERE (:q IS NULL OR LOWER(u.email) LIKE :q OR LOWER(u.full_name) LIKE :q
                   OR LOWER(COALESCE(u.mobile,'')) LIKE :q)
              AND (:status IS NULL OR u.status = :status)
              AND (:role IS NULL
                   OR (:role = 'SP_ADMIN' AND EXISTS (
                        SELECT 1 FROM sp_membership m
                        WHERE m.user_id = u.id AND m.status = 'ACTIVE'))
                   OR (:role = 'CUSTOMER' AND EXISTS (
                        SELECT 1 FROM account a WHERE a.payer_user_id = u.id))
                   OR (:role = 'NONE' AND NOT EXISTS (
                        SELECT 1 FROM sp_membership m WHERE m.user_id = u.id AND m.status = 'ACTIVE')
                       AND NOT EXISTS (
                        SELECT 1 FROM account a WHERE a.payer_user_id = u.id)))
            """;

        var countQ = em.createNativeQuery("SELECT COUNT(*) FROM app_user u " + where);
        bind(countQ, search, status, role);
        long total = ((Number) countQ.getSingleResult()).longValue();

        String sql = """
            SELECT u.id, u.email, u.full_name, u.mobile, u.status,
                   COALESCE((SELECT COUNT(*) FROM sp_membership m
                             WHERE m.user_id = u.id AND m.status = 'ACTIVE'), 0) AS sp_count,
                   (SELECT GROUP_CONCAT(sp.name SEPARATOR ', ')
                    FROM sp_membership m JOIN service_provider sp ON sp.sp_code = m.sp_code
                    WHERE m.user_id = u.id AND m.status = 'ACTIVE') AS sp_names,
                   COALESCE((SELECT COUNT(*) FROM account a
                             WHERE a.payer_user_id = u.id), 0) AS acc_count,
                   u.created_at
            FROM app_user u
            """ + where + " ORDER BY u.id DESC LIMIT :lim OFFSET :off";

        var dataQ = em.createNativeQuery(sql);
        bind(dataQ, search, status, role);
        dataQ.setParameter("lim", size);
        dataQ.setParameter("off", page * size);

        List<Object[]> rows = dataQ.getResultList();
        List<UserRow> items = new ArrayList<>();
        for (Object[] r : rows) {
            items.add(new UserRow(
                    ((Number) r[0]).longValue(), (String) r[1], (String) r[2], (String) r[3],
                    (String) r[4], ((Number) r[5]).longValue(), (String) r[6],
                    ((Number) r[7]).longValue(), toLocalDate(r[8])));
        }
        return new PageResponse<>(items, total, page, size);
    }

    @PostMapping("/generate-password")
    GeneratedPassword generatePassword() {
        StringBuilder sb = new StringBuilder(10);
        for (int i = 0; i < 10; i++) {
            sb.append(PWD_CHARS.charAt(RANDOM.nextInt(PWD_CHARS.length())));
        }
        return new GeneratedPassword(sb.toString());
    }

    @PatchMapping("/{id}/password")
    @Transactional
    ResponseEntity<?> changePassword(@PathVariable Long id,
                                     @Valid @RequestBody ChangePasswordRequest r) {
        int n = em.createNativeQuery(
                        "UPDATE app_user SET password_hash = :h, updated_at = NOW() WHERE id = :id")
                .setParameter("h", encoder.encode(r.password()))
                .setParameter("id", id)
                .executeUpdate();
        if (n == 0) {
            return ResponseEntity.badRequest().body(new MessageResponse("Pengguna tidak dijumpai."));
        }
        return ResponseEntity.ok(new MessageResponse("Kata laluan berjaya ditukar."));
    }

    @PatchMapping("/{id}/status")
    @Transactional
    ResponseEntity<?> changeStatus(@PathVariable Long id,
                                   @RequestBody java.util.Map<String, String> body) {
        String s = body.get("status");
        if (!"ACTIVE".equals(s) && !"INACTIVE".equals(s)) {
            return ResponseEntity.badRequest().body(new MessageResponse("Status tidak sah."));
        }
        em.createNativeQuery("UPDATE app_user SET status = :s, updated_at = NOW() WHERE id = :id")
                .setParameter("s", s).setParameter("id", id).executeUpdate();
        return ResponseEntity.ok(new MessageResponse("Status dikemas kini."));
    }

    // ---------- helper ----------

    private void bind(jakarta.persistence.Query query, String q, String status, String role) {
        query.setParameter("q", q);
        query.setParameter("status", (status == null || status.isBlank()) ? null : status);
        query.setParameter("role", (role == null || role.isBlank()) ? null : role);
    }

    /** Driver MySQL 9 pulangkan java.time.* — terima semua jenis. */
    private static LocalDate toLocalDate(Object o) {
        if (o == null) return null;
        if (o instanceof LocalDate d) return d;
        if (o instanceof java.time.LocalDateTime dt) return dt.toLocalDate();
        if (o instanceof java.sql.Timestamp ts) return ts.toLocalDateTime().toLocalDate();
        if (o instanceof java.sql.Date d) return d.toLocalDate();
        return null;
    }
}
