package com.monthley.ledger.internal;

import com.monthley.ledger.api.GlAccounts;
import com.monthley.ledger.api.LedgerPort;
import com.monthley.ledger.api.PostingLine;
import com.monthley.ledger.api.PostingRequest;
import com.monthley.ledger.api.SourceType;
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
    @Autowired FinancialReportController reports;
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

        // Controller menyemak peranan. Query mentah dalam draf pertama
        // melangkau semakan itu sepenuhnya — satu lagi sebab ujian mesti
        // memanggil laluan sebenar.
        org.springframework.security.core.context.SecurityContextHolder
                .getContext().setAuthentication(
                new org.springframework.security.authentication
                        .UsernamePasswordAuthenticationToken("admin", "n/a",
                        java.util.List.of(
                                new org.springframework.security.core.authority
                                        .SimpleGrantedAuthority("SP_" + SP + "_SP_ADMIN"))));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

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

    // ── Senarai Kutipan ──────────────────────────────────────────────

    /**
     * Resit yang melangsaikan satu baris invois bertempoh tertentu.
     *
     * Monthly Basis menapis mengikut tempoh INVOIS, bukan tarikh
     * bayaran — jadi ujian memerlukan kedua-duanya berbeza.
     */
    private void resitDenganAlokasi(String docNo, LocalDate tarikhBayar,
                                    LocalDate tempohInvois, String amaun,
                                    String kaedah, String produk) {
        long akaun = ((Number) em.createNativeQuery(
                "SELECT id FROM account WHERE sp_code=:sp AND account_no='FR-1'")
                .setParameter("sp", SP).getSingleResult()).longValue();

        // Invois yang akan dilangsaikan
        em.createNativeQuery("""
                INSERT INTO financial_document
                  (sp_code, doc_no, doc_type, account_id, doc_date, amount,
                   tax_amount, status, title, version)
                VALUES (:sp, :inv, 'INVOICE', :acc, :td, :amt, 0, 'ACTIVE', 'Invois', 0)
                """).setParameter("sp", SP).setParameter("inv", docNo + "-INV")
                .setParameter("acc", akaun).setParameter("td", tempohInvois)
                .setParameter("amt", new BigDecimal(amaun)).executeUpdate();
        em.flush();
        long invId = ((Number) em.createNativeQuery(
                "SELECT id FROM financial_document WHERE sp_code=:sp AND doc_no=:n")
                .setParameter("sp", SP).setParameter("n", docNo + "-INV")
                .getSingleResult()).longValue();

        em.createNativeQuery("""
                INSERT INTO financial_document_line
                  (document_id, account_id, description, quantity, unit_price,
                   proration_ratio, amount, tax_amount, active, once_only,
                   period_start, period_end)
                VALUES (:doc, :acc, :prod, 1, :amt, 1, :amt, 0, 1, 0, :ps, :pe)
                """).setParameter("doc", invId).setParameter("acc", akaun)
                .setParameter("prod", produk).setParameter("amt", new BigDecimal(amaun))
                .setParameter("ps", tempohInvois)
                .setParameter("pe", tempohInvois.withDayOfMonth(
                        tempohInvois.lengthOfMonth())).executeUpdate();
        em.flush();
        long lineId = ((Number) em.createNativeQuery(
                "SELECT id FROM financial_document_line WHERE document_id = :d")
                .setParameter("d", invId).getSingleResult()).longValue();

        // Resit
        em.createNativeQuery("""
                INSERT INTO financial_document
                  (sp_code, doc_no, doc_type, account_id, doc_date, amount,
                   tax_amount, status, title, version)
                VALUES (:sp, :no, 'RECEIPT', :acc, :dd, :amt, 0,
                        'ACTIVE', 'Resit bayaran', 0)
                """).setParameter("sp", SP).setParameter("no", docNo)
                .setParameter("acc", akaun).setParameter("dd", tarikhBayar)
                .setParameter("amt", new BigDecimal(amaun)).executeUpdate();
        em.flush();
        long rcpId = ((Number) em.createNativeQuery(
                "SELECT id FROM financial_document WHERE sp_code=:sp AND doc_no=:n")
                .setParameter("sp", SP).setParameter("n", docNo)
                .getSingleResult()).longValue();

        em.createNativeQuery("""
                INSERT INTO payment
                  (sp_code, receipt_document_id, payer_account_id, payment_date,
                   amount, allocated_amount, deposit_amount, method, status, version)
                VALUES (:sp, :rcp, :acc, :dd, :amt, :amt, 0, :m, 'ACTIVE', 0)
                """).setParameter("sp", SP).setParameter("rcp", rcpId)
                .setParameter("acc", akaun).setParameter("dd", tarikhBayar)
                .setParameter("amt", new BigDecimal(amaun)).setParameter("m", kaedah)
                .executeUpdate();

        em.createNativeQuery("""
                INSERT INTO fi_allocation
                  (sp_code, account_id, credit_document_id, debit_document_id,
                   debit_document_line_id, amount, status, version)
                VALUES (:sp, :acc, :cr, :dr, :line, :amt, 'ACTIVE', 0)
                """).setParameter("sp", SP).setParameter("acc", akaun)
                .setParameter("cr", rcpId)
                .setParameter("dr", invId).setParameter("line", lineId)
                .setParameter("amt", new BigDecimal(amaun)).executeUpdate();
        em.flush();
    }

    private void akaunUji() {
        em.createNativeQuery("""
                INSERT IGNORE INTO account (sp_code, account_no, account_name, status)
                VALUES (:sp, 'FR-1', 'FR-1', 'ACTIVE')
                """).setParameter("sp", SP).executeUpdate();
        em.flush();
    }

    /**
     * Memanggil CONTROLLER, bukan menulis semula querynya.
     *
     * Draf pertama ujian ini menyalin SQL ke dalam fail ujian. Ia lulus,
     * dan ujian mutasi memerahkannya — tetapi ia menguji salinan itu,
     * bukan kod yang dijalankan. Controller boleh menyimpang tanpa satu
     * ujian pun berubah warna.
     */
    private FinancialReportController.Collection kutipan(
            LocalDate from, LocalDate to, boolean byProduct, boolean monthly) {
        em.flush();
        em.clear();
        return reports.collection(from, to, byProduct, monthly, null, null, null);
    }

    @Test
    @DisplayName("Monthly Basis: bayaran kepada TUNGGAKAN dikecualikan")
    void monthlyBasisKecualikanTunggakan() {
        // Daripada yang dikutip bulan ini, berapa untuk bil bulan ini dan
        // berapa untuk tunggakan lama. Menapis mengikut tarikh BAYARAN
        // tidak boleh menjawabnya — kedua-duanya dibayar bulan ini.
        akaunUji();
        resitDenganAlokasi("FR-R1", LocalDate.of(2026, 8, 5),
                LocalDate.of(2026, 8, 1), "150.00", "CASH", "Yuran Ogos");
        resitDenganAlokasi("FR-R2", LocalDate.of(2026, 8, 6),
                LocalDate.of(2026, 6, 1), "80.00", "CASH", "Yuran Jun");

        var semua = kutipan(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), true, false);
        var bulan = kutipan(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), true, true);

        assertThat(semua.rows()).as("All: kedua-dua bayaran").hasSize(2);
        assertThat(semua.total()).isEqualByComparingTo("230.00");

        assertThat(bulan.rows()).as("Monthly Basis: Ogos sahaja").hasSize(1);
        assertThat(bulan.total()).isEqualByComparingTo("150.00");
    }

    @Test
    @DisplayName("Nota kredit BUKAN kutipan — dikecualikan")
    void notaKreditDikecualikan() {
        // Nota kredit mengurangkan hutang tetapi tiada duit masuk.
        // Memasukkannya dalam laporan kutipan menjadikan jumlah tidak
        // sepadan dengan penyata bank.
        akaunUji();
        resitDenganAlokasi("FR-R3", LocalDate.of(2026, 8, 5),
                LocalDate.of(2026, 8, 1), "100.00", "CASH", "Yuran");

        long akaun = ((Number) em.createNativeQuery(
                "SELECT id FROM account WHERE sp_code=:sp AND account_no='FR-1'")
                .setParameter("sp", SP).getSingleResult()).longValue();
        // Nota kredit mesti mempunyai ALOKASI, kalau tidak ia tidak
        // muncul dalam account_allocation_match walau apa pun tapisan
        // doc_type — dan ujian membuktikan sifar.
        //
        // Draf pertama melangkau ini. Ujian lulus, dan mutasi yang
        // membenarkan CREDIT_NOTE kekal HIJAU: positif palsu.
        long invId = ((Number) em.createNativeQuery(
                "SELECT id FROM financial_document WHERE sp_code=:sp AND doc_no='FR-R3-INV'")
                .setParameter("sp", SP).getSingleResult()).longValue();
        long lineId = ((Number) em.createNativeQuery(
                "SELECT id FROM financial_document_line WHERE document_id = :d")
                .setParameter("d", invId).getSingleResult()).longValue();

        em.createNativeQuery("""
                INSERT INTO financial_document
                  (sp_code, doc_no, doc_type, account_id, doc_date, amount,
                   tax_amount, status, title, version)
                VALUES (:sp, 'FR-CN1', 'CREDIT_NOTE', :acc, '2026-08-07', 30.00, 0,
                        'ACTIVE', 'Nota kredit', 0)
                """).setParameter("sp", SP).setParameter("acc", akaun).executeUpdate();
        em.flush();
        long cnId = ((Number) em.createNativeQuery(
                "SELECT id FROM financial_document WHERE sp_code=:sp AND doc_no='FR-CN1'")
                .setParameter("sp", SP).getSingleResult()).longValue();

        em.createNativeQuery("""
                INSERT INTO fi_allocation
                  (sp_code, account_id, credit_document_id, debit_document_id,
                   debit_document_line_id, amount, status, version)
                VALUES (:sp, :acc, :cn, :inv, :line, 30.00, 'ACTIVE', 0)
                """).setParameter("sp", SP).setParameter("acc", akaun)
                .setParameter("cn", cnId).setParameter("inv", invId)
                .setParameter("line", lineId).executeUpdate();
        em.flush();

        assertThat(kutipan(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), true, false)
                .rows()).as("resit sahaja").hasSize(1);
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
