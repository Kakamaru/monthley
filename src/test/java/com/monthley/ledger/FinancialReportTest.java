package com.monthley.ledger;

import com.monthley.ledger.api.GlAccounts;
import com.monthley.ledger.api.LedgerPort;
import com.monthley.ledger.api.PostingLine;
import com.monthley.ledger.api.PostingRequest;
import com.monthley.ledger.api.SourceType;
import com.monthley.ledger.internal.ChartOfAccountSeeder;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Imbangan Duga dan Untung Rugi.
 *
 * Imbangan Duga bukan laporan untuk membuat keputusan — ia UJIAN. Kalau
 * dua jumlah tidak sama, pembukuan rosak dan setiap laporan lain
 * dibina atas nombor yang salah.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class FinancialReportTest {

    private static final String SP = "SPFR";

    @Autowired ChartOfAccountSeeder seeder;
    @Autowired LedgerPort ledger;
    @PersistenceContext EntityManager em;

    @BeforeEach
    void seed() {
        em.createNativeQuery("""
                INSERT IGNORE INTO service_provider (sp_code, name, status, version)
                VALUES (:sp, 'SP Ujian Laporan', 'ACTIVE', 0)
                """).setParameter("sp", SP).executeUpdate();
        seeder.seedFor(SP);
        TenantContext.set(SP);
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    /** Invois: AR debit, hasil kredit. */
    private void invois(String amaun, LocalDate tarikh) {
        ledger.post(new PostingRequest(SP, tarikh, SourceType.INVOICE, null,
                "Invois ujian",
                List.of(new PostingLine(GlAccounts.ACCOUNTS_RECEIVABLE,
                                new BigDecimal(amaun), BigDecimal.ZERO, null, null, null),
                        new PostingLine(GlAccounts.SERVICE_INCOME,
                                BigDecimal.ZERO, new BigDecimal(amaun), null, null, null)),
                null));
        em.flush();
    }

    private Object[] jumlah(LocalDate had) {
        em.flush();
        em.clear();
        return (Object[]) em.createNativeQuery("""
                SELECT COALESCE(SUM(CASE WHEN t.baki > 0 THEN t.baki ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN t.baki < 0 THEN -t.baki ELSE 0 END), 0)
                FROM (
                  SELECT SUM(l.debit_amount - l.credit_amount) AS baki
                  FROM   journal_line  l
                  JOIN   journal_entry e ON e.id = l.journal_entry_id
                  WHERE  e.sp_code = :sp AND e.status <> 'DRAFT'
                    AND  e.entry_date <= :had
                  GROUP  BY l.gl_account_id
                ) t
                """).setParameter("sp", SP).setParameter("had", had).getSingleResult();
    }

    @Test
    @DisplayName("Imbangan Duga SEIMBANG selepas beberapa posting")
    void imbanganDugaSeimbang() {
        invois("80.00", LocalDate.of(2026, 7, 1));
        invois("45.50", LocalDate.of(2026, 7, 15));

        Object[] j = jumlah(LocalDate.of(2026, 12, 31));

        assertThat(new BigDecimal(j[0].toString()))
                .isEqualByComparingTo(new BigDecimal(j[1].toString()));
        assertThat(new BigDecimal(j[0].toString())).isEqualByComparingTo("125.50");
    }

    @Test
    @DisplayName("Entri DIBALIKKAN kekal dikira — kesan bersih SIFAR")
    void entriDibalikkanKekalDikira() {
        // Membatalkan invois menandakan entri asal REVERSED dan mengepos
        // entri CANCELLATION yang membalikkannya. Kedua-duanya mesti
        // dikira.
        //
        // Menapis status = 'POSTED' membuang entri asal sambil
        // MENGEKALKAN pembalikannya — kesan bersih tolak sekali, bukan
        // sifar. Draf pertama laporan ini melakukan tepat itu, dan AR
        // terkurang RM391.50 daripada sub-lejar.
        invois("80.00", LocalDate.of(2026, 7, 1));

        long entri = ((Number) em.createNativeQuery(
                "SELECT id FROM journal_entry WHERE sp_code = :sp ORDER BY id DESC LIMIT 1")
                .setParameter("sp", SP).getSingleResult()).longValue();
        ledger.reverse(entri, "Batal ujian");
        em.flush();

        Object[] j = jumlah(LocalDate.of(2026, 12, 31));

        assertThat(new BigDecimal(j[0].toString()))
                .as("debit sifar selepas pembalikan")
                .isEqualByComparingTo("0");
        assertThat(new BigDecimal(j[1].toString()))
                .as("kredit sifar selepas pembalikan")
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("Imbangan Duga menghormati tarikh — posting selepas tarikh dikecualikan")
    void hormatiTarikh() {
        invois("80.00", LocalDate.of(2026, 7, 1));
        invois("45.50", LocalDate.of(2026, 9, 1));

        Object[] j = jumlah(LocalDate.of(2026, 7, 31));

        assertThat(new BigDecimal(j[0].toString()))
                .as("September dikecualikan").isEqualByComparingTo("80.00");
    }
}
