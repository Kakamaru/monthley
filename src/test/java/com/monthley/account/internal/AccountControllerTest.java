package com.monthley.account.internal;

import com.monthley.shared.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Menguji AccountController.create() — laluan cipta akaun penuh (teras + ahli +
 * dua alamat). Tanpa ujian ini, 24 field SaveAccountRequest tidak tersentuh.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AccountControllerTest {

    @Autowired AccountController controller;
    @PersistenceContext EntityManager em;

    @BeforeEach
    void setup() {
        em.createNativeQuery("""
            INSERT IGNORE INTO service_provider (sp_code, name, status, created_at, updated_at, version)
            VALUES ('SPX', 'Akaun Test', 'ACTIVE', NOW(), NOW(), 0)
            """).executeUpdate();
        TenantContext.set("SPX");
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    @Test
    @DisplayName("create() simpan teras + ahli + dua alamat")
    void createsFullAccount() {
        var req = new AccountController.SaveAccountRequest(
                "ACC-100", "Pemilik Unit A", null, "MONTHLY",
                java.time.LocalDate.of(2026, 1, 1), null,
                // ahli
                "Ali bin Ahmad", "900101-01-1234", "ali@test.com", "0123456789",
                // alamat akaun
                "No 1", "Jalan Satu", "Taman Dua", null,
                "40000", "Selangor", "MY",
                // billto
                "Penyewa Baba", "baba@test.com", "0198765432",
                "No 2", "Jalan Tiga", null, null,
                "50000", "Wp Kuala Lumpur", "MY",
                null, null, null, null, null, java.util.List.of());

        var resp = controller.create(req);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();

        // Sahkan tersimpan penuh
        Object[] row = (Object[]) em.createNativeQuery("""
                SELECT account_name, member_name, member_id_no, addr_line1, addr_postcode,
                       addr_state, billto_name, billto_addr_line1, billto_postcode, billto_state
                FROM account WHERE sp_code='SPX' AND account_no='ACC-100'
                """).getSingleResult();

        assertThat(row[0]).isEqualTo("Pemilik Unit A");
        assertThat(row[1]).isEqualTo("Ali bin Ahmad");
        assertThat(row[2]).isEqualTo("900101-01-1234");
        assertThat(row[3]).isEqualTo("No 1");
        assertThat(row[4]).isEqualTo("40000");
        assertThat(row[5]).isEqualTo("Selangor");
        assertThat(row[6]).isEqualTo("Penyewa Baba");     // billto beza dari member
        assertThat(row[7]).isEqualTo("No 2");
        assertThat(row[8]).isEqualTo("50000");
        assertThat(row[9]).isEqualTo("Wp Kuala Lumpur");
    }

    @Test
    @DisplayName("create() dengan langganan produk -> account_subscription terisi")
    void createsWithSubscriptions() {
        // Cipta produk dulu
        em.createNativeQuery("""
            INSERT INTO product (sp_code, code, name, charge_frequency, unit_rate,
                                 main_product, mandatory, prorated, late_penalty, status,
                                 created_at, updated_at, version)
            VALUES ('SPX', 'PRD1', 'Sewa Unit', 'MONTHLY', 500.00, 0, 0, 0, 0, 'ACTIVE', NOW(), NOW(), 0)
            """).executeUpdate();
        Long prodId = ((Number) em.createNativeQuery(
                "SELECT id FROM product WHERE sp_code='SPX' AND code='PRD1'").getSingleResult()).longValue();

        var sub = new AccountController.SubLine(
                prodId, new java.math.BigDecimal("2"),
                java.time.LocalDate.of(2026, 1, 1), null, null);

        var req = new AccountController.SaveAccountRequest(
                "ACC-SUB", "Akaun Langgan", null, "MONTHLY",
                null, null,
                null, null, null, null,
                null, null, null, null, null, null, null,
                "Billto", null, null, null, null, null, null, null, null, null,
                null, null, null, null, null,
                java.util.List.of(sub));

        var resp = controller.create(req);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();

        // Sahkan subscription terisi
        Long accId = ((Number) em.createNativeQuery(
                "SELECT id FROM account WHERE sp_code='SPX' AND account_no='ACC-SUB'")
                .getSingleResult()).longValue();
        Long count = ((Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM account_subscription WHERE account_id = :a AND product_id = :p")
                .setParameter("a", accId).setParameter("p", prodId).getSingleResult()).longValue();
        assertThat(count).isEqualTo(1L);

        // qty betul
        java.math.BigDecimal qty = (java.math.BigDecimal) em.createNativeQuery(
                "SELECT quantity FROM account_subscription WHERE account_id = :a")
                .setParameter("a", accId).getSingleResult();
        assertThat(qty).isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("create() tolak account_no berganda")
    void rejectsDuplicateNo() {
        var req = new AccountController.SaveAccountRequest(
                "ACC-DUP", "Pertama", null, null, null, null,
                null, null, null, null,
                null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, java.util.List.of());
        assertThat(controller.create(req).getStatusCode().is2xxSuccessful()).isTrue();

        // Kedua dengan no sama -> ditolak
        var dup = controller.create(req);
        assertThat(dup.getStatusCode().is4xxClientError()).isTrue();
    }

    @Test
    @DisplayName("update() ubah field + status INACTIVE, account_no/opening kekal")
    void updatesAccount() {
        // Cipta akaun dulu
        var create = new AccountController.SaveAccountRequest(
                "EDIT-1", "Nama Asal", null, "MONTHLY", null, null,
                null, null, null, null,
                null, null, null, null, null, null, null,
                "Billto Asal", null, null, null, null, null, null, null, null, null,
                null, null, java.math.BigDecimal.valueOf(500), null, null, java.util.List.of());
        Long accId = (Long) ((java.util.Map<?,?>) controller.create(create).getBody()).get("id");

        // Edit: tukar nama + status INACTIVE
        var edit = new AccountController.EditAccountRequest(
                "Nama Baru", null, "INACTIVE", "MONTHLY", null,
                null, null, "catatan",
                null, null, null, null, null, null, null,
                "Billto Baru", null, null, null, null, null, null, null, null, null, null,
                java.util.List.of());
        var resp = controller.update(accId, edit);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();

        Object[] row = (Object[]) em.createNativeQuery("""
                SELECT account_name, status, billto_name, opening_amount, account_no
                FROM account WHERE id = :id
                """).setParameter("id", accId).getSingleResult();
        assertThat(row[0]).isEqualTo("Nama Baru");
        assertThat(row[1]).isEqualTo("INACTIVE");
        assertThat(row[2]).isEqualTo("Billto Baru");
        assertThat(((Number) row[3]).intValue()).isEqualTo(500);   // opening KEKAL
        assertThat(row[4]).isEqualTo("EDIT-1");                     // account_no KEKAL
    }

    @Test
    @DisplayName("update() delete subscription -> status ENDED (row kekal)")
    void endsSubscriptionOnDelete() {
        // Produk + akaun + subscription
        em.createNativeQuery("""
            INSERT INTO product (sp_code, code, name, charge_frequency, unit_rate,
                                 main_product, mandatory, prorated, late_penalty, status,
                                 created_at, updated_at, version)
            VALUES ('SPX', 'EP1', 'Prod Edit', 'MONTHLY', 100.00, 0,0,0,0,'ACTIVE',NOW(),NOW(),0)
            """).executeUpdate();
        Long prodId = ((Number) em.createNativeQuery(
                "SELECT id FROM product WHERE sp_code='SPX' AND code='EP1'").getSingleResult()).longValue();

        var create = new AccountController.SaveAccountRequest(
                "EDIT-2", "Akaun Sub", null, "MONTHLY", null, null,
                null,null,null,null, null,null,null,null,null,null,null,
                "Billto", null,null,null,null,null,null,null,null,null,
                null,null,null,null,null,
                java.util.List.of(new AccountController.SubLine(
                        prodId, java.math.BigDecimal.ONE,
                        java.time.LocalDate.of(2026,1,1), null, null)));
        Long accId = (Long) ((java.util.Map<?,?>) controller.create(create).getBody()).get("id");
        Long subId = ((Number) em.createNativeQuery(
                "SELECT id FROM account_subscription WHERE account_id = :a")
                .setParameter("a", accId).getSingleResult()).longValue();

        // Delete subscription (deleted=true)
        var edit = new AccountController.EditAccountRequest(
                "Akaun Sub", null, "ACTIVE", "MONTHLY", null,
                null,null,null, null,null,null,null,null,null,null,
                "Billto", null,null,null,null,null,null,null,null,null,null,
                java.util.List.of(new AccountController.EditSubLine(
                        subId, prodId, java.math.BigDecimal.ONE,
                        java.time.LocalDate.of(2026,1,1), null, null, true)));
        controller.update(accId, edit);

        // Row MASIH ada, status ENDED
        String status = (String) em.createNativeQuery(
                "SELECT status FROM account_subscription WHERE id = :id")
                .setParameter("id", subId).getSingleResult();
        assertThat(status).isEqualTo("ENDED");
    }
}
