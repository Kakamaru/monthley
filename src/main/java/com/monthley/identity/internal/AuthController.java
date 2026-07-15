package com.monthley.identity.internal;

import com.monthley.identity.api.CurrentUser;
import com.monthley.identity.api.UserRole;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Auth — rujuk handoff §5.
 *   POST /api/v1/auth/register  — daftar pengguna baharu (peranan lalai: CUSTOMER)
 *   POST /api/v1/auth/login     — ID + kata laluan → JWT + role + senarai SP
 *   GET  /api/v1/auth/me        — profil + akses SP
 *
 * Satu login untuk semua: sistem kenal pasti sama ada pengguna
 * ialah superadmin, admin SP, atau pelanggan — dari data, bukan borang.
 */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

    private final AppUserRepository users;
    private final SpMembershipRepository memberships;
    private final PlatformAdminRepository admins;
    private final PasswordEncoder encoder;
    private final JwtService jwt;

    @PersistenceContext
    private EntityManager em;

    AuthController(AppUserRepository users, SpMembershipRepository memberships,
                   PlatformAdminRepository admins, PasswordEncoder encoder, JwtService jwt) {
        this.users = users;
        this.memberships = memberships;
        this.admins = admins;
        this.encoder = encoder;
        this.jwt = jwt;
    }

    // ---------- DTO ----------

    record RegisterRequest(
            @NotBlank String fullName,
            @Email @NotBlank String email,
            String mobile,
            @NotBlank @Size(min = 6, message = "Kata laluan minimum 6 aksara") String password) {}

    record LoginRequest(@NotBlank String id, @NotBlank String password) {}

    record SpAccessDto(String spCode, String spName, String role) {}

    record LoginResponse(
            String token,
            Long userId,
            String email,
            String fullName,
            boolean superadmin,
            List<SpAccessDto> spAccess,
            boolean hasLinkedAccounts) {}

    record ErrorResponse(String message) {}

    // ---------- Endpoints ----------

    @PostMapping("/register")
    @Transactional
    ResponseEntity<?> register(@Valid @RequestBody RegisterRequest r) {
        String email = r.email().toLowerCase().trim();

        if (users.existsByEmailIgnoreCase(email)) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("E-mel ini sudah didaftarkan."));
        }

        AppUser user = new AppUser(email, r.fullName(), r.mobile(), encoder.encode(r.password()));
        users.save(user);

        return ResponseEntity.ok(buildLogin(user));
    }

    @PostMapping("/login")
    @Transactional(readOnly = true)
    ResponseEntity<?> login(@Valid @RequestBody LoginRequest r) {
        String id = r.id().toLowerCase().trim();

        // 1. Cuba platform admin dahulu
        Optional<PlatformAdmin> admin = admins.findByEmailIgnoreCase(id);
        if (admin.isPresent()) {
            PlatformAdmin a = admin.get();
            if (a.getStatus() != PlatformAdmin.Status.ACTIVE
                    || !encoder.matches(r.password(), a.getPasswordHash())) {
                return unauthorized();
            }
            Map<String, Object> claims = new HashMap<>();
            claims.put("email", a.getEmail());
            claims.put("name", a.getFullName());
            claims.put("superadmin", a.getRole() == PlatformAdmin.Role.SUPERADMIN);
            String token = jwt.generate("admin:" + a.getId(), claims);

            return ResponseEntity.ok(new LoginResponse(
                    token, a.getId(), a.getEmail(), a.getFullName(),
                    a.getRole() == PlatformAdmin.Role.SUPERADMIN, List.of(), false));
        }

        // 2. Pengguna biasa
        Optional<AppUser> maybe = users.findByEmailIgnoreCase(id);
        if (maybe.isEmpty()) return unauthorized();

        AppUser user = maybe.get();
        if (user.getStatus() != AppUser.Status.ACTIVE
                || user.getPasswordHash() == null
                || !encoder.matches(r.password(), user.getPasswordHash())) {
            return unauthorized();
        }

        return ResponseEntity.ok(buildLogin(user));
    }

    @GetMapping("/me")
    @Transactional(readOnly = true)
    ResponseEntity<?> me(@RequestHeader(value = "Authorization", required = false) String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) return unauthorized();
        try {
            var claims = jwt.parse(auth.substring(7));
            String sub = claims.getSubject();
            if (sub.startsWith("admin:")) {
                Long id = Long.valueOf(sub.substring(6));
                return admins.findById(id)
                        .<ResponseEntity<?>>map(a -> ResponseEntity.ok(new CurrentUser(
                                a.getId(), a.getEmail(), a.getFullName(),
                                a.getRole() == PlatformAdmin.Role.SUPERADMIN, List.of())))
                        .orElseGet(this::unauthorized);
            }
            Long id = Long.valueOf(sub);
            return users.findById(id)
                    .<ResponseEntity<?>>map(u -> ResponseEntity.ok(toCurrentUser(u)))
                    .orElseGet(this::unauthorized);
        } catch (Exception e) {
            return unauthorized();
        }
    }

    // ---------- helper ----------

    private LoginResponse buildLogin(AppUser user) {
        List<SpAccessDto> access = spAccessOf(user.getId());
        Map<String, Object> claims = new HashMap<>();
        claims.put("email", user.getEmail());
        claims.put("name", user.getFullName());
        claims.put("superadmin", false);
        claims.put("sps", access.stream().map(SpAccessDto::spCode).toList());

        String token = jwt.generate(String.valueOf(user.getId()), claims);

        return new LoginResponse(token, user.getId(), user.getEmail(), user.getFullName(),
                false, access, hasLinkedAccounts(user.getId()));
    }

    private CurrentUser toCurrentUser(AppUser u) {
        List<CurrentUser.SpAccess> access = spAccessOf(u.getId()).stream()
                .map(a -> new CurrentUser.SpAccess(a.spCode(), a.spName(), UserRole.valueOf(a.role())))
                .toList();
        return new CurrentUser(u.getId(), u.getEmail(), u.getFullName(), false, access);
    }

    @SuppressWarnings("unchecked")
    private List<SpAccessDto> spAccessOf(Long userId) {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT m.sp_code, sp.name, m.role
                FROM sp_membership m
                JOIN service_provider sp ON sp.sp_code = m.sp_code
                WHERE m.user_id = :uid AND m.status = 'ACTIVE'
                ORDER BY sp.name
                """).setParameter("uid", userId).getResultList();

        List<SpAccessDto> out = new ArrayList<>();
        for (Object[] r : rows) {
            out.add(new SpAccessDto((String) r[0], (String) r[1], (String) r[2]));
        }
        return out;
    }

    /** Ada akaun pelanggan dipaut? Kalau tiada, dashboard pelanggan kosong. */
    private boolean hasLinkedAccounts(Long userId) {
        Number n = (Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM account WHERE payer_user_id = :uid")
                .setParameter("uid", userId).getSingleResult();
        return n.longValue() > 0;
    }

    private ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(401).body(new ErrorResponse("ID atau kata laluan tidak sah."));
    }
}
