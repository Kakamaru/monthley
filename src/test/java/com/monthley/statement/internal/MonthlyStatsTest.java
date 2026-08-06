package com.monthley.statement.internal;

import com.monthley.shared.TenantContext;
import com.monthley.statement.api.MonthlyStatsPort;
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
 * Statistik bulanan.
 *
 * Laporan ini menyentuh duit dan dibawa ke mesyuarat JMB — nombor yang
 * salah di sini menjadi keputusan yang salah.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MonthlyStatsTest {

    private static final String SP = "SPMS";

    @Autowired MonthlyStatsPort stats;
    @PersistenceContext EntityManager em;

    @BeforeEach
    void seed() {
        em.createNativeQuery("""
                INSERT IGNORE INTO service_provider (sp_code, name, status, version)
                VALUES (:sp, 'SP Ujian Statistik', 'ACTIVE', 0)
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

    private long dokumen(String no, String jenis, long akaunId,
                         String tarikh, String amaun) {
        em.createNativeQuery("""
                INSERT INTO financial_document
                  (sp_code, doc_no, doc_type, account_id, doc_date, amount,
                   tax_amount, status, title, version)
                VALUES (:sp, :no, :t, :acc, :dd, :amt, 0, 'ACTIVE', 'Ujian', 0)
                """).setParameter("sp", SP).setParameter("no", no)
                .setParameter("t", jenis).setParameter("acc", akaunId)
                .setParameter("dd", LocalDate.parse(tarikh))
                .setParameter("amt", new BigDecimal(amaun)).executeUpdate();
        em.flush();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM financial_document WHERE sp_code=:sp AND doc_no=:n")
                .setParameter("sp", SP).setParameter("n", no)
                .getSingleResult()).longValue();
    }

    /** Baris invois dengan tempoh liputan tertentu. */
    private long baris(long docId, long akaunId, String amaun,
                       String periodStart, String periodEnd) {
        em.createNativeQuery("""
                INSERT INTO financial_document_line
                  (document_id, account_id, description, quantity, unit_price,
                   proration_ratio, amount, tax_amount, active, once_only,
                   period_start, period_end)
                VALUES (:doc, :acc, 'Yuran', 1, :amt, 1, :amt, 0, 1, 0, :ps, :pe)
                """).setParameter("doc", docId).setParameter("acc", akaunId)
                .setParameter("amt", new BigDecimal(amaun))
                .setParameter("ps", LocalDate.parse(periodStart))
                .setParameter("pe", LocalDate.parse(periodEnd)).executeUpdate();
        em.flush();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM financial_document_line WHERE document_id=:d")
                .setParameter("d", docId).getSingleResult()).longValue();
    }

    private void alokasi(long akaunId, long resitId, long invoisId,
                         long barisId, String amaun) {
        em.createNativeQuery("""
                INSERT INTO fi_allocation
                  (sp_code, account_id, credit_document_id, debit_document_id,
                   debit_document_line_id, amount, status, version)
                VALUES (:sp, :acc, :cr, :dr, :line, :amt, 'ACTIVE', 0)
                """).setParameter("sp", SP).setParameter("acc", akaunId)
                .setParameter("cr", resitId).setParameter("dr", invoisId)
                .setParameter("line", barisId)
                .setParameter("amt", new BigDecimal(amaun)).executeUpdate();
        em.flush();
    }

    private MonthlyStatsPort.Stats jana(int tahun, int bulan) {
        em.flush();
        em.clear();
        return stats.monthly(SP, tahun, bulan);
    }

    @Test
    @DisplayName("Kutipan DIPECAH: tempoh ini lawan tunggakan lama")
    void kutipanDipecah() {
        // Laporan legacy meletakkan jumlah invois di sebelah jumlah resit
        // tanpa penjelasan, dan kutipan yang melebihi bil kelihatan
        // seperti ralat. Pecahan ini yang membetulkannya.
        long acc = akaun("MS-1");

        long invJun = dokumen("MS-IJUN", "INVOICE", acc, "2026-06-01", "300.00");
        long lnJun = baris(invJun, acc, "300.00", "2026-06-01", "2026-06-30");

        long invOgos = dokumen("MS-IOGO", "INVOICE", acc, "2026-08-01", "500.00");
        long lnOgos = baris(invOgos, acc, "500.00", "2026-08-01", "2026-08-31");

        // Satu resit Ogos membayar KEDUA-DUA tempoh.
        long rcp = dokumen("MS-R1", "RECEIPT", acc, "2026-08-10", "800.00");
        alokasi(acc, rcp, invOgos, lnOgos, "500.00");
        alokasi(acc, rcp, invJun, lnJun, "300.00");

        var s = jana(2026, 8);

        assertThat(s.collected()).as("jumlah resit").isEqualByComparingTo("800.00");
        assertThat(s.collectedThisPeriod()).as("untuk Ogos").isEqualByComparingTo("500.00");
        assertThat(s.collectedArrears()).as("untuk Jun").isEqualByComparingTo("300.00");
    }

    @Test
    @DisplayName("Kadar kutipan: bahagian tempoh ini dibahagi jumlah bil")
    void kadarKutipan() {
        long acc = akaun("MS-2");
        long inv = dokumen("MS-I2", "INVOICE", acc, "2026-08-01", "1000.00");
        long ln = baris(inv, acc, "1000.00", "2026-08-01", "2026-08-31");
        long rcp = dokumen("MS-R2", "RECEIPT", acc, "2026-08-15", "250.00");
        alokasi(acc, rcp, inv, ln, "250.00");

        assertThat(jana(2026, 8).collectionRate()).isEqualByComparingTo("25.0");
    }

    @Test
    @DisplayName("Trend harian: TERKUMPUL, dan hari kosong tetap muncul")
    void trendHarianTerkumpul() {
        // Jurang dalam garis kelihatan seperti data yang rosak, jadi hari
        // tanpa kutipan mesti muncul sebagai sifar.
        long acc = akaun("MS-3");
        dokumen("MS-R3a", "RECEIPT", acc, "2026-08-02", "100.00");
        dokumen("MS-R3b", "RECEIPT", acc, "2026-08-05", "50.00");

        var s = jana(2026, 8);

        assertThat(s.daily()).hasSize(31);
        assertThat(s.daily().get(0).cumulative()).as("1 Ogos").isEqualByComparingTo("0");
        assertThat(s.daily().get(1).cumulative()).as("2 Ogos").isEqualByComparingTo("100.00");
        assertThat(s.daily().get(2).cumulative())
                .as("3 Ogos: tiada kutipan, terkumpul kekal").isEqualByComparingTo("100.00");
        assertThat(s.daily().get(4).cumulative()).as("5 Ogos").isEqualByComparingTo("150.00");
        assertThat(s.daily().get(30).cumulative()).as("31 Ogos").isEqualByComparingTo("150.00");
    }

    @Test
    @DisplayName("Hari tertinggi dan bilangan transaksi")
    void ringkasanHarian() {
        long acc = akaun("MS-4");
        dokumen("MS-R4a", "RECEIPT", acc, "2026-08-03", "80.00");
        dokumen("MS-R4b", "RECEIPT", acc, "2026-08-09", "500.00");
        dokumen("MS-R4c", "RECEIPT", acc, "2026-08-09", "120.00");

        var d = jana(2026, 8).dailySummary();

        assertThat(d.transactions()).isEqualTo(3);
        assertThat(d.busiestDay()).as("9 Ogos: 500 + 120").isEqualTo(9);
        assertThat(d.busiestAmount()).isEqualByComparingTo("620.00");
        assertThat(d.total()).isEqualByComparingTo("700.00");
    }

    @Test
    @DisplayName("Bulan SEBELUMNYA dikira untuk delta")
    void bulanSebelumnya() {
        long acc = akaun("MS-5");
        dokumen("MS-I5", "INVOICE", acc, "2026-07-01", "400.00");
        dokumen("MS-I6", "INVOICE", acc, "2026-08-01", "600.00");

        var s = jana(2026, 8);

        assertThat(s.billed()).isEqualByComparingTo("600.00");
        assertThat(s.billedPrevious()).as("Julai").isEqualByComparingTo("400.00");
    }

    @Test
    @DisplayName("Dokumen BATAL dikecualikan")
    void batalDikecualikan() {
        long acc = akaun("MS-6");
        dokumen("MS-I7", "INVOICE", acc, "2026-08-01", "300.00");
        em.createNativeQuery("""
                INSERT INTO financial_document
                  (sp_code, doc_no, doc_type, account_id, doc_date, amount,
                   tax_amount, status, title, version)
                VALUES (:sp, 'MS-I8', 'INVOICE', :acc, '2026-08-02', 999.00, 0,
                        'CANCELLED', 'Batal', 0)
                """).setParameter("sp", SP).setParameter("acc", acc).executeUpdate();

        assertThat(jana(2026, 8).billed()).isEqualByComparingTo("300.00");
    }
}
