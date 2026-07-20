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
    private final VerificationService verification;
    private final UserTokenRepository tokens;

    @PersistenceContext
    private EntityManager em;

    AuthController(AppUserRepository users, SpMembershipRepository memberships,
                   PlatformAdminRepository admins, PasswordEncoder encoder, JwtService jwt,
                   VerificationService verification, UserTokenRepository tokens) {
        this.users = users;
        this.memberships = memberships;
        this.admins = admins;
        this.encoder = encoder;
        this.jwt = jwt;
        this.verification = verification;
        this.tokens = tokens;
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
    record MessageResponse(String message) {}
    record ForgotRequest(@Email @NotBlank String email) {}
    record ResetRequest(@NotBlank String token,
                        @NotBlank @Size(min = 6, message = "Kata laluan minimum 6 aksara") String password) {}
    record ResendRequest(@Email @NotBlank String email) {}
    record RegisterResponse(String message, String email) {}

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
        users.flush();

        verification.sendVerification(user);

        // Sengaja TIDAK pulangkan token — e-mel mesti disahkan dahulu.
        return ResponseEntity.ok(new RegisterResponse(
                "Pendaftaran berjaya. Sila semak e-mel anda untuk pautan pengesahan.", email));
    }

    @PostMapping("/verify")
    @Transactional
    ResponseEntity<?> verify(@RequestBody java.util.Map<String, String> body) {
        String token = body.get("token");
        if (token == null || token.isBlank()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("Token diperlukan."));
        }

        var maybe = tokens.findByToken(token);
        if (maybe.isEmpty() || maybe.get().getType() != UserToken.Type.VERIFY_EMAIL) {
            return ResponseEntity.badRequest().body(new ErrorResponse("Pautan tidak sah."));
        }
        UserToken t = maybe.get();
        if (!t.isUsable()) {
            return ResponseEntity.badRequest().body(new ErrorResponse(
                    "Pautan sudah digunakan atau telah luput. Sila minta pautan baharu."));
        }

        AppUser user = users.findById(t.getUserId()).orElse(null);
        if (user == null) {
            return ResponseEntity.badRequest().body(new ErrorResponse("Pengguna tidak dijumpai."));
        }

        t.markUsed();
        user.markEmailVerified();
        users.save(user);

        // Auto-link: padan jemputan PENDING untuk email ini -> pautkan akaun
        String uemail = user.getEmail() == null ? "" : user.getEmail().trim().toLowerCase();
        if (!uemail.isEmpty()) {
            List<Object[]> pend = em.createNativeQuery(
                    "SELECT id, account_id FROM account_invitation WHERE LOWER(email) = :e AND status = 'PENDING'")
                    .setParameter("e", uemail).getResultList();
            for (Object[] row : pend) {
                Long invId = ((Number) row[0]).longValue();
                Long accId = ((Number) row[1]).longValue();
                em.createNativeQuery(
                        "UPDATE account SET payer_user_id = :uid, link_date = NOW() WHERE id = :aid")
                        .setParameter("uid", user.getId()).setParameter("aid", accId).executeUpdate();
                em.createNativeQuery(
                        "UPDATE account_invitation SET status = 'ACCEPTED', accepted_at = NOW() WHERE id = :iid")
                        .setParameter("iid", invId).executeUpdate();
            }
        }

        verification.sendWelcome(user);

        return ResponseEntity.ok(buildLogin(user));
    }

    @PostMapping("/resend-verification")
    @Transactional
    ResponseEntity<?> resendVerification(@Valid @RequestBody ResendRequest r) {
        users.findByEmailIgnoreCase(r.email().toLowerCase().trim())
                .filter(u -> !u.isEmailVerified())
                .ifPresent(verification::sendVerification);
        // Sentiasa balasan sama — jangan dedahkan e-mel wujud atau tidak
        return ResponseEntity.ok(new MessageResponse(
                "Jika e-mel tersebut berdaftar dan belum disahkan, pautan baharu telah dihantar."));
    }

    @PostMapping("/forgot-password")
    @Transactional
    ResponseEntity<?> forgotPassword(@Valid @RequestBody ForgotRequest r) {
        users.findByEmailIgnoreCase(r.email().toLowerCase().trim())
                .filter(u -> u.getStatus() == AppUser.Status.ACTIVE)
                .ifPresent(verification::sendPasswordReset);
        // Sentiasa balasan sama — elak pengintipan e-mel
        return ResponseEntity.ok(new MessageResponse(
                "Jika e-mel tersebut berdaftar, pautan reset telah dihantar."));
    }

    @PostMapping("/reset-password")
    @Transactional
    ResponseEntity<?> resetPassword(@Valid @RequestBody ResetRequest r) {
        var maybe = tokens.findByToken(r.token());
        if (maybe.isEmpty() || maybe.get().getType() != UserToken.Type.RESET_PASSWORD) {
            return ResponseEntity.badRequest().body(new ErrorResponse("Pautan tidak sah."));
        }
        UserToken t = maybe.get();
        if (!t.isUsable()) {
            return ResponseEntity.badRequest().body(new ErrorResponse(
                    "Pautan sudah digunakan atau telah luput. Sila minta pautan baharu."));
        }

        AppUser user = users.findById(t.getUserId()).orElse(null);
        if (user == null) {
            return ResponseEntity.badRequest().body(new ErrorResponse("Pengguna tidak dijumpai."));
        }

        t.markUsed();
        user.setPasswordHash(encoder.encode(r.password()));
        // Reset kata laluan melalui e-mel = bukti milik e-mel → sahkan sekali
        if (!user.isEmailVerified()) user.markEmailVerified();
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

        // E-mel WAJIB disahkan sebelum masuk portal
        if (!user.isEmailVerified()) {
            return ResponseEntity.status(403).body(new ErrorResponse(
                    "E-mel anda belum disahkan. Sila semak e-mel untuk pautan pengesahan."));
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
        claims.put("sps", access.stream().map(SpAccessDto::spCode).distinct().toList());
        // Peranan per SP — "SW01:SP_ADMIN", "SW01:CLERK", …
        claims.put("spRoles", access.stream()
                .map(a -> a.spCode() + ":" + a.role()).toList());

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
        // Satu baris per (SP, peranan). Pengguna boleh ada beberapa peranan
        // dalam SP yang sama — cth Admin + Cashier.
        List<Object[]> rows = em.createNativeQuery("""
                SELECT m.sp_code, sp.name, m.role
                FROM sp_membership m
                JOIN service_provider sp ON sp.sp_code = m.sp_code
                WHERE m.user_id = :uid AND m.status = 'ACTIVE'
                ORDER BY sp.name, m.role
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
