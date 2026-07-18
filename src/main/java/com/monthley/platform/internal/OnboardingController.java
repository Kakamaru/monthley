package com.monthley.platform.internal;

import com.monthley.ledger.api.LedgerPort;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Onboarding SP — superadmin sahaja (rujuk log keputusan §5).
 *
 *   GET  /api/v1/platform/service-plans   — pakej untuk dropdown
 *   POST /api/v1/platform/generate-key    — jana gateway key
 *   POST /api/v1/platform/onboard         — cipta SP + naik taraf admin
 *
 * Syarat: e-mel admin MESTI sudah berdaftar dengan Monthley (app_user).
 * Ini yang menaikkan taraf pengguna berdaftar menjadi SP_ADMIN.
 */
@RestController
@RequestMapping("/api/v1/platform")
class OnboardingController {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String KEY_CHARS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private final LedgerPort ledger;

    @PersistenceContext
    private EntityManager em;

    OnboardingController(LedgerPort ledger) {
        this.ledger = ledger;
    }

    // ---------- DTO ----------

    record ServicePlanDto(Long id, String code, String name, int accountLimit,
                          BigDecimal priceMonthly, BigDecimal priceYearly) {}

    record BusinessTypeDto(String code, String name, String description) {}

    record GeneratedKey(String merchantId, String gatewayKey) {}

    record OnboardRequest(
            // maklumat SP
            @NotBlank String name,
            String businessType,
            String registrationNo,
            String businessDesc,
            String website,
            String addrLine1,
            String addrLine2,
            String city,
            String postcode,
            String state,
            String country,
            String orgRegisteredDate,     // 'YYYY-MM-DD'
            Long   servicePlanId,
            String billingPlan,           // MONTHLY | YEARLY
            Integer estInvoicesMonth,

            // orang perhubungan
            @NotBlank String contactName,
            @Email @NotBlank String adminEmail,
            String contactPhone,

            // khidmat pembayaran online
            boolean absorb,               // true = organisasi serap
            String merchantId,
            String gatewayKey,

            // bank
            String bankName,
            String bankAccountNo,
            String bankAccountName
    ) {}

    record OnboardResult(String spCode, String name, Long adminUserId, String adminEmail) {}
    record ErrorResponse(String message) {}

    // ---------- Endpoints ----------

    @GetMapping("/service-plans")
    @SuppressWarnings("unchecked")
    List<ServicePlanDto> plans() {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT id, code, name, account_limit, price_monthly, price_yearly
                FROM service_plan WHERE status = 'ACTIVE' ORDER BY account_limit
                """).getResultList();
        List<ServicePlanDto> out = new ArrayList<>();
        for (Object[] r : rows) {
            out.add(new ServicePlanDto(
                    ((Number) r[0]).longValue(), (String) r[1], (String) r[2],
                    ((Number) r[3]).intValue(), (BigDecimal) r[4], (BigDecimal) r[5]));
        }
        return out;
    }

    @GetMapping("/business-types")
    @SuppressWarnings("unchecked")
    List<BusinessTypeDto> businessTypes() {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT code, name, description FROM ref_business_type
                WHERE status = 'ACTIVE' ORDER BY sort_order, name
                """).getResultList();
        List<BusinessTypeDto> out = new ArrayList<>();
        for (Object[] r : rows) {
            out.add(new BusinessTypeDto((String) r[0], (String) r[1], (String) r[2]));
        }
        return out;
    }

    /** Jana kunci gateway. Fungsi sebenar akan diselaraskan dengan mpay kemudian. */
    @PostMapping("/generate-key")
    GeneratedKey generateKey() {
        return new GeneratedKey(nextMerchantId(), randomKey(32));
    }

    @PostMapping("/onboard")
    @Transactional
    ResponseEntity<?> onboard(@Valid @RequestBody OnboardRequest r) {
        String email = r.adminEmail().toLowerCase().trim();

        // ---- Syarat: admin mesti sudah berdaftar ----
        Object[] user;
        try {
            user = (Object[]) em.createNativeQuery(
                            "SELECT id, full_name FROM app_user WHERE LOWER(email) = :e AND status = 'ACTIVE'")
                    .setParameter("e", email).getSingleResult();
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(
                    "E-mel '" + email + "' belum berdaftar dengan Monthley. "
                    + "Minta admin daftar akaun terlebih dahulu sebelum onboarding."));
        }
        Long adminUserId = ((Number) user[0]).longValue();

        // ---- Jana sp_code ----
        String spCode = nextSpCode();

        // ---- Cipta SP ----
        em.createNativeQuery("""
                INSERT INTO service_provider
                  (sp_code, name, business_type, registration_no, business_desc, website,
                   addr_line1, addr_line2, city, postcode, state, country,
                   org_registered_date, service_plan_id, billing_plan, est_invoices_month,
                   contact_email, phone, bank_code, bank_account_no, bank_account_name,
                   status, applied_at, approved_at, onboarded_by, created_at, updated_at, version)
                VALUES
                  (:sp, :name, :bizType, :reg, :desc, :web,
                   :a1, :a2, :city, :post, :state, :country,
                   :orgDate, :plan, :billPlan, :est,
                   :email, :phone, :bank, :accNo, :accName,
                   'ACTIVE', NOW(), NOW(), :by, NOW(), NOW(), 0)
                """)
                .setParameter("sp", spCode)
                .setParameter("name", r.name())
                .setParameter("bizType", r.businessType() == null || r.businessType().isBlank() ? null : r.businessType())
                .setParameter("reg", r.registrationNo())
                .setParameter("desc", r.businessDesc())
                .setParameter("web", r.website())
                .setParameter("a1", r.addrLine1())
                .setParameter("a2", r.addrLine2())
                .setParameter("city", r.city())
                .setParameter("post", r.postcode())
                .setParameter("state", r.state())
                .setParameter("country", r.country() == null ? "Malaysia" : r.country())
                .setParameter("orgDate", r.orgRegisteredDate() == null || r.orgRegisteredDate().isBlank()
                        ? null : LocalDate.parse(r.orgRegisteredDate()))
                .setParameter("plan", r.servicePlanId())
                .setParameter("billPlan", r.billingPlan() == null ? "MONTHLY" : r.billingPlan())
                .setParameter("est", r.estInvoicesMonth())
                .setParameter("email", email)
                .setParameter("phone", r.contactPhone())
                .setParameter("bank", r.bankName())
                .setParameter("accNo", r.bankAccountNo())
                .setParameter("accName", r.bankAccountName())
                .setParameter("by", adminUserId)
                .executeUpdate();

        // ---- Tetapan bayaran ----
        em.createNativeQuery("""
                INSERT INTO sp_payment_setting
                  (sp_code, gateway, merchant_id, gateway_key, manual_payment, online_payment,
                   absorb, rate_single, rate_multi, version)
                VALUES (:sp, 'MP', :mid, :key, 1, 1, :absorb, 1.50, 2.00, 0)
                """)
                .setParameter("sp", spCode)
                .setParameter("mid", r.merchantId())
                .setParameter("key", r.gatewayKey())
                .setParameter("absorb", r.absorb() ? 1 : 0)
                .executeUpdate();

        // ---- Naik taraf pengguna → SP_ADMIN ----
        em.createNativeQuery("""
                INSERT INTO sp_membership (sp_code, user_id, role, status, created_at, updated_at, version)
                VALUES (:sp, :uid, 'SP_ADMIN', 'ACTIVE', NOW(), NOW(), 0)
                """)
                .setParameter("sp", spCode)
                .setParameter("uid", adminUserId)
                .executeUpdate();

        // ---- Carta akaun standard ----
        em.flush();
        ledger.seedChartOfAccounts(spCode);

        return ResponseEntity.ok(new OnboardResult(spCode, r.name(), adminUserId, email));
    }

    // ---------- helper ----------

    private String nextSpCode() {
        Number n = (Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM service_provider").getSingleResult();
        String code;
        long i = n.longValue() + 1;
        do {
            code = String.format("SP%04d", i++);
        } while (exists(code));
        return code;
    }

    private boolean exists(String spCode) {
        Number n = (Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM service_provider WHERE sp_code = :c")
                .setParameter("c", spCode).getSingleResult();
        return n.intValue() > 0;
    }

    private String nextMerchantId() {
        Number n = (Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM sp_payment_setting").getSingleResult();
        return String.format("MY%08d", n.longValue() + 1);
    }

    private static String randomKey(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(KEY_CHARS.charAt(RANDOM.nextInt(KEY_CHARS.length())));
        }
        return sb.toString();
    }
}
