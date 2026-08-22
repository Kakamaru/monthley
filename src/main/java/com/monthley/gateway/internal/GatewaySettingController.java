package com.monthley.gateway.internal;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
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

    /**
     * @param keySet  sama ada kunci WUJUD — bukan nilainya. Skrin yang
     *                memaparkan kunci bermakna sesiapa yang melihatnya
     *                boleh menyalinnya.
     * @param absorb  true = SP menyerap yuran; false = pelanggan bayar
     */
    record SettingView(String spCode, String spName, String gateway,
                       String categoryCode, boolean sandbox,
                       boolean onlinePayment, boolean keySet,
                       boolean absorb, BigDecimal rateSingle,
                       BigDecimal rateMulti, BigDecimal rateMultiAcct,
                       BigDecimal minAmount) {}

    /**
     * secretKey adalah PILIHAN semasa mengemas kini.
     *
     * Kosong bermakna 'jangan sentuh kunci sedia ada' — superadmin yang
     * hendak menukar kadar yuran sahaja tidak sepatutnya perlu menaip
     * semula kunci gerbang, dan memaksanya bermakna kunci disalin ke
     * papan keratan setiap kali tetapan lain diubah.
     */
    record SaveBody(String gateway, String secretKey, String categoryCode,
                    Boolean sandbox, Boolean onlinePayment,
                    Boolean absorb, BigDecimal rateSingle,
                    BigDecimal rateMulti, BigDecimal rateMultiAcct,
                    BigDecimal minAmount) {}

    @GetMapping
    @SuppressWarnings("unchecked")
    List<SettingView> list() {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT s.sp_code, sp.name, s.gateway, s.category_code, s.sandbox,
                       s.online_payment,
                       (s.gateway_key_enc IS NOT NULL AND s.gateway_key_enc <> ''),
                       s.absorb, s.rate_single, s.rate_multi, s.rate_multi_acct,
                       sp.min_pymt_amount
                FROM   sp_payment_setting s
                JOIN   service_provider sp ON sp.sp_code = s.sp_code
                ORDER  BY s.sp_code
                """).getResultList();

        return rows.stream().map(r -> new SettingView(
                (String) r[0], (String) r[1], (String) r[2], (String) r[3],
                bool(r[4]), bool(r[5]), bool(r[6]),
                bool(r[7]), (BigDecimal) r[8], (BigDecimal) r[9],
                (BigDecimal) r[10], (BigDecimal) r[11])).toList();
    }

    @PutMapping("/{spCode}")
    @Transactional
    ResponseEntity<?> save(@PathVariable String spCode, @RequestBody SaveBody b) {
        // Kunci hanya ditulis apabila dihantar. Kosong bermakna kekalkan
        // yang sedia ada.
        boolean adaKunci = b.secretKey() != null && !b.secretKey().isBlank();

        if (adaKunci) {
            if (b.categoryCode() == null || b.categoryCode().isBlank()) {
                throw new IllegalStateException(
                        "Kod kategori wajib diisi bersama kunci rahsia.");
            }
            creds.save(spCode,
                    b.gateway() == null || b.gateway().isBlank() ? "TP" : b.gateway().trim(),
                    b.secretKey().trim(),
                    b.categoryCode().trim(),
                    b.sandbox() == null || b.sandbox());
        }

        // Kadar yuran: negatif ditolak. Yuran negatif bermakna sistem
        // MEMBAYAR pelanggan untuk membuat bayaran.
        if (b.rateSingle() != null && b.rateSingle().signum() < 0) {
            throw new IllegalStateException("Kadar yuran tidak boleh negatif.");
        }
        if (b.rateMulti() != null && b.rateMulti().signum() < 0) {
            throw new IllegalStateException("Kadar yuran tidak boleh negatif.");
        }
        if (b.rateMultiAcct() != null && b.rateMultiAcct().signum() < 0) {
            throw new IllegalStateException("Kadar yuran tidak boleh negatif.");
        }

        em.createNativeQuery("""
                UPDATE sp_payment_setting
                SET    online_payment = COALESCE(:online, online_payment),
                       absorb         = COALESCE(:absorb, absorb),
                       rate_single    = COALESCE(:single, rate_single),
                       rate_multi     = COALESCE(:multi, rate_multi),
                       rate_multi_acct = COALESCE(:multiAcct, rate_multi_acct),
                       sandbox        = COALESCE(:sandbox, sandbox),
                       gateway        = COALESCE(:gw, gateway)
                WHERE  sp_code = :sp
                """)
                .setParameter("online", b.onlinePayment() == null ? null
                        : (b.onlinePayment() ? 1 : 0))
                .setParameter("absorb", b.absorb() == null ? null
                        : (b.absorb() ? 1 : 0))
                .setParameter("single", b.rateSingle())
                .setParameter("multi", b.rateMulti())
                .setParameter("multiAcct", b.rateMultiAcct())
                .setParameter("sandbox", b.sandbox() == null ? null
                        : (b.sandbox() ? 1 : 0))
                .setParameter("gw", (b.gateway() == null || b.gateway().isBlank())
                        ? null : b.gateway().trim())
                .setParameter("sp", spCode)
                .executeUpdate();

        // Amaun minimum hidup pada service_provider, bukan tetapan bayaran
        // — ia dikongsi dengan laluan bayaran manual.
        if (b.minAmount() != null) {
            if (b.minAmount().signum() < 0) {
                throw new IllegalStateException("Amaun minimum tidak boleh negatif.");
            }
            em.createNativeQuery(
                    "UPDATE service_provider SET min_pymt_amount = :v WHERE sp_code = :sp")
                    .setParameter("v", b.minAmount())
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
