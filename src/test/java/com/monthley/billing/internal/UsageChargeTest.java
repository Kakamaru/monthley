package com.monthley.billing.internal;

import com.monthley.ledger.api.GlAccounts;
import com.monthley.ledger.internal.ChartOfAccountSeeder;
import com.monthley.shared.GenMode;
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
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Caj berasaskan penggunaan (V58).
 *
 * Kerani memuat naik Excel dengan kuantiti atau amaun per akaun; baris
 * duduk sebagai PENDING sehingga bil dijana.
 *
 * Tiga sifat yang membezakannya daripada baris langganan:
 *   TIADA LANGGANAN diperlukan
 *   TEMPOH daripada baris, bukan daripada mod bil
 *   AMAUN muktamad, tidak diprorata
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class UsageChargeTest {

    private static final String SP = "SPUC";

    @Autowired InvoiceGenerationService billing;
    @Autowired ChartOfAccountSeeder seeder;
    @PersistenceContext EntityManager em;

    private long produk;

    @BeforeEach
    void seed() {
        em.createNativeQuery("""
                INSERT IGNORE INTO service_provider (sp_code, name, status, version)
                VALUES (:sp, 'SP Ujian Usage', 'ACTIVE', 0)
                """).setParameter("sp", SP).executeUpdate();
        seeder.seedFor(SP);

        String kod = "UC-" + System.nanoTime();
        em.createNativeQuery("""
                INSERT INTO product (sp_code, code, name, charge_frequency, unit_rate,
                                     main_product, mandatory, prorated, late_penalty,
                                     status, version)
                VALUES (:sp, :k, 'Sukaneka', 'PER_USE', 25.00, 0,0,0,0, 'ACTIVE', 0)
                """).setParameter("sp", SP).setParameter("k", kod).executeUpdate();
        produk = ((Number) em.createNativeQuery(
                "SELECT id FROM product WHERE sp_code=:sp AND code=:k")
                .setParameter("sp", SP).setParameter("k", kod)
                .getSingleResult()).longValue();

        TenantContext.set(SP);
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    private long akaun(String no) {
        em.createNativeQuery("""
                INSERT INTO account (sp_code, account_no, account_name,
                                     charge_frequency, status)
                VALUES (:sp, :no, :no, 'MONTHLY', 'ACTIVE')
                """).setParameter("sp", SP).setParameter("no", no).executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM account WHERE sp_code=:sp AND account_no=:no")
                .setParameter("sp", SP).setParameter("no", no)
                .getSingleResult()).longValue();
    }

    private long tempoh(int tahun, int bulan) {
        return ((Number) em.createNativeQuery(
                "SELECT period_id FROM fi_period WHERE charge_code='MO' "
                + "AND YEAR(start_dt)=:y AND MONTH(start_dt)=:m")
                .setParameter("y", tahun).setParameter("m", bulan)
                .getSingleResult()).longValue();
    }

    private void usage(long akaunId, long periodId, String qty, String amt) {
        em.createNativeQuery("""
                INSERT INTO account_usage_charge
                  (sp_code, account_id, product_id, period_id, quantity, amount, status)
                VALUES (:sp, :acc, :prod, :per, :q, :a, 'PENDING')
                """)
                .setParameter("sp", SP).setParameter("acc", akaunId)
                .setParameter("prod", produk).setParameter("per", periodId)
                .setParameter("q", new BigDecimal(qty))
                .setParameter("a", new BigDecimal(amt))
                .executeUpdate();
        em.flush();
    }

    private BillingContext ctx() {
        return BillingContext.of(SP, BigDecimal.ZERO, GlAccounts.ACCOUNTS_RECEIVABLE,
                GlAccounts.TAX_PAYABLE, GlAccounts.SERVICE_INCOME);
    }

    @Test
    @DisplayName("Akaun TANPA langganan: caj penggunaan tetap dibil")
    void tanpaLangganTetapDibil() {
        // Enjin melangkau akaun yang tiada langganan. Caj penggunaan
        // tidak memerlukan langganan — kerani memuat naik Excel untuk
        // mana-mana akaun di bawah SP.
        long acc = akaun("UC-NOSUB");
        usage(acc, tempoh(2026, 7), "19", "475.00");
        em.clear();

        var out = billing.generateForAccountDetailed(SP, acc, YearMonth.of(2026, 8),
                GenMode.CURRENT, ctx());

        assertThat(out.invoicesPosted()).isEqualTo(1);

        var r = (Object[]) em.createNativeQuery("""
                SELECT l.quantity, l.amount, l.once_only
                FROM   financial_document_line l
                JOIN   financial_document d ON d.id = l.document_id
                WHERE  d.account_id = :acc
                """).setParameter("acc", acc).getSingleResult();

        assertThat((BigDecimal) r[0]).isEqualByComparingTo("19");
        assertThat((BigDecimal) r[1]).isEqualByComparingTo("475.00");
        // onceOnly FALSE: idem_key untuk baris onceOnly menyekat ikut
        // (akaun, produk) TANPA tempoh, dan usage Julai akan digugurkan
        // kerana usage Jun sudah wujud untuk produk yang sama.
        //
        // tinyint(1) dipulangkan sebagai Boolean oleh Connector/J, bukan
        // Number — kali KETIGA corak ini muncul dalam projek.
        assertThat(r[2]).isEqualTo(false);
    }

    @Test
    @DisplayName("Dua tempoh, produk sama: DUA baris dalam larian yang sama")
    void duaTempohDuaBaris() {
        // Kerani memuat naik Jun dan Julai untuk produk yang sama. Tab
        // Per-use menunjukkan dua baris, dan penjanaan menghasilkan
        // kedua-duanya.
        long acc = akaun("UC-2P");
        usage(acc, tempoh(2026, 6), "10", "250.00");
        usage(acc, tempoh(2026, 7), "19", "475.00");
        em.clear();

        billing.generateForAccountDetailed(SP, acc, YearMonth.of(2026, 8),
                GenMode.CURRENT, ctx());
        em.flush();
        em.clear();

        assertThat(em.createNativeQuery("""
                SELECT COUNT(*) FROM financial_document_line l
                JOIN   financial_document d ON d.id = l.document_id
                WHERE  d.account_id = :acc
                """).setParameter("acc", acc).getSingleResult())
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("Selepas dibil: status INVOICED, tidak dibil lagi")
    void selepasDibilTidakBerulang() {
        // Larian kedua tidak boleh mengecaj perkara yang sama dua kali.
        long acc = akaun("UC-SEKALI");
        usage(acc, tempoh(2026, 7), "19", "475.00");
        em.clear();

        billing.generateForAccountDetailed(SP, acc, YearMonth.of(2026, 8),
                GenMode.CURRENT, ctx());
        em.flush();
        em.clear();

        var r = (Object[]) em.createNativeQuery(
                "SELECT status, document_id FROM account_usage_charge WHERE account_id = :acc")
                .setParameter("acc", acc).getSingleResult();
        assertThat(r[0]).isEqualTo("INVOICED");
        assertThat(r[1]).as("dokumen direkod untuk jejak audit").isNotNull();

        var out2 = billing.generateForAccountDetailed(SP, acc, YearMonth.of(2026, 9),
                GenMode.CURRENT, ctx());
        assertThat(out2.invoicesPosted()).isZero();
    }

    @Test
    @DisplayName("Catatan disimpan pada BARIS, bukan menggantikan nama produk")
    void catatanBerasingan() {
        // description dan remarks ialah dua perkara: nama produk, dan
        // penjelasan mengapa amaun itu. Menyimpan catatan sebagai
        // description bermakna satu lajur yang bermakna dua perkara
        // bergantung pada jenis baris — dan tiada apa dalam baris itu
        // memberitahu yang mana.
        long acc = akaun("UC-CATATAN");
        long per = tempoh(2026, 7);
        em.createNativeQuery("""
                INSERT INTO account_usage_charge
                  (sp_code, account_id, product_id, period_id,
                   quantity, amount, remarks, status)
                VALUES (:sp, :acc, :prod, :per, 2, 50.00,
                        'bacaan meter 1213', 'PENDING')
                """).setParameter("sp", SP).setParameter("acc", acc)
                .setParameter("prod", produk).setParameter("per", per)
                .executeUpdate();
        em.flush();
        em.clear();

        billing.generateForAccountDetailed(SP, acc, YearMonth.of(2026, 8),
                GenMode.CURRENT, ctx());
        em.flush();
        em.clear();

        var r = (Object[]) em.createNativeQuery("""
                SELECT l.description, l.remarks
                FROM   financial_document_line l
                JOIN   financial_document d ON d.id = l.document_id
                WHERE  d.account_id = :acc
                """).setParameter("acc", acc).getSingleResult();

        assertThat(r[0]).as("nama produk kekal").isEqualTo("Sukaneka");
        assertThat(r[1]).as("catatan dalam lajur sendiri")
                .isEqualTo("bacaan meter 1213");
    }

    @Test
    @DisplayName("Tempoh baris invois daripada CAJ, bukan daripada mod bil")
    void tempohDaripadaCaj() {
        // POSTPAID pada Ogos membil Julai untuk langganan. Caj
        // penggunaan yang ditanda JUN kekal Jun — tempohnya dipilih
        // semasa muat naik.
        long acc = akaun("UC-TEMPOH");
        long jun = tempoh(2026, 6);
        usage(acc, jun, "10", "250.00");
        em.clear();

        billing.generateForAccountDetailed(SP, acc, YearMonth.of(2026, 8),
                GenMode.POSTPAID, ctx());
        em.flush();
        em.clear();

        assertThat(em.createNativeQuery("""
                SELECT l.period_id FROM financial_document_line l
                JOIN   financial_document d ON d.id = l.document_id
                WHERE  d.account_id = :acc
                """).setParameter("acc", acc).getSingleResult())
                .isEqualTo(jun);
    }
}
