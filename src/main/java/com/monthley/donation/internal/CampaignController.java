package com.monthley.donation.internal;

import com.monthley.shared.Access;
import com.monthley.shared.TenantContext;
import org.springframework.security.core.context.SecurityContextHolder;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Pengurusan kempen derma — sisi SP.
 *
 * Borang awam dan pemprosesan bayaran hidup dalam controller berasingan:
 * yang ini menuntut log masuk dan konteks SP, yang itu tidak menuntut
 * apa-apa (ADR 0020 #1).
 */
@RestController
@RequestMapping("/api/v1/donations/campaigns")
class CampaignController {

    @PersistenceContext
    private EntityManager em;

    private String sp() {
        String s = TenantContext.get();
        if (s == null || s.isBlank()) {
            throw new IllegalStateException("Tiada konteks SP.");
        }
        return s;
    }

    private Long currentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new IllegalStateException("Tiada pengguna dalam konteks.");
        }
        return Long.valueOf(auth.getName());
    }

    record CampaignDto(
            Long id, String title, String description, String posterUrl,
            String campaignType, String slug, String status,
            LocalDate startDate, LocalDate endDate,
            BigDecimal targetAmount, String presetAmounts, BigDecimal minAmount,
            boolean allowCustom,
            boolean requireName, boolean requireEmail, boolean requirePhone,
            boolean requireAccount, boolean allowAnonymous,
            Boolean absorbFee, boolean autoReceipt,
            // Dikira, bukan disimpan: jumlah yang terkumpul berubah setiap
            // kali derma masuk, dan lajur cache akan menyimpang.
            BigDecimal raised, long donors) {}

    record SaveCampaign(
            @NotBlank String title, String description, String posterUrl,
            String campaignType, @NotBlank String slug, String status,
            LocalDate startDate, LocalDate endDate,
            BigDecimal targetAmount, String presetAmounts, BigDecimal minAmount,
            Boolean allowCustom,
            Boolean requireName, Boolean requireEmail, Boolean requirePhone,
            Boolean requireAccount, Boolean allowAnonymous,
            Boolean absorbFee, Boolean autoReceipt) {}

    @GetMapping
    @Transactional(readOnly = true)
    List<CampaignDto> senarai() {
        Access.requireAnyRole("melihat kempen derma", "SP_ADMIN", "CLERK", "VIEWER");

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT c.id, c.title, c.description, c.poster_url,
                       c.campaign_type, c.slug, c.status,
                       c.start_date, c.end_date,
                       c.target_amount, c.preset_amounts, c.min_amount,
                       c.allow_custom,
                       c.require_name, c.require_email, c.require_phone,
                       c.require_account, c.allow_anonymous,
                       c.absorb_fee, c.auto_receipt,
                       COALESCE((SELECT SUM(d.amount) FROM donation d
                                 WHERE d.campaign_id = c.id AND d.status = 'SUCCESS'), 0),
                       (SELECT COUNT(*) FROM donation d
                        WHERE d.campaign_id = c.id AND d.status = 'SUCCESS')
                FROM   donation_campaign c
                WHERE  c.sp_code = :sp
                ORDER  BY c.status = 'ACTIVE' DESC, c.id DESC
                """)
                .setParameter("sp", sp())
                .getResultList();

        return rows.stream().map(r -> new CampaignDto(
                ((Number) r[0]).longValue(), (String) r[1], (String) r[2], (String) r[3],
                (String) r[4], (String) r[5], (String) r[6],
                toDate(r[7]), toDate(r[8]),
                (BigDecimal) r[9], (String) r[10], (BigDecimal) r[11],
                bool(r[12]),
                bool(r[13]), bool(r[14]), bool(r[15]), bool(r[16]), bool(r[17]),
                r[18] == null ? null : bool(r[18]), bool(r[19]),
                (BigDecimal) r[20], ((Number) r[21]).longValue())).toList();
    }

    @PostMapping
    @Transactional
    ResponseEntity<?> cipta(@Valid @RequestBody SaveCampaign r) {
        Access.requireRole("SP_ADMIN", "mencipta kempen derma");

        String slug = bersihSlug(r.slug());
        semakSlug(slug, null);

        em.createNativeQuery("""
                INSERT INTO donation_campaign
                  (sp_code, title, description, poster_url, campaign_type, slug,
                   status, start_date, end_date, target_amount, preset_amounts,
                   min_amount, allow_custom, require_name, require_email,
                   require_phone, require_account, allow_anonymous,
                   absorb_fee, auto_receipt, created_at, updated_at, created_by, version)
                VALUES (:sp, :title, :desc, :poster, :type, :slug,
                        :status, :start, :end, :target, :presets,
                        :min, :custom, :rName, :rEmail, :rPhone, :rAcct, :anon,
                        :absorb, :receipt, NOW(), NOW(), :by, 0)
                """)
                .setParameter("sp", sp())
                .setParameter("title", r.title().trim())
                .setParameter("desc", r.description())
                .setParameter("poster", r.posterUrl())
                .setParameter("type", r.campaignType() == null ? "DERMA" : r.campaignType())
                .setParameter("slug", slug)
                .setParameter("status", r.status() == null ? "DRAFT" : r.status())
                .setParameter("start", r.startDate())
                .setParameter("end", r.endDate())
                .setParameter("target", r.targetAmount())
                .setParameter("presets", r.presetAmounts())
                .setParameter("min", r.minAmount())
                .setParameter("custom", flag(r.allowCustom(), true))
                .setParameter("rName", flag(r.requireName(), true))
                .setParameter("rEmail", flag(r.requireEmail(), true))
                .setParameter("rPhone", flag(r.requirePhone(), false))
                .setParameter("rAcct", flag(r.requireAccount(), false))
                .setParameter("anon", flag(r.allowAnonymous(), true))
                .setParameter("absorb", r.absorbFee() == null ? null
                        : (r.absorbFee() ? 1 : 0))
                .setParameter("receipt", flag(r.autoReceipt(), true))
                .setParameter("by", String.valueOf(currentUserId()))
                .executeUpdate();

        return ResponseEntity.ok(Map.of("message", "Kempen dicipta.", "slug", slug));
    }

    @PutMapping("/{id}")
    @Transactional
    ResponseEntity<?> kemasKini(@PathVariable Long id, @Valid @RequestBody SaveCampaign r) {
        Access.requireRole("SP_ADMIN", "mengemas kini kempen derma");

        String slug = bersihSlug(r.slug());
        semakSlug(slug, id);

        int n = em.createNativeQuery("""
                UPDATE donation_campaign
                SET    title = :title, description = :desc, poster_url = :poster,
                       campaign_type = :type, slug = :slug, status = :status,
                       start_date = :start, end_date = :end,
                       target_amount = :target, preset_amounts = :presets,
                       min_amount = :min, allow_custom = :custom,
                       require_name = :rName, require_email = :rEmail,
                       require_phone = :rPhone, require_account = :rAcct,
                       allow_anonymous = :anon, absorb_fee = :absorb,
                       auto_receipt = :receipt, updated_at = NOW()
                WHERE  id = :id AND sp_code = :sp
                """)
                .setParameter("id", id)
                .setParameter("sp", sp())
                .setParameter("title", r.title().trim())
                .setParameter("desc", r.description())
                .setParameter("poster", r.posterUrl())
                .setParameter("type", r.campaignType() == null ? "DERMA" : r.campaignType())
                .setParameter("slug", slug)
                .setParameter("status", r.status() == null ? "DRAFT" : r.status())
                .setParameter("start", r.startDate())
                .setParameter("end", r.endDate())
                .setParameter("target", r.targetAmount())
                .setParameter("presets", r.presetAmounts())
                .setParameter("min", r.minAmount())
                .setParameter("custom", flag(r.allowCustom(), true))
                .setParameter("rName", flag(r.requireName(), true))
                .setParameter("rEmail", flag(r.requireEmail(), true))
                .setParameter("rPhone", flag(r.requirePhone(), false))
                .setParameter("rAcct", flag(r.requireAccount(), false))
                .setParameter("anon", flag(r.allowAnonymous(), true))
                .setParameter("absorb", r.absorbFee() == null ? null
                        : (r.absorbFee() ? 1 : 0))
                .setParameter("receipt", flag(r.autoReceipt(), true))
                .executeUpdate();

        if (n == 0) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("message", "Kempen dikemas kini."));
    }

    /**
     * Slug mesti unik MERENTAS SEMUA SP.
     *
     * URL awam tidak membawa kod SP, jadi dua SP dengan 'tabung-surau'
     * bermakna satu menerima derma yang ditujukan kepada yang lain.
     */
    private void semakSlug(String slug, Long kecualiId) {
        @SuppressWarnings("unchecked")
        List<Object> ada = em.createNativeQuery(
                "SELECT id FROM donation_campaign WHERE slug = :slug")
                .setParameter("slug", slug).getResultList();

        for (Object o : ada) {
            long id = ((Number) o).longValue();
            if (kecualiId == null || id != kecualiId) {
                throw new IllegalStateException(
                        "Pautan '" + slug + "' sudah digunakan. Sila pilih yang lain.");
            }
        }
    }

    /** Huruf kecil, sempang sahaja — slug muncul dalam URL yang dikongsi. */
    private static String bersihSlug(String s) {
        String bersih = s == null ? "" : s.trim().toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (bersih.isEmpty()) {
            throw new IllegalStateException("Pautan awam diperlukan.");
        }
        return bersih;
    }

    private static int flag(Boolean b, boolean lalai) {
        return (b == null ? lalai : b) ? 1 : 0;
    }

    private static boolean bool(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean b) return b;
        return ((Number) o).intValue() != 0;
    }

    private static LocalDate toDate(Object o) {
        if (o == null) return null;
        if (o instanceof LocalDate d) return d;
        if (o instanceof java.sql.Date d) return d.toLocalDate();
        return LocalDate.parse(o.toString());
    }
}
