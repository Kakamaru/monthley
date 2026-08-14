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
        // SP platform diseed oleh V77 — lihat nota dalam ModuleEntitlementTest.

        em.createNativeQuery("""
            INSERT INTO product (sp_code, code, name, charge_frequency, unit_rate,
                                 main_product, mandatory, prorated, late_penalty,
                                 account_limit, status, created_at, updated_at, version)
            VALUES ('SP0000', 'PQ300', 'Pakej Ujian 300', 'MONTHLY', 80.00,
                    0,0,0,0, 300, 'ACTIVE', NOW(), NOW(), 0)
            """).executeUpdate();
        Long planProductId = ((Number) em.createNativeQuery(
                "SELECT id FROM product WHERE sp_code='SP0000' AND code='PQ300'")
                .getSingleResult()).longValue();

        // SP pelanggan yang menggunakan pelan itu, dipaut kepada KEDUA-DUA sumber
        em.createNativeQuery("""
            INSERT IGNORE INTO service_provider (sp_code, name, status,
                                                 plan_product_id,
                                                 created_at, updated_at, version)
            VALUES ('SPQ1', 'Pelanggan Ujian', 'ACTIVE', :prod, NOW(), NOW(), 0)
            """).setParameter("prod", planProductId).executeUpdate();
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
        List<OnboardingController.PlanDto> plans = onboarding.plans();

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
                "SELECT id FROM product WHERE sp_code='SP0000' AND code='PQ300'")
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
                planProductId,               // planProductId
                "ACC-UJIAN-1",               // accountNo
                null,                        // extraProductIds
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

    /**
     * Onboarding mesti menghasilkan TIGA rekod, bukan satu: SP, akaun bil di
     * bawah SP platform, dan langganan untuk setiap produk dipilih.
     *
     * Kama menguji ini secara manual dan mengesahkan masalahnya: SP yang
     * didaftar melalui superadmin tidak muncul dalam senarai akaun
     * Rapidevelop, dan akaun yang dicipta di Rapidevelop tidak muncul
     * sebagai SP. Dua rekod tanpa pautan bermakna setiap SP perlu didaftar
     * dua kali secara manual — dan sekali terlupa, ada SP yang tidak pernah
     * dibil.
     */
    @Test
    @DisplayName("onboard — SP, akaun bil, dan langganan tercipta serentak dan terpaut")
    void onboardCiptaAkaunBilTerpaut() {
        Long planProductId = ((Number) em.createNativeQuery(
                "SELECT id FROM product WHERE sp_code='SP0000' AND code='PQ300'")
                .getSingleResult()).longValue();

        // Item sekali sahaja, macam Onboarding/Migrasi
        em.createNativeQuery("""
            INSERT INTO product (sp_code, code, name, charge_frequency, unit_rate,
                                 main_product, mandatory, prorated, late_penalty,
                                 status, created_at, updated_at, version)
            VALUES ('SP0000', 'PQOB', 'Onboarding Ujian', 'ONE_TIME', 300.00,
                    0,0,0,0, 'ACTIVE', NOW(), NOW(), 0)
            """).executeUpdate();
        Long obId = ((Number) em.createNativeQuery(
                "SELECT id FROM product WHERE sp_code='SP0000' AND code='PQOB'")
                .getSingleResult()).longValue();

        em.createNativeQuery("""
            INSERT IGNORE INTO app_user (email, password_hash, full_name, status,
                                         created_at, updated_at, version)
            VALUES ('onboard-tiga@test.com', 'x', 'Admin Tiga', 'ACTIVE', NOW(), NOW(), 0)
            """).executeUpdate();
        em.flush();

        var req = new OnboardingController.OnboardRequest(
                "SP Tiga Rekod", "JMB", null, null, null,
                null, null, null, null, null, "Malaysia", null,
                planProductId, "ACC-TIGA", List.of(obId), 50,
                "Admin Tiga", "onboard-tiga@test.com", null,
                false, null, null, null, null, null);

        onboarding.onboard(req);
        em.flush();
        em.clear();

        // 1. SP wujud dan terpaut kepada akaun
        Object[] sp = (Object[]) em.createNativeQuery("""
                SELECT sp.sp_code, sp.billing_account_id, a.account_no, a.sp_code
                FROM   service_provider sp
                JOIN   account a ON a.id = sp.billing_account_id
                WHERE  sp.name = 'SP Tiga Rekod'
                """).getSingleResult();

        assertThat((String) sp[2]).isEqualTo("ACC-TIGA");
        // Akaun duduk di bawah SP PLATFORM, bukan SP baharu itu sendiri.
        assertThat((String) sp[3]).isEqualTo("SP0000");
        assertThat((String) sp[0]).isNotEqualTo((String) sp[3]);

        // 2. Dua langganan: pelan + item sekali sahaja
        Long accId = ((Number) sp[1]).longValue();
        Number bil = (Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM account_subscription WHERE account_id = :a")
                .setParameter("a", accId).getSingleResult();
        assertThat(bil.intValue()).isEqualTo(2);
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
