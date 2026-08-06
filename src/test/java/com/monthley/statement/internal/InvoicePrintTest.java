package com.monthley.statement.internal;

import com.monthley.shared.Access;
import com.monthley.shared.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cetak invois pukal — pemilihan invois.
 *
 * Dua peraturan mudah tersilap: tapisan mengikut tempoh LIPUTAN, dan
 * 'belum lunas sahaja'. Kesilapan di sini bermakna invois hilang
 * daripada cetakan, atau pelanggan menerima bil yang sudah dibayar.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class InvoicePrintTest {

    private static final String SP = "SPIP";

    @Autowired InvoicePrintController controller;
    @PersistenceContext EntityManager em;

    @BeforeEach
    void seed() {
        em.createNativeQuery("""
                INSERT IGNORE INTO service_provider (sp_code, name, status, version)
                VALUES (:sp, 'SP Ujian Cetak', 'ACTIVE', 0)
                """).setParameter("sp", SP).executeUpdate();
        TenantContext.set(SP);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "n/a",
                        List.of(new SimpleGrantedAuthority("SP_" + SP + "_SP_ADMIN"))));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

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

    /**
     * @param docDate      bila invois DIJANA
     * @param periodStart  tempoh yang DILIPUTINYA
     */
    private long invois(long akaunId, String no, String docDate,
                        String periodStart, String amaun) {
        em.createNativeQuery("""
                INSERT INTO financial_document
                  (sp_code, doc_no, doc_type, account_id, doc_date, due_date,
                   amount, tax_amount, status, title, version)
                VALUES (:sp, :no, 'INVOICE', :acc, :dd, :dd, :amt, 0,
                        'ACTIVE', 'Invois', 0)
                """).setParameter("sp", SP).setParameter("no", no)
                .setParameter("acc", akaunId)
                .setParameter("dd", LocalDate.parse(docDate))
                .setParameter("amt", new BigDecimal(amaun)).executeUpdate();
        em.flush();

        long id = ((Number) em.createNativeQuery(
                "SELECT id FROM financial_document WHERE sp_code=:sp AND doc_no=:n")
                .setParameter("sp", SP).setParameter("n", no)
                .getSingleResult()).longValue();

        LocalDate ps = LocalDate.parse(periodStart);
        em.createNativeQuery("""
                INSERT INTO financial_document_line
                  (document_id, account_id, description, quantity, unit_price,
                   proration_ratio, amount, tax_amount, active, once_only,
                   period_start, period_end)
                VALUES (:doc, :acc, 'Yuran', 1, :amt, 1, :amt, 0, 1, 0, :ps, :pe)
                """).setParameter("doc", id).setParameter("acc", akaunId)
                .setParameter("amt", new BigDecimal(amaun))
                .setParameter("ps", ps)
                .setParameter("pe", ps.withDayOfMonth(ps.lengthOfMonth()))
                .executeUpdate();
        em.flush();
        return id;
    }

    private void bayar(long akaunId, long invoisId, String amaun) {
        em.createNativeQuery("""
                INSERT INTO financial_document
                  (sp_code, doc_no, doc_type, account_id, doc_date, amount,
                   tax_amount, status, title, version)
                VALUES (:sp, :no, 'RECEIPT', :acc, '2026-08-20', :amt, 0,
                        'ACTIVE', 'Resit', 0)
                """).setParameter("sp", SP).setParameter("no", "IP-R" + invoisId)
                .setParameter("acc", akaunId)
                .setParameter("amt", new BigDecimal(amaun)).executeUpdate();
        em.flush();
        long rcp = ((Number) em.createNativeQuery(
                "SELECT id FROM financial_document WHERE sp_code=:sp AND doc_no=:n")
                .setParameter("sp", SP).setParameter("n", "IP-R" + invoisId)
                .getSingleResult()).longValue();

        em.createNativeQuery("""
                INSERT INTO fi_allocation
                  (sp_code, account_id, credit_document_id, debit_document_id,
                   amount, status, version)
                VALUES (:sp, :acc, :cr, :dr, :amt, 'ACTIVE', 0)
                """).setParameter("sp", SP).setParameter("acc", akaunId)
                .setParameter("cr", rcp).setParameter("dr", invoisId)
                .setParameter("amt", new BigDecimal(amaun)).executeUpdate();
        em.flush();
    }

    private InvoicePrintController.Preview senarai(int tahun, int bulan,
                                                   boolean belumLunas) {
        em.flush();
        em.clear();
        return controller.senarai(tahun, bulan, null, belumLunas);
    }

    @Test
    @DisplayName("Tapis mengikut TEMPOH LIPUTAN, bukan tarikh dokumen")
    void tapisIkutTempohLiputan() {
        // Invois yang dijana pada Ogos boleh meliputi Julai. SP yang
        // mencetak 'bil Julai' mahukan yang MELIPUTI Julai, bukan yang
        // dijana pada Julai.
        long acc = akaun("IP-1");
        invois(acc, "IP-A", "2026-08-05", "2026-07-01", "100.00");  // liputan Julai
        invois(acc, "IP-B", "2026-08-05", "2026-08-01", "200.00");  // liputan Ogos

        assertThat(senarai(2026, 7, false).rows())
                .as("Julai: yang MELIPUTI Julai")
                .extracting(InvoicePrintController.Row::docNo)
                .containsExactly("IP-A");

        assertThat(senarai(2026, 8, false).rows())
                .as("Ogos")
                .extracting(InvoicePrintController.Row::docNo)
                .containsExactly("IP-B");
    }

    @Test
    @DisplayName("Belum lunas sahaja: invois yang DIBAYAR PENUH dikecualikan")
    void belumLunasSahaja() {
        // Menghantar bil yang sudah dibayar kepada pelanggan menjejaskan
        // kepercayaan lebih daripada bil yang lewat.
        long acc = akaun("IP-2");
        long lunas = invois(acc, "IP-LUNAS", "2026-08-01", "2026-08-01", "100.00");
        invois(acc, "IP-BELUM", "2026-08-01", "2026-08-01", "150.00");
        bayar(acc, lunas, "100.00");

        assertThat(senarai(2026, 8, false).rows())
                .as("semua").hasSize(2);

        assertThat(senarai(2026, 8, true).rows())
                .as("belum lunas sahaja")
                .extracting(InvoicePrintController.Row::docNo)
                .containsExactly("IP-BELUM");
    }

    @Test
    @DisplayName("Bayaran SEBAHAGIAN masih dikira belum lunas")
    void bayaranSebahagian() {
        long acc = akaun("IP-3");
        long inv = invois(acc, "IP-SEP", "2026-08-01", "2026-08-01", "300.00");
        bayar(acc, inv, "100.00");

        assertThat(senarai(2026, 8, true).rows())
                .extracting(InvoicePrintController.Row::docNo)
                .containsExactly("IP-SEP");
    }

    @Test
    @DisplayName("Invois BATAL tidak pernah dicetak")
    void batalDikecualikan() {
        long acc = akaun("IP-4");
        invois(acc, "IP-OK", "2026-08-01", "2026-08-01", "100.00");
        long batal = invois(acc, "IP-BATAL", "2026-08-01", "2026-08-01", "999.00");
        em.createNativeQuery(
                "UPDATE financial_document SET status='CANCELLED' WHERE id=:id")
                .setParameter("id", batal).executeUpdate();
        em.flush();

        assertThat(senarai(2026, 8, false).rows())
                .extracting(InvoicePrintController.Row::docNo)
                .containsExactly("IP-OK");
    }

    @Test
    @DisplayName("Jumlah pratonton ialah hasil tambah baris")
    void jumlahSepadan() {
        // SP melihat jumlah sebelum menekan cetak; jumlah yang dikira
        // daripada set berbeza daripada baris yang dipaparkan ialah
        // percanggahan yang ditemui semasa mengaudit.
        long acc = akaun("IP-5");
        invois(acc, "IP-J1", "2026-08-01", "2026-08-01", "120.50");
        invois(acc, "IP-J2", "2026-08-01", "2026-08-01", "79.50");

        var v = senarai(2026, 8, false);
        BigDecimal hasilTambah = v.rows().stream()
                .map(InvoicePrintController.Row::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(v.total()).isEqualByComparingTo(hasilTambah);
        assertThat(v.total()).isEqualByComparingTo("200.00");
        assertThat(v.count()).isEqualTo(2);
    }
}
