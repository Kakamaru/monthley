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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tarikh bayaran DITERIMA, bukan tarikh rekod dicipta.
 *
 * Kerani boleh merekod bayaran yang diterima dua hari lepas. Sebelum
 * pembetulan ini, ManualPaymentController menerima paymentDate dan
 * MEMBUANGnya: resit, rekod bayaran dan catatan ledger semuanya
 * menggunakan LocalDate.now().
 *
 * Kesannya bukan kosmetik — penyata menyusun resit pada tarikh yang
 * salah, baki berjalan salah untuk hari-hari antara, dan rekonsiliasi
 * bank tidak tally.
 *
 * Legacy membezakan kedua-duanya: 'Receipt Date' ialah bila bayaran
 * diterima, 'Date of Issue' ialah bila resit dicetak.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PaymentDateTest {

    private static final String SP = "SPDT";

    @Autowired PaymentPort payment;
    @Autowired ChartOfAccountSeeder seeder;
    @Autowired JdbcClient jdbc;
    @PersistenceContext EntityManager em;

    private long acc;

    @BeforeEach
    void seed() {
        jdbc.sql("""
                INSERT IGNORE INTO service_provider (sp_code, name, status, version)
                VALUES (:sp, 'SP Ujian Tarikh', 'ACTIVE', 0)
                """).param("sp", SP).update();
        seeder.seedFor(SP);

        String no = "DT-" + System.nanoTime();
        jdbc.sql("""
                INSERT INTO account (sp_code, account_no, account_name, status)
                VALUES (:sp, :no, 'Ujian Tarikh', 'ACTIVE')
                """).param("sp", SP).param("no", no).update();
        acc = jdbc.sql("SELECT id FROM account WHERE sp_code=:sp AND account_no=:no")
                .param("sp", SP).param("no", no).query(Long.class).single();
    }

    /** @return id DOKUMEN resit. */
    private long bayar(String amaun, LocalDate tarikh, String ref) {
        var r = payment.receivePayment(new NewPayment(SP, acc, new BigDecimal(amaun),
                PaymentMethod.TRANSFER, ref, List.of(), null, tarikh));
        em.flush();
        return r.receiptDocumentId();
    }

    @Test
    @DisplayName("tarikh dua hari lepas muncul pada resit, rekod bayaran DAN ledger")
    void tarikhKeBelakangDihormati() {
        LocalDate duaHariLepas = LocalDate.now().minusDays(2);
        long resitId = bayar("100.00", duaHariLepas, "DT-REF-1");

        LocalDate docDate = jdbc.sql(
                "SELECT doc_date FROM financial_document WHERE id = :id")
                .param("id", resitId).query(LocalDate.class).single();
        assertThat(docDate)
                .as("penyata menyusun mengikut doc_date — tarikh salah bermakna "
                    + "resit muncul pada hari yang salah")
                .isEqualTo(duaHariLepas);

        LocalDate payDate = jdbc.sql(
                "SELECT payment_date FROM payment WHERE receipt_document_id = :id")
                .param("id", resitId).query(LocalDate.class).single();
        assertThat(payDate).isEqualTo(duaHariLepas);

        LocalDate entryDate = jdbc.sql("""
                SELECT entry_date FROM journal_entry
                WHERE source_document_id = :id AND source_type = 'PAYMENT'
                """).param("id", resitId).query(LocalDate.class).single();
        assertThat(entryDate)
                .as("ledger mesti sepadan — jika tidak rekonsiliasi bank tidak tally")
                .isEqualTo(duaHariLepas);
    }

    @Test
    @DisplayName("ketiga-tiga tarikh SAMA — diambil sekali, bukan now() tiga kali")
    void satuTarikhBukanTiga() {
        LocalDate semalam = LocalDate.now().minusDays(1);
        long resitId = bayar("50.00", semalam, "DT-REF-2");

        var tarikh = jdbc.sql("""
                SELECT d.doc_date, p.payment_date, j.entry_date
                FROM   financial_document d
                JOIN   payment p ON p.receipt_document_id = d.id
                JOIN   journal_entry j ON j.source_document_id = d.id
                                     AND j.source_type = 'PAYMENT'
                WHERE  d.id = :id
                """).param("id", resitId)
                .query((rs, n) -> List.of(
                        rs.getDate(1).toLocalDate(),
                        rs.getDate(2).toLocalDate(),
                        rs.getDate(3).toLocalDate()))
                .single();

        assertThat(tarikh)
                .as("LocalDate.now() dipanggil tiga kali boleh memberi dua tarikh "
                    + "berbeza bagi bayaran pada tengah malam")
                .containsExactly(semalam, semalam, semalam);
    }

    @Test
    @DisplayName("null = hari ini — tingkah laku lalai tidak berubah")
    void nullBermaknaHariIni() {
        long resitId = bayar("30.00", null, "DT-REF-3");

        LocalDate docDate = jdbc.sql(
                "SELECT doc_date FROM financial_document WHERE id = :id")
                .param("id", resitId).query(LocalDate.class).single();
        assertThat(docDate).isEqualTo(LocalDate.now());
    }

    @Test
    @DisplayName("created_at kekal masa sebenar — itu 'Date of Issue' pada resit")
    void createdAtBukanTarikhBayar() {
        LocalDate lama = LocalDate.now().minusDays(5);
        long resitId = bayar("20.00", lama, "DT-REF-4");

        LocalDate dicipta = jdbc.sql(
                "SELECT DATE(created_at) FROM financial_document WHERE id = :id")
                .param("id", resitId).query(LocalDate.class).single();

        assertThat(dicipta)
                .as("dua tarikh, dua maksud: bila bayaran diterima lawan bila "
                    + "resit dikeluarkan")
                .isEqualTo(LocalDate.now())
                .isNotEqualTo(lama);
    }
}
