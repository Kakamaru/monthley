package com.monthley.donation.internal;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Borang derma awam — TIADA log masuk (ADR 0020 #1).
 *
 * Penderma ialah orang luar: tiada akaun, tiada kata laluan, dan tidak
 * akan mendaftar untuk menderma RM50. Menuntut log masuk menghalang derma
 * tanpa melindungi apa-apa — penderma tidak mengakses data sesiapa dan
 * memberikan wang.
 *
 * Yang MASIH dilindungi:
 *
 *   Amaun datang daripada gerbang semasa callback, bukan daripada
 *   permintaan (ADR 0007 #1).
 *
 *   Kempen mesti AKTIF dan dalam tempoh — kempen draf atau tamat tidak
 *   boleh menerima wang walaupun seseorang menyimpan pautannya.
 *
 *   Kadar yuran datang daripada tetapan, tidak boleh dipengaruhi oleh
 *   permintaan.
 *
 * Terletak di bawah /api/v1/pub/** yang sudah dibuka dalam SecurityConfig.
 */
@RestController
@RequestMapping("/api/v1/pub/donations")
class PublicDonationController {

    @PersistenceContext
    private EntityManager em;

    private final DonationService service;

    PublicDonationController(DonationService service) {
        this.service = service;
    }

    /**
     * @param spName  nama SP dipaparkan pada borang — penderma perlu tahu
     *                kepada siapa wang pergi
     */
    record PublicCampaign(
            String slug, String title, String description, String posterUrl,
            String spName,
            BigDecimal targetAmount, BigDecimal raised, long donors,
            List<BigDecimal> presets, BigDecimal minAmount, boolean allowCustom,
            boolean requireName, boolean requireEmail, boolean requirePhone,
            boolean requireAccount, boolean allowAnonymous) {}

    record DonateBody(
            @NotNull BigDecimal amount,
            String donorName, String donorEmail, String donorPhone,
            String donorAccount, Boolean anonymous) {}

    /** Kempen untuk borang awam. Hanya yang AKTIF dan dalam tempoh. */
    @GetMapping("/{slug}")
    @Transactional(readOnly = true)
    ResponseEntity<?> kempen(@PathVariable String slug) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT c.slug, c.title, c.description, c.poster_url, s.name,
                       c.target_amount,
                       COALESCE((SELECT SUM(d.amount) FROM donation d
                                 WHERE d.campaign_id = c.id AND d.status = 'SUCCESS'), 0),
                       (SELECT COUNT(*) FROM donation d
                        WHERE d.campaign_id = c.id AND d.status = 'SUCCESS'),
                       c.preset_amounts, c.min_amount, c.allow_custom,
                       c.require_name, c.require_email, c.require_phone,
                       c.require_account, c.allow_anonymous
                FROM   donation_campaign c
                JOIN   service_provider s ON s.sp_code = c.sp_code
                WHERE  c.slug = :slug AND c.status = 'ACTIVE'
                  AND  (c.start_date IS NULL OR c.start_date <= CURDATE())
                  AND  (c.end_date IS NULL OR c.end_date >= CURDATE())
                """)
                .setParameter("slug", slug)
                .getResultList();

        if (rows.isEmpty()) {
            // Mesej yang sama untuk 'tidak wujud' dan 'tidak aktif':
            // membezakan keduanya memberitahu orang luar kempen mana yang
            // wujud tetapi ditutup.
            return ResponseEntity.status(404).body(Map.of(
                    "message", "Kempen tidak dijumpai atau sudah ditutup."));
        }

        Object[] r = rows.get(0);
        return ResponseEntity.ok(new PublicCampaign(
                (String) r[0], (String) r[1], (String) r[2], (String) r[3],
                (String) r[4],
                (BigDecimal) r[5], (BigDecimal) r[6], ((Number) r[7]).longValue(),
                huraiPresets((String) r[8]), (BigDecimal) r[9], bool(r[10]),
                bool(r[11]), bool(r[12]), bool(r[13]), bool(r[14]), bool(r[15])));
    }

    /** Pratonton caj sebelum penderma meneruskan. */
    @PostMapping("/{slug}/preview")
    @Transactional(readOnly = true)
    ResponseEntity<?> pratonton(@PathVariable String slug,
                                @Valid @RequestBody DonateBody b) {
        var p = service.pratontonYuran(slug, b.amount());
        return ResponseEntity.ok(Map.of(
                "amount", p.amount(), "fee", p.fee(),
                "charged", p.charged(), "absorb", p.absorb()));
    }

    /** Mulakan derma — memulangkan URL gerbang. */
    @PostMapping("/{slug}/donate")
    @Transactional
    ResponseEntity<?> derma(@PathVariable String slug,
                            @Valid @RequestBody DonateBody b) {
        var hasil = service.mulakan(slug, b);
        return ResponseEntity.ok(Map.of(
                "ourRef", hasil.ourRef(),
                "paymentUrl", hasil.paymentUrl(),
                "amount", hasil.amount(),
                "fee", hasil.fee(),
                "charged", hasil.charged()));
    }

    /** Status selepas penderma kembali dari gerbang. */
    @GetMapping("/status/{ourRef}")
    @Transactional(readOnly = true)
    ResponseEntity<?> status(@PathVariable String ourRef) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT d.status, d.amount, d.fee_amount, r.doc_no, c.title
                FROM   donation d
                JOIN   donation_campaign c ON c.id = d.campaign_id
                LEFT   JOIN financial_document r ON r.id = d.receipt_document_id
                WHERE  d.our_ref = :ref
                """)
                .setParameter("ref", ourRef)
                .getResultList();

        if (rows.isEmpty()) return ResponseEntity.notFound().build();

        Object[] r = rows.get(0);
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("status", r[0]);
        body.put("amount", r[1]);
        body.put("fee", r[2]);
        body.put("receiptNo", r[3]);
        body.put("campaign", r[4]);
        return ResponseEntity.ok(body);
    }

    /**
     * Callback gerbang — sentiasa 200.
     *
     * Gerbang mengulangi callback yang tidak mendapat 200, jadi kegagalan
     * pemprosesan dilog dan bukan dipulangkan sebagai ralat. Kalau tidak,
     * derma yang gagal diproses menjadi gelung ulangan tanpa henti.
     */
    @PostMapping("/callback")
    ResponseEntity<String> callback(@RequestParam java.util.Map<String, String> form) {
        String ourRef = form.get("order_id");
        if (ourRef == null || ourRef.isBlank()) {
            return ResponseEntity.ok("OK");
        }
        try {
            service.handleCallback(ourRef, form.toString());
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(PublicDonationController.class)
                    .error("Gagal memproses callback derma {}: {}", ourRef, e.getMessage());
        }
        return ResponseEntity.ok("OK");
    }

    /** '10, 50, 100' -> [10, 50, 100]. Maksimum empat. */
    private static List<BigDecimal> huraiPresets(String s) {
        List<BigDecimal> out = new ArrayList<>();
        if (s == null || s.isBlank()) return out;
        for (String bahagian : s.split(",")) {
            String t = bahagian.trim();
            if (t.isEmpty()) continue;
            try {
                out.add(new BigDecimal(t));
            } catch (NumberFormatException e) {
                // Nilai rosak dilangkau: borang masih berfungsi dengan
                // pilihan yang sah, dan penderma boleh menaip amaun sendiri.
            }
            if (out.size() == 4) break;
        }
        return out;
    }

    private static boolean bool(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean b) return b;
        return ((Number) o).intValue() != 0;
    }
}
