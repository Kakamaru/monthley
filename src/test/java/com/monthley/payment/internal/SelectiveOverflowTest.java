package com.monthley.payment.internal;

import com.monthley.ledger.internal.ChartOfAccountSeeder;
import com.monthley.payment.api.NewPayment;
import com.monthley.payment.api.PaymentMethod;
import com.monthley.payment.api.PaymentPort;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invois yang dipilih menetapkan KEUTAMAAN, bukan HAD.
 *
 * FIFO mengalir melalui invois yang ditanda dahulu; jika amaun masih
 * berbaki, ia meneruskan melalui invois tertunggak yang lain. Advance
 * hanya wujud apabila SEMUA invois telah dijelaskan.
 *
 * Tanpa ini, pelanggan yang menanda satu invois RM80 dan membayar RM100
 * mendapat advance RM20 sedangkan invois lain masih tertunggak — baki
 * akaun menunjukkan hutang, dan pada masa sama sistem memegang kredit
 * yang tidak digunakan.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SelectiveOverflowTest {

    @Autowired PaymentPort payments;
    @Autowired ChartOfAccountSeeder seeder;
    @PersistenceContext EntityManager em;

    Long akaun;
    Long invA;   // RM80, paling lama
    Long invB;   // RM50

    @BeforeEach
    void setup() {
        em.createNativeQuery("""
            INSERT IGNORE INTO service_provider
              (sp_code, name, status, allow_selective, created_at, updated_at, version)
            VALUES ('SPS1', 'Ujian Selektif', 'ACTIVE', 1, NOW(), NOW(), 0)
            """).executeUpdate();
        em.createNativeQuery(
                "UPDATE service_provider SET allow_selective = 1 WHERE sp_code = 'SPS1'")
                .executeUpdate();

        // Bayaran mempos ke ledger; tanpa carta akaun, posting gagal.
        seeder.seedFor("SPS1");

        em.createNativeQuery("""
            INSERT INTO account (sp_code, account_no, account_name, charge_frequency,
                                 start_date, status, created_at, updated_at, version)
            VALUES ('SPS1', 'SEL-01', 'Penghuni Ujian', 'MONTHLY', CURDATE(), 'ACTIVE',
                    NOW(), NOW(), 0)
            """).executeUpdate();
        akaun = ((Number) em.createNativeQuery(
                "SELECT id FROM account WHERE account_no = 'SEL-01'")
                .getSingleResult()).longValue();

        invA = invois("SEL-A", new BigDecimal("80.00"), 60);
        invB = invois("SEL-B", new BigDecimal("50.00"), 30);
        em.flush();
    }

    /** Invois ringkas; due_date lebih lama = lebih dahulu dalam FIFO. */
    private Long invois(String docNo, BigDecimal amaun, int hariLalu) {
        em.createNativeQuery("""
            INSERT INTO financial_document
              (sp_code, account_id, doc_no, doc_type, doc_date, due_date,
               amount, tax_amount, status, created_at, updated_at, version)
            VALUES ('SPS1', :acc, :no, 'INVOICE',
                    DATE_SUB(CURDATE(), INTERVAL :h DAY),
                    DATE_SUB(CURDATE(), INTERVAL :h DAY),
                    :amt, 0.00, 'ACTIVE', NOW(), NOW(), 0)
            """)
            .setParameter("acc", akaun)
            .setParameter("no", docNo)
            .setParameter("h", hariLalu)
            .setParameter("amt", amaun)
            .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM financial_document WHERE doc_no = :no")
                .setParameter("no", docNo).getSingleResult()).longValue();
    }

    private BigDecimal baki(Long docId) {
        Object v = em.createNativeQuery("""
                SELECT (d.amount + d.tax_amount)
                         - COALESCE((SELECT SUM(al.amount) FROM fi_allocation al
                                     WHERE al.debit_document_id = d.id
                                       AND al.status = 'ACTIVE'), 0)
                FROM   financial_document d WHERE d.id = :id
                """).setParameter("id", docId).getSingleResult();
        return new BigDecimal(v.toString());
    }

    /**
     * Bayaran melebihi invois yang ditanda MELIMPAH ke yang lain.
     *
     * Ini kes yang memotivasikan perubahan: sebelum ini RM20 menjadi
     * advance walaupun invois B masih tertunggak RM50.
     */
    @Test
    @DisplayName("lebihan melimpah ke invois tertunggak lain, bukan menjadi advance")
    void lebihanMelimpah() {
        payments.receivePayment(new NewPayment(
                "SPS1", akaun, new BigDecimal("100.00"), PaymentMethod.CASH,
                "UJI-1", List.of(invA), "idem-limpah-1", null, null));
        em.flush();

        // A dijelaskan penuh; RM20 pergi ke B.
        assertThat(baki(invA)).isEqualByComparingTo("0.00");
        assertThat(baki(invB)).isEqualByComparingTo("30.00");
    }

    /**
     * Invois yang ditanda didahulukan walaupun BUKAN yang paling lama.
     *
     * Tanpa keutamaan, FIFO tulen akan membayar A (lebih lama) dahulu dan
     * pelanggan yang sengaja memilih B tidak mendapat apa yang diminta.
     */
    @Test
    @DisplayName("invois ditanda didahulukan walaupun bukan paling lama")
    void ditandaDidahulukan() {
        payments.receivePayment(new NewPayment(
                "SPS1", akaun, new BigDecimal("50.00"), PaymentMethod.CASH,
                "UJI-2", List.of(invB), "idem-limpah-2", null, null));
        em.flush();

        // B dipilih dan dijelaskan; A tidak disentuh walaupun lebih lama.
        assertThat(baki(invB)).isEqualByComparingTo("0.00");
        assertThat(baki(invA)).isEqualByComparingTo("80.00");
    }

    @Test
    @DisplayName("bayaran separa pada invois ditanda tidak menyentuh yang lain")
    void separaTidakMelimpah() {
        payments.receivePayment(new NewPayment(
                "SPS1", akaun, new BigDecimal("30.00"), PaymentMethod.CASH,
                "UJI-3", List.of(invA), "idem-limpah-3", null, null));
        em.flush();

        assertThat(baki(invA)).isEqualByComparingTo("50.00");
        assertThat(baki(invB)).isEqualByComparingTo("50.00");
    }

    /**
     * Advance hanya wujud apabila SEMUA invois dijelaskan.
     */
    @Test
    @DisplayName("advance hanya selepas semua invois dijelaskan")
    void advanceSelepasSemua() {
        payments.receivePayment(new NewPayment(
                "SPS1", akaun, new BigDecimal("200.00"), PaymentMethod.CASH,
                "UJI-4", List.of(invA), "idem-limpah-4", null, null));
        em.flush();

        assertThat(baki(invA)).isEqualByComparingTo("0.00");
        assertThat(baki(invB)).isEqualByComparingTo("0.00");

        // RM200 - RM130 = RM70 advance.
        Object dep = em.createNativeQuery(
                "SELECT deposit_amount FROM payment WHERE payment_ref_no = 'UJI-4'")
                .getSingleResult();
        assertThat(new BigDecimal(dep.toString())).isEqualByComparingTo("70.00");
    }
}
