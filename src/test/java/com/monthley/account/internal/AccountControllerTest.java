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
                "50000", "Wp Kuala Lumpur", "MY");

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
    @DisplayName("create() tolak account_no berganda")
    void rejectsDuplicateNo() {
        var req = new AccountController.SaveAccountRequest(
                "ACC-DUP", "Pertama", null, null, null, null,
                null, null, null, null,
                null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
        assertThat(controller.create(req).getStatusCode().is2xxSuccessful()).isTrue();

        // Kedua dengan no sama -> ditolak
        var dup = controller.create(req);
        assertThat(dup.getStatusCode().is4xxClientError()).isTrue();
    }
}
