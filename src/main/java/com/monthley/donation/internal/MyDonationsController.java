package com.monthley.donation.internal;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Sumbangan — sisi pelanggan yang LOG MASUK.
 *
 * Berbeza daripada borang awam dalam dua cara:
 *
 *   Kempen ditapis kepada SP yang pelanggan mempunyai akaun dengannya.
 *   Pelanggan JMB tidak sepatutnya melihat kutipan sekolah yang tiada
 *   kaitan dengannya.
 *
 *   Maklumat penderma diambil daripada profil, bukan ditaip semula.
 *   Pelanggan yang sudah log masuk memberikan nama dan e-melnya semasa
 *   mendaftar.
 */
@RestController
@RequestMapping("/api/v1/donations/my")
class MyDonationsController {

    @PersistenceContext
    private EntityManager em;

    private final DonationService service;

    MyDonationsController(DonationService service) {
        this.service = service;
    }

    private Long uid() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new IllegalStateException("Tiada pengguna dalam konteks.");
        }
        return Long.valueOf(auth.getName());
    }

    record MyCampaign(
            Long id, String slug, String title, String description,
            String posterUrl, String spCode, String spName,
            BigDecimal targetAmount, BigDecimal raised, long donors,
            List<BigDecimal> presets, BigDecimal minAmount, boolean allowCustom,
            boolean allowAnonymous) {}

    /**
     * Maklumat penderma boleh DIUBAH oleh pelanggan.
     *
     * Profil mengisi medan sebagai lalai, tetapi telefon selalunya tiada
     * dan sesetengah orang menderma bagi pihak keluarga. Memaksa nilai
     * profil bermakna borang menolak derma yang sah.
     *
     * Kosong bermakna guna nilai profil.
     */
    record DonateBody(@NotNull BigDecimal amount, Boolean anonymous,
                      String donorName, String donorEmail, String donorPhone) {}

    /** Profil untuk mengisi borang. */
    record MyProfile(String name, String email, String phone) {}

    @GetMapping("/profile")
    @Transactional(readOnly = true)
    MyProfile profil() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT full_name, email, mobile FROM app_user WHERE id = :uid")
                .setParameter("uid", uid()).getResultList();
        if (rows.isEmpty()) return new MyProfile(null, null, null);
        Object[] u = rows.get(0);
        return new MyProfile((String) u[0], (String) u[1], (String) u[2]);
    }

    /**
     * Kempen aktif daripada SP yang pelanggan mempunyai akaun dengannya.
     */
    @GetMapping("/campaigns")
    @Transactional(readOnly = true)
    List<MyCampaign> senarai() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT c.id, c.slug, c.title, c.description, c.poster_url,
                       c.sp_code, s.name,
                       c.target_amount,
                       COALESCE((SELECT SUM(d.amount) FROM donation d
                                 WHERE d.campaign_id = c.id AND d.status = 'SUCCESS'), 0),
                       (SELECT COUNT(*) FROM donation d
                        WHERE d.campaign_id = c.id AND d.status = 'SUCCESS'),
                       c.preset_amounts, c.min_amount, c.allow_custom,
                       c.allow_anonymous
                FROM   donation_campaign c
                JOIN   service_provider s ON s.sp_code = c.sp_code
                WHERE  c.status = 'ACTIVE'
                  AND  (c.start_date IS NULL OR c.start_date <= CURDATE())
                  AND  (c.end_date IS NULL OR c.end_date >= CURDATE())
                  AND  EXISTS (SELECT 1 FROM account a
                               WHERE a.sp_code = c.sp_code
                                 AND a.payer_user_id = :uid
                                 AND a.status = 'ACTIVE')
                ORDER  BY c.sp_code, c.id DESC
                """)
                .setParameter("uid", uid())
                .getResultList();

        return rows.stream().map(r -> new MyCampaign(
                ((Number) r[0]).longValue(), (String) r[1], (String) r[2],
                (String) r[3], (String) r[4], (String) r[5], (String) r[6],
                (BigDecimal) r[7], (BigDecimal) r[8], ((Number) r[9]).longValue(),
                huraiPresets((String) r[10]), (BigDecimal) r[11],
                bool(r[12]), bool(r[13]))).toList();
    }

    /** Pratonton caj. */
    @PostMapping("/{slug}/preview")
    @Transactional(readOnly = true)
    ResponseEntity<?> pratonton(@PathVariable String slug,
                                @Valid @RequestBody DonateBody b) {
        var p = service.pratontonYuran(slug, b.amount());
        return ResponseEntity.ok(Map.of(
                "amount", p.amount(), "fee", p.fee(),
                "charged", p.charged(), "absorb", p.absorb()));
    }

    /**
     * Derma sebagai pelanggan yang log masuk.
     *
     * Nama, e-mel, dan telefon diambil daripada profil — pelanggan tidak
     * menaipnya semula, dan maklumat yang datang daripada permintaan
     * tidak dipercayai untuk medan ini.
     */
    @PostMapping("/{slug}/donate")
    @Transactional
    ResponseEntity<?> derma(@PathVariable String slug,
                            @Valid @RequestBody DonateBody b) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT full_name, email, mobile FROM app_user WHERE id = :uid")
                .setParameter("uid", uid()).getResultList();
        if (rows.isEmpty()) {
            throw new IllegalStateException("Pengguna tidak dijumpai.");
        }
        Object[] u = rows.get(0);

        // Nilai yang dihantar menang; profil hanya mengisi yang kosong.
        var body = new PublicDonationController.DonateBody(
                b.amount(),
                pilih(b.donorName(), (String) u[0]),
                pilih(b.donorEmail(), (String) u[1]),
                pilih(b.donorPhone(), (String) u[2]),
                null, b.anonymous());

        var hasil = service.mulakan(slug, body);
        return ResponseEntity.ok(Map.of(
                "ourRef", hasil.ourRef(),
                "paymentUrl", hasil.paymentUrl(),
                "amount", hasil.amount(),
                "fee", hasil.fee(),
                "charged", hasil.charged()));
    }

    private static String pilih(String dihantar, String profil) {
        return (dihantar == null || dihantar.trim().isEmpty()) ? profil : dihantar.trim();
    }

    private static List<BigDecimal> huraiPresets(String s) {
        List<BigDecimal> out = new java.util.ArrayList<>();
        if (s == null || s.isBlank()) return out;
        for (String bahagian : s.split(",")) {
            String t = bahagian.trim();
            if (t.isEmpty()) continue;
            try { out.add(new BigDecimal(t)); } catch (NumberFormatException e) { }
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
