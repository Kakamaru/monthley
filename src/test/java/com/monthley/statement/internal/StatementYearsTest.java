package com.monthley.statement.internal;

import com.monthley.shared.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tahun yang mempunyai transaksi bagi satu akaun.
 *
 * Dropdown yang menyenaraikan sepuluh tahun ke belakang memaksa SP
 * mencuba satu-satu untuk mencari yang ada data; ini menyenaraikan
 * hanya yang wujud.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StatementYearsTest {

    private static final String SP = "SPSY";

    @Autowired StatementController controller;
    @PersistenceContext EntityManager em;

    @BeforeEach
    void seed() {
        em.createNativeQuery("""
                INSERT IGNORE INTO service_provider (sp_code, name, status, version)
                VALUES (:sp, 'SP Ujian Tahun', 'ACTIVE', 0)
                """).setParameter("sp", SP).executeUpdate();
        TenantContext.set(SP);
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    private long akaun(String no) {
        em.createNativeQuery("""
                INSERT INTO account (sp_code, account_no, account_name, status)
                VALUES (:sp, :no, :no, 'ACTIVE')
                """).setParameter("sp", SP).setParameter("no", no).executeUpdate();
        em.flush();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM account WHERE sp_code=:sp AND account_no=:no")
                .setParameter("sp", SP).setParameter("no", no)
                .getSingleResult()).longValue();
    }

    private void invois(long akaunId, String no, String tarikh) {
        em.createNativeQuery("""
                INSERT INTO financial_document
                  (sp_code, doc_no, doc_type, account_id, doc_date, amount,
                   tax_amount, status, title, version)
                VALUES (:sp, :no, 'INVOICE', :acc, :dd, 100.00, 0,
                        'ACTIVE', 'Invois', 0)
                """).setParameter("sp", SP).setParameter("no", no)
                .setParameter("acc", akaunId)
                .setParameter("dd", LocalDate.parse(tarikh)).executeUpdate();
        em.flush();
    }

    private StatementController.TahunTersedia tahun(long akaunId) {
        em.flush();
        em.clear();
        return controller.years(akaunId);
    }

    @Test
    @DisplayName("Hanya tahun yang MEMPUNYAI transaksi, terbaharu dahulu")
    void tahunBerdataSahaja() {
        long acc = akaun("SY-1");
        invois(acc, "SY-A", "2024-03-01");
        invois(acc, "SY-B", "2026-08-01");
        invois(acc, "SY-C", "2026-09-01");   // tahun sama, tidak berganda

        var t = tahun(acc);

        assertThat(t.years())
                .as("2025 tiada transaksi, jadi tidak disenaraikan")
                .containsExactly(2026, 2024);
    }

    @Test
    @DisplayName("firstYear ialah tahun transaksi PERTAMA")
    void tahunPertama() {
        // 'Semua sejak mula' bermula pada transaksi pertama, bukan
        // tarikh sewenang-wenangnya.
        long acc = akaun("SY-2");
        invois(acc, "SY-D", "2026-01-01");
        invois(acc, "SY-E", "2022-06-15");

        assertThat(tahun(acc).firstYear()).isEqualTo(2022);
    }

    @Test
    @DisplayName("Akaun tanpa transaksi: senarai kosong, firstYear null")
    void akaunKosong() {
        long acc = akaun("SY-3");

        var t = tahun(acc);

        assertThat(t.years()).isEmpty();
        assertThat(t.firstYear()).isNull();
    }

    @Test
    @DisplayName("Akaun LAIN tidak menyumbang tahun")
    void akaunLainTerasing() {
        long a = akaun("SY-4");
        long b = akaun("SY-5");
        invois(a, "SY-F", "2020-01-01");
        invois(b, "SY-G", "2026-01-01");

        assertThat(tahun(a).years()).containsExactly(2020);
        assertThat(tahun(b).years()).containsExactly(2026);
    }
}
