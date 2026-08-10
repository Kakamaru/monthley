package com.monthley.platform.internal;

import com.monthley.shared.PageResponse;
import com.monthley.shared.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Merakam kelakuan pelan SP SEBELUM sumbernya dialihkan dari service_plan
 * ke product (ADR 0016 peringkat B3).
 *
 * Tiga endpoint memandu tiga skrin dan TIADA satu pun ujian melindunginya
 * sebelum ini. Tanpa ujian ini, menukar sumber data akan berlalu senyap:
 * mvn test kekal hijau dan kesilapan hanya kelihatan bila skrin dibuka.
 *
 * Nilai yang ditegaskan ialah kuota dan nama — BUKAN harga. Harga sengaja
 * berbeza antara dua sumber (service_plan RM300 vs product RM80) dan RM80
 * yang betul, jadi menegaskan harga di sini bermakna ujian ini perlu
 * diubah semasa B3 — dan ujian yang diubah bersama kod yang diujinya
 * tidak menjaga apa-apa.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PlanSourceTest {

    @Autowired OnboardingController onboarding;
    @Autowired ServiceProviderController spList;
    @PersistenceContext EntityManager em;

    @BeforeEach
    void setup() {
        // SP platform + produk pelan (macam SP0000 dalam monthley_new)
        em.createNativeQuery("""
            INSERT IGNORE INTO service_provider (sp_code, name, status, is_platform_owner,
                                                 created_at, updated_at, version)
            VALUES ('SPQ0', 'Platform Test', 'ACTIVE', 1, NOW(), NOW(), 0)
            """).executeUpdate();

        em.createNativeQuery("""
            INSERT INTO product (sp_code, code, name, charge_frequency, unit_rate,
                                 main_product, mandatory, prorated, late_penalty,
                                 account_limit, status, created_at, updated_at, version)
            VALUES ('SPQ0', 'PQ300', 'Pakej Ujian 300', 'MONTHLY', 80.00,
                    0,0,0,0, 300, 'ACTIVE', NOW(), NOW(), 0)
            """).executeUpdate();
        Long planProductId = ((Number) em.createNativeQuery(
                "SELECT id FROM product WHERE sp_code='SPQ0' AND code='PQ300'")
                .getSingleResult()).longValue();

        // Pelan lama (service_plan) — sumber semasa
        em.createNativeQuery("""
            INSERT INTO service_plan (code, name, account_limit, price_monthly,
                                      price_yearly, status, created_at, updated_at, version)
            VALUES ('PQ300', 'Pakej Ujian 300', 300, 300.00, 3000.00,
                    'ACTIVE', NOW(), NOW(), 0)
            """).executeUpdate();
        Long planId = ((Number) em.createNativeQuery(
                "SELECT id FROM service_plan WHERE code='PQ300'")
                .getSingleResult()).longValue();

        // SP pelanggan yang menggunakan pelan itu, dipaut kepada KEDUA-DUA sumber
        em.createNativeQuery("""
            INSERT IGNORE INTO service_provider (sp_code, name, status, service_plan_id,
                                                 plan_product_id, billing_plan,
                                                 created_at, updated_at, version)
            VALUES ('SPQ1', 'Pelanggan Ujian', 'ACTIVE', :plan, :prod, 'MONTHLY',
                    NOW(), NOW(), 0)
            """).setParameter("plan", planId).setParameter("prod", planProductId)
                .executeUpdate();
        em.flush();

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("superadmin", "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_SUPERADMIN"))));
    }

    @AfterEach
    void clear() { TenantContext.clear(); SecurityContextHolder.clearContext(); }

    @Test
    @DisplayName("service-plans — pelan aktif disenaraikan dengan kuota")
    void senaraiPelan() {
        List<OnboardingController.ServicePlanDto> plans = onboarding.plans();

        var pq = plans.stream().filter(p -> "PQ300".equals(p.code())).findFirst().orElseThrow();
        assertThat(pq.name()).isEqualTo("Pakej Ujian 300");
        assertThat(pq.accountLimit()).isEqualTo(300);
    }

    @Test
    @DisplayName("senarai SP — nama pelan dan kuota muncul pada baris SP")
    void senaraiSpTunjukPelan() {
        PageResponse<ServiceProviderController.SpRow> r =
                spList.list(null, null, null, null, null, 0, 50);

        var row = r.items().stream()
                .filter(x -> "SPQ1".equals(x.spCode())).findFirst().orElseThrow();

        assertThat(row.planName()).isEqualTo("Pakej Ujian 300");
        assertThat(row.accountLimit()).isEqualTo(300);
    }

    /**
     * Laluan TULIS, bukan bacaan.
     *
     * B3 mengalihkan tiga BACAAN ke produk tetapi terlepas laluan tulis:
     * /onboard masih menyimpan id ke service_plan_id, sedangkan borang kini
     * menghantar id PRODUK. FK ditolak dan onboarding gagal dengan 500 —
     * dan tiada ujian menangkapnya kerana semua ujian sedia ada membaca
     * sahaja.
     */
    @Test
    @DisplayName("onboard — id produk pelan disimpan ke plan_product_id, bukan service_plan_id")
    void onboardSimpanProdukPelan() {
        Long planProductId = ((Number) em.createNativeQuery(
                "SELECT id FROM product WHERE sp_code='SPQ0' AND code='PQ300'")
                .getSingleResult()).longValue();

        // Admin mesti wujud dahulu — syarat onboarding.
        em.createNativeQuery("""
            INSERT IGNORE INTO app_user (email, password_hash, full_name, status,
                                         created_at, updated_at, version)
            VALUES ('onboard-ujian@test.com', 'x', 'Admin Ujian', 'ACTIVE',
                    NOW(), NOW(), 0)
            """).executeUpdate();
        em.flush();

        var req = new OnboardingController.OnboardRequest(
                "SP Ujian Onboard",          // name
                "JMB",                       // businessType
                null, null, null,            // registrationNo, businessDesc, website
                null, null, null, null, null,// addr1, addr2, city, postcode, state
                "Malaysia",                  // country
                null,                        // orgRegisteredDate
                planProductId,               // servicePlanId — kini id PRODUK
                "MONTHLY",                   // billingPlan (diabaikan, digugurkan B5)
                100,                         // estInvoicesMonth
                "Admin Ujian",               // contactName
                "onboard-ujian@test.com",    // adminEmail
                null,                        // contactPhone
                false,                       // absorb
                null, null,                  // merchantId, gatewayKey
                null, null, null);           // bankName, bankAccountNo, bankAccountName

        onboarding.onboard(req);
        em.flush();
        em.clear();

        Object[] row = (Object[]) em.createNativeQuery("""
                SELECT sp.plan_product_id, p.code, p.account_limit
                FROM   service_provider sp
                JOIN   product p ON p.id = sp.plan_product_id
                WHERE  sp.name = 'SP Ujian Onboard'
                """).getSingleResult();

        assertThat(((Number) row[0]).longValue()).isEqualTo(planProductId);
        assertThat((String) row[1]).isEqualTo("PQ300");
        assertThat(((Number) row[2]).intValue()).isEqualTo(300);
    }

    @Test
    @DisplayName("SP tanpa pelan tidak memecahkan senarai")
    void spTanpaPelan() {
        em.createNativeQuery("""
            INSERT IGNORE INTO service_provider (sp_code, name, status,
                                                 created_at, updated_at, version)
            VALUES ('SPQ2', 'Tiada Pelan', 'ACTIVE', NOW(), NOW(), 0)
            """).executeUpdate();
        em.flush();

        PageResponse<ServiceProviderController.SpRow> r =
                spList.list(null, null, null, null, null, 0, 50);

        var row = r.items().stream()
                .filter(x -> "SPQ2".equals(x.spCode())).findFirst().orElseThrow();

        assertThat(row.planName()).isNull();
        assertThat(row.accountLimit()).isNull();
    }
}
