package com.monthley.statement;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lejar SP (V60) — cermin penyata pelanggan.
 *
 * Invois MENURUNKAN baki SP: caj telah dikeluarkan tetapi belum
 * dikutip. Resit menaikkannya.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SpLedgerViewTest {

    private static final String SP = "SPLG";

    @PersistenceContext EntityManager em;

    private void seed() {
        em.createNativeQuery("""
                INSERT IGNORE INTO service_provider (sp_code, name, status, version)
                VALUES (:sp, 'SP Ujian Lejar', 'ACTIVE', 0)
                """).setParameter("sp", SP).executeUpdate();
        em.createNativeQuery("""
                INSERT INTO account (sp_code, account_no, account_name, status)
                VALUES (:sp, 'LG-1', 'LG-1', 'ACTIVE')
                """).setParameter("sp", SP).executeUpdate();
        em.flush();
    }

    private long akaun() {
        return ((Number) em.createNativeQuery(
                "SELECT id FROM account WHERE sp_code=:sp AND account_no='LG-1'")
                .setParameter("sp", SP).getSingleResult()).longValue();
    }

    private long invois(String no, String amaun, String status) {
        em.createNativeQuery("""
                INSERT INTO financial_document
                  (sp_code, doc_no, doc_type, account_id, doc_date, amount,
                   tax_amount, status, title, version)
                VALUES (:sp, :no, 'INVOICE', :acc, '2026-07-01', :amt, 0,
                        :st, 'Invois', 0)
                """).setParameter("sp", SP).setParameter("no", no)
                .setParameter("acc", akaun()).setParameter("amt", new BigDecimal(amaun))
                .setParameter("st", status).executeUpdate();
        em.flush();
        long id = ((Number) em.createNativeQuery(
                "SELECT id FROM financial_document WHERE sp_code=:sp AND doc_no=:no")
                .setParameter("sp", SP).setParameter("no", no)
                .getSingleResult()).longValue();
        em.createNativeQuery("""
                INSERT INTO financial_document_line
                  (document_id, account_id, description, quantity, unit_price,
                   proration_ratio, amount, tax_amount, active, once_only)
                VALUES (:doc, :acc, 'Yuran', 1, :amt, 1, :amt, 0, 1, 0)
                """).setParameter("doc", id).setParameter("acc", akaun())
                .setParameter("amt", new BigDecimal(amaun)).executeUpdate();
        em.flush();
        return id;
    }

    private BigDecimal jumlah() {
        em.flush();
        em.clear();
        Object v = em.createNativeQuery(
                "SELECT COALESCE(SUM(signed_amount),0) FROM sp_ledger_line WHERE sp_code = :sp")
                .setParameter("sp", SP).getSingleResult();
        return new BigDecimal(v.toString());
    }

    @Test
    @DisplayName("Invois MENURUNKAN baki SP — cermin penyata pelanggan")
    void invoisMenurunkanBakiSp() {
        seed();
        invois("LG-I1", "80.00", "ACTIVE");

        assertThat(jumlah()).isEqualByComparingTo("-80.00");
    }

    @Test
    @DisplayName("Dokumen BATAL tidak menggerakkan baki, tetapi barisnya kekal")
    void batalTidakMenggerakkanBaki() {
        // Legend aktif/batal memerlukan baris itu kelihatan. Membuangnya
        // menjadikan lejar tidak boleh dibandingkan dengan senarai
        // dokumen (V33 keputusan yang sama).
        seed();
        invois("LG-I2", "80.00", "CANCELLED");

        assertThat(jumlah()).as("tiada kesan pada baki").isEqualByComparingTo("0");
        assertThat(em.createNativeQuery(
                "SELECT COUNT(*) FROM sp_ledger_line WHERE sp_code=:sp AND doc_no='LG-I2'")
                .setParameter("sp", SP).getSingleResult())
                .as("baris kekal kelihatan").isEqualTo(1L);
    }

    @Test
    @DisplayName("Jumlah lejar SP ialah NEGATIF tepat jumlah baki pelanggan")
    void lejarSpCerminBakiPelanggan() {
        // Ujian yang paling bermakna dalam fail ini: kalau satu baris
        // hilang atau dikira dua kali, dua nombor ini berpisah.
        //
        // Ia meliputi ketiga-tiga cabang VIEW sekali gus — baris invois,
        // alokasi resit, dan baki resit yang tidak dialokasikan.
        seed();
        invois("LG-I3", "80.00", "ACTIVE");
        invois("LG-I4", "45.50", "ACTIVE");
        invois("LG-I5", "12.00", "CANCELLED");

        Object pelanggan = em.createNativeQuery(
                "SELECT COALESCE(SUM(signed_amount),0) FROM account_document_entry WHERE sp_code = :sp")
                .setParameter("sp", SP).getSingleResult();

        assertThat(jumlah())
                .isEqualByComparingTo(new BigDecimal(pelanggan.toString()).negate());
    }
}
