package com.monthley.billing.internal;

import com.monthley.document.api.DocumentPort;
import com.monthley.ledger.api.GlAccounts;
import com.monthley.ledger.internal.ChartOfAccountSeeder;
import com.monthley.shared.GenMode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Split ikut PRODUK dan TEMPOH (ADR 0011, meminda ADR 0008).
 *
 * ADR 0008 memecah ikut produk sahaja. Apabila beberapa tempoh dijana
 * dalam satu larian — akaun tanpa start_charging mengejar seluruh ufuk —
 * dua belas bulan berakhir dalam SATU dokumen.
 *
 * Itu menjadikan pembatalan separa mustahil. Apabila kadar berubah
 * selepas AGM pertengahan tahun, SP mesti boleh membatalkan Ogos hingga
 * Disember tanpa menyentuh Januari hingga Julai yang mungkin sudah
 * dibayar.
 *
 * Legacy sudah berbuat demikian: Pandan Mewah 11/01/2020 09:24:47
 * menghasilkan EMPAT invois dalam satu cap masa (2 produk x 2 bulan).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SplitByPeriodTest {

    private static final String SP = "SPPRD";

    @Autowired InvoiceGenerationService billing;
    @Autowired DocumentPort documents;
    @Autowired ChartOfAccountSeeder seeder;
    @PersistenceContext EntityManager em;

    private Long accountId;

    @BeforeEach
    void setup() {
        em.createNativeQuery("""
            INSERT IGNORE INTO service_provider (sp_code, name, status, created_at, updated_at, version)
            VALUES (:sp, 'SP Split Tempoh', 'ACTIVE', NOW(), NOW(), 0)
            """).setParameter("sp", SP).executeUpdate();
        seeder.seedFor(SP);

        em.createNativeQuery("""
            INSERT INTO product (sp_code, code, name, charge_frequency, unit_rate,
                                 main_product, mandatory, prorated, late_penalty, status,
                                 created_at, updated_at, version)
            VALUES (:sp, 'PRK', 'Parking', 'MONTHLY', 50.00, 0,0,0,0,'ACTIVE', NOW(), NOW(), 0)
            """).setParameter("sp", SP).executeUpdate();
        Long prod = ((Number) em.createNativeQuery(
                "SELECT id FROM product WHERE sp_code=:sp AND code='PRK'")
                .setParameter("sp", SP).getSingleResult()).longValue();

        // Akaun YEAR + produk MONTHLY = dua belas caj dalam satu larian.
        //
        // account.charge_frequency menentukan KITARAN ASAS; product.charge_frequency
        // menentukan caj DALAM kitaran itu. Postpaid + YEAR -> kitaran asas ialah
        // tahun penuh sebelumnya, dan produk bulanan menghasilkan satu caj setiap
        // bulan dalam tahun tersebut.
        //
        // Inilah keadaan sebenar M04: dua belas baris parking dalam satu dokumen.
        // start_date NULL bermakna tiada had bawah, jadi kesemua dua belas bulan
        // dicaj.
        em.createNativeQuery("""
            INSERT INTO account (sp_code, account_no, account_name, charge_frequency,
                                 start_date, status, cached_balance, created_at, updated_at, version)
            VALUES (:sp, 'PACC', 'Payer Tempoh', 'YEAR', NULL, 'ACTIVE', 0, NOW(), NOW(), 0)
            """).setParameter("sp", SP).executeUpdate();
        accountId = ((Number) em.createNativeQuery(
                "SELECT id FROM account WHERE sp_code=:sp AND account_no='PACC'")
                .setParameter("sp", SP).getSingleResult()).longValue();

        em.createNativeQuery("""
            INSERT INTO account_subscription (sp_code, account_id, product_id, quantity,
                                              start_date, status, created_at, updated_at, version)
            VALUES (:sp, :a, :p, 1, NULL, 'ACTIVE', NOW(), NOW(), 0)
            """).setParameter("sp", SP).setParameter("a", accountId)
                .setParameter("p", prod).executeUpdate();
    }

    private BillingContext ctx(boolean split) {
        return new BillingContext(SP, BigDecimal.ZERO, null, true, 14, Set.of(),
                GlAccounts.ACCOUNTS_RECEIVABLE, GlAccounts.TAX_PAYABLE,
                GlAccounts.SERVICE_INCOME, split);
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> dokumen() {
        return em.createNativeQuery("""
                SELECT d.id, d.doc_no, d.period_id, d.status,
                       (SELECT COUNT(*) FROM financial_document_line l
                        WHERE l.document_id = d.id AND l.active = 1) AS bil_baris
                FROM   financial_document d
                WHERE  d.sp_code = :sp AND d.doc_type = 'INVOICE'
                ORDER  BY d.period_id, d.id
                """).setParameter("sp", SP).getResultList();
    }

    @Test
    @DisplayName("split = 1: SATU dokumen per tempoh, bukan satu untuk semua")
    void splitPecahIkutTempoh() {
        int posted = billing.generateForSp(SP, YearMonth.of(2026, 3),
                GenMode.CURRENT, ctx(true));
        em.flush();

        var docs = dokumen();

        assertThat(docs.size())
                .as("beberapa tempoh dijana; setiap satu dokumen sendiri")
                .isGreaterThan(1)
                .isEqualTo(posted);

        assertThat(docs).allSatisfy(d ->
                assertThat(((Number) d[4]).intValue())
                        .as("satu produk satu tempoh = satu baris")
                        .isEqualTo(1));

        assertThat(docs).extracting(d -> d[2])
                .as("period_id dokumen mesti UNIK — jika semua bertanda tempoh "
                    + "larian yang sama, senarai tidak boleh dibezakan")
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("split = 0: SATU dokumen untuk seluruh larian — had yang diterima")
    void tanpaSplitSatuDokumen() {
        int posted = billing.generateForSp(SP, YearMonth.of(2026, 3),
                GenMode.CURRENT, ctx(false));
        em.flush();

        assertThat(posted).isEqualTo(1);
        assertThat(dokumen()).hasSize(1);
        assertThat(((Number) dokumen().get(0)[4]).intValue())
                .as("semua tempoh sebagai baris dalam satu dokumen")
                .isGreaterThan(1);
    }

    @Test
    @DisplayName("SEBAB perubahan ini: batal satu tempoh, yang lain tidak tersentuh")
    void batalSatuTempohSahaja() {
        billing.generateForSp(SP, YearMonth.of(2026, 3), GenMode.CURRENT, ctx(true));
        em.flush();

        var docs = dokumen();
        assertThat(docs.size()).isGreaterThan(2);

        // Kadar berubah selepas AGM: batalkan tempoh terakhir sahaja.
        Long sasaran = ((Number) docs.get(docs.size() - 1)[0]).longValue();
        documents.cancelDocument(sasaran);
        em.flush();
        em.clear();

        var selepas = dokumen();
        long dibatalkan = selepas.stream()
                .filter(d -> "CANCELLED".equals(String.valueOf(d[3]))).count();

        assertThat(dibatalkan)
                .as("hanya tempoh sasaran dibatalkan")
                .isEqualTo(1);
        assertThat(selepas.size() - dibatalkan)
                .as("tempoh lain kekal aktif — inilah sebab pecahan tempoh wujud")
                .isEqualTo(docs.size() - 1);
    }
}
