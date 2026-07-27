package com.monthley.account.internal;

import com.monthley.shared.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Satu akaun, satu produk, satu langganan HIDUP (CASE-007).
 *
 * Akaun 260 produksi berakhir dengan DUA langganan produk 197 dan
 * invoisnya mengecaj Julai 2026 dua kali. Penapis 'produk belum
 * dilanggan' wujud di frontend SAHAJA; backend menerima apa yang
 * dihantar. Peraturan yang hidup hanya dalam UI bukan peraturan.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SubscriptionOverlapGuardTest {

    @Autowired AccountController controller;
    @Autowired AccountSubscriptionRepository subscriptions;
    @Autowired JdbcClient jdbc;

    private String sp;
    private long acc;
    private long produk;

    @BeforeEach
    void seed() {
        sp = jdbc.sql("SELECT sp_code FROM service_provider ORDER BY sp_code LIMIT 1")
                .query(String.class).single();
        TenantContext.set(sp);

        String kod = "OVL-" + System.nanoTime();
        jdbc.sql("""
                INSERT INTO product (sp_code, code, name, charge_frequency, unit_rate,
                                     main_product, mandatory, prorated, late_penalty, status, version)
                VALUES (:sp, :k, 'Parking Ujian', 'MONTHLY', 50.00, 0,0,1,0,'ACTIVE',0)
                """).param("sp", sp).param("k", kod).update();
        produk = jdbc.sql("SELECT id FROM product WHERE sp_code=:sp AND code=:k")
                .param("sp", sp).param("k", kod).query(Long.class).single();

        String no = "OVL-ACC-" + System.nanoTime();
        jdbc.sql("""
                INSERT INTO account (sp_code, account_no, account_name, status)
                VALUES (:sp, :no, 'Ujian Tindihan', 'ACTIVE')
                """).param("sp", sp).param("no", no).update();
        acc = jdbc.sql("SELECT id FROM account WHERE sp_code=:sp AND account_no=:no")
                .param("sp", sp).param("no", no).query(Long.class).single();
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    private void langgan(LocalDate start, LocalDate end,
                         AccountSubscription.Status status) {
        var s = new AccountSubscription(sp, acc, produk, BigDecimal.ONE, start);
        if (end != null) s.setEndDate(end);
        s.setStatus(status);
        subscriptions.save(s);
    }

    private long bilAktif() {
        return jdbc.sql("""
                SELECT COUNT(*) FROM account_subscription
                WHERE account_id = :a AND product_id = :p AND status = 'ACTIVE'
                """).param("a", acc).param("p", produk).query(Long.class).single();
    }

    @Test
    @DisplayName("langganan ACTIVE menyekat produk yang sama ditambah lagi")
    void aktifMenyekat() {
        langgan(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 15),
                AccountSubscription.Status.ACTIVE);

        // 26 medan akaun (semua null = tiada perubahan) + senarai langganan.
        // Bentuk diambil daripada ralat kompiler, bukan daripada ingatan.
        var req = new AccountController.EditAccountRequest(
                "Ujian Tindihan",   // accountName ialah @NotBlank
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                java.util.List.of(new AccountController.EditSubLine(
                        null, produk, BigDecimal.ONE, LocalDate.of(2026, 7, 19),
                        null, null, false)));

        assertThatThrownBy(() -> controller.update(acc, req))
                .as("ini keadaan tepat yang menghasilkan INV000032")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sudah dilanggan");

        assertThat(bilAktif()).isEqualTo(1);
    }

    @Test
    @DisplayName("langganan ENDED TIDAK menyekat — pelanggan boleh melanggan semula")
    void endedTidakMenyekat() {
        langgan(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31),
                AccountSubscription.Status.ENDED);

        langgan(LocalDate.of(2026, 8, 1), null, AccountSubscription.Status.ACTIVE);

        // Sejarah kekal: baris ENDED tidak dipadam atau diguna semula.
        assertThat(bilAktif()).isEqualTo(1);
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM account_subscription
                WHERE account_id = :a AND product_id = :p
                """).param("a", acc).param("p", produk).query(Long.class).single())
                .isEqualTo(2);
    }
}
