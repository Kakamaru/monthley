package com.monthley.gateway.internal;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Tetapan gerbang per SP — superadmin sahaja.
 *
 * Laluan /api/v1/platform/** sudah disekat kepada SUPERADMIN dalam
 * SecurityConfig.
 *
 * Kunci rahsia TIDAK PERNAH dipulangkan. GET memberitahu sama ada kunci
 * WUJUD, bukan apa nilainya — skrin yang memaparkan kunci bermakna sesiapa
 * yang melihat skrin itu boleh menyalinnya.
 */
@RestController
@RequestMapping("/api/v1/platform/gateway")
class GatewaySettingController {

    private final GatewayCredentials creds;

    @PersistenceContext
    private EntityManager em;

    GatewaySettingController(GatewayCredentials creds) {
        this.creds = creds;
    }

    record SettingView(String spCode, String spName, String gateway,
                       String categoryCode, boolean sandbox,
                       boolean onlinePayment, boolean keySet) {}

    record SaveBody(String gateway, String secretKey, String categoryCode,
                    Boolean sandbox, Boolean onlinePayment) {}

    @GetMapping
    @SuppressWarnings("unchecked")
    List<SettingView> list() {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT s.sp_code, sp.name, s.gateway, s.category_code, s.sandbox,
                       s.online_payment,
                       (s.gateway_key_enc IS NOT NULL AND s.gateway_key_enc <> '')
                FROM   sp_payment_setting s
                JOIN   service_provider sp ON sp.sp_code = s.sp_code
                ORDER  BY s.sp_code
                """).getResultList();

        return rows.stream().map(r -> new SettingView(
                (String) r[0], (String) r[1], (String) r[2], (String) r[3],
                bool(r[4]), bool(r[5]), bool(r[6]))).toList();
    }

    @PutMapping("/{spCode}")
    @Transactional
    ResponseEntity<?> save(@PathVariable String spCode, @RequestBody SaveBody b) {
        if (b.secretKey() == null || b.secretKey().isBlank()) {
            throw new IllegalStateException("Kunci rahsia wajib diisi.");
        }
        if (b.categoryCode() == null || b.categoryCode().isBlank()) {
            throw new IllegalStateException("Kod kategori wajib diisi.");
        }

        creds.save(spCode,
                b.gateway() == null || b.gateway().isBlank() ? "TP" : b.gateway().trim(),
                b.secretKey().trim(),
                b.categoryCode().trim(),
                b.sandbox() == null || b.sandbox());

        if (b.onlinePayment() != null) {
            em.createNativeQuery(
                    "UPDATE sp_payment_setting SET online_payment = :v WHERE sp_code = :sp")
                    .setParameter("v", b.onlinePayment() ? 1 : 0)
                    .setParameter("sp", spCode)
                    .executeUpdate();
        }

        return ResponseEntity.ok(Map.of("message", "Tetapan gerbang " + spCode + " disimpan."));
    }

    private static boolean bool(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean x) return x;
        return ((Number) v).intValue() != 0;
    }
}
