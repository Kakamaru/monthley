package com.monthley.gateway.internal;

import com.monthley.gateway.api.GatewayPort;
import com.monthley.ledger.internal.ChartOfAccountSeeder;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.TestConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pecahan callback merentas akaun (ADR 0019).
 *
 * Ujian ini menjalankan aliran SEBENAR: cipta bil, terima callback,
 * periksa resit. Guard sebelum bayaran diuji berasingan
 * (MultiAccountPaymentTest) — di sini yang diuji ialah apa yang berlaku
 * kepada duit.
 *
 * Gerbang diganti dengan stub kerana ToyyibPay sebenar memerlukan
 * rangkaian, kunci, dan wang.
 */
/*
 * TIADA @Transactional.
 *
 * handleCallback berjalan dengan REQUIRES_NEW supaya callback dilog
 * walaupun pemprosesan gagal — dan transaksi baharu itu TIDAK melihat data
 * ujian yang belum commit. Ujian yang dibalut transaksi akan sentiasa
 * melihat 'rujukan tidak dikenali'.
 *
 * Harganya: ujian mesti membersihkan sendiri (@AfterEach), kerana tiada
 * rollback automatik.
 */
@SpringBootTest
@ActiveProfiles("test")
class MultiAccountCallbackTest {

    /**
     * Gerbang palsu.
     *
     * fetchTransaction memulangkan amaun yang direkod semasa createBill —
     * meniru gerbang sebenar, yang memulangkan apa yang pelanggan bayar.
     */
    @TestConfiguration
    static class StubGateway {
        static BigDecimal amaunDicaj = BigDecimal.ZERO;

        @Bean @Primary
        GatewayPort stub() {
            return new GatewayPort() {
                @Override public String code() { return "TP"; }

                @Override public BillCreated createBill(NewBill req) {
                    amaunDicaj = req.amount();
                    return new BillCreated("STUB-" + req.ourRef(),
                                           "https://ujian.test/STUB");
                }

                @Override public BillTxn fetchTransaction(String sp, String billCode) {
                    return new BillTxn(true, "STUBREF-" + billCode,
                                       amaunDicaj, "1", "{}");
                }
            };
        }
    }

    @Autowired GatewayService gateway;
    @Autowired ChartOfAccountSeeder seeder;
    @Autowired org.springframework.transaction.support.TransactionTemplate tx;
    @PersistenceContext EntityManager em;

    Long uid, akaunA, akaunB, invA, invB;

    @BeforeEach
    void setup() {
        tx.executeWithoutResult(st -> setupData());
    }

    private void setupData() {
        em.createNativeQuery("""
            INSERT IGNORE INTO service_provider
              (sp_code, name, status, allow_selective, min_pymt_amount,
               created_at, updated_at, version)
            VALUES ('SPC1', 'Ujian Callback', 'ACTIVE', 1, 1.00, NOW(), NOW(), 0)
            """).executeUpdate();
        seeder.seedFor("SPC1");

        // absorb = 0: pelanggan membayar yuran, jadi amaun yang diterima
        // termasuk caj dan callback mesti menolaknya sebelum resit.
        em.createNativeQuery("""
            INSERT INTO sp_payment_setting
              (sp_code, gateway, manual_payment, online_payment, absorb,
               rate_single, rate_multi, rate_multi_acct, sandbox,
               gateway_key_enc, category_code, version)
            VALUES ('SPC1', 'TP', 1, 1, 0, 1.50, 2.00, 2.50, 1, 'x', 'x', 0)
            ON DUPLICATE KEY UPDATE absorb = 0
            """).executeUpdate();

        em.createNativeQuery("""
            INSERT IGNORE INTO app_user
              (email, full_name, mobile, password_hash, status,
               created_at, updated_at, version)
            VALUES ('cb@ujian.test', 'Pembayar CB', '0123456789', 'x', 'ACTIVE',
                    NOW(), NOW(), 0)
            """).executeUpdate();
        uid = ((Number) em.createNativeQuery(
                "SELECT id FROM app_user WHERE email = 'cb@ujian.test'")
                .getSingleResult()).longValue();

        akaunA = akaun("CB-01", "Akaun A");
        akaunB = akaun("CB-02", "Akaun B");
        invA = invois(akaunA, "CB-INV-A", new BigDecimal("80.00"));
        invB = invois(akaunB, "CB-INV-B", new BigDecimal("50.00"));
        em.flush();
    }

    @AfterEach
    void bersih() {
        // Susunan penting: baris anak dahulu, kemudian induk.
        for (String sql : new String[]{
                "DELETE FROM gateway_txn_line WHERE txn_id IN "
                    + "(SELECT id FROM gateway_txn WHERE sp_code = 'SPC1')",
                "DELETE FROM gateway_txn WHERE sp_code = 'SPC1'",
                "DELETE FROM fi_allocation WHERE debit_document_id IN "
                    + "(SELECT id FROM financial_document WHERE sp_code = 'SPC1')",
                "DELETE FROM ledger_line WHERE entry_id IN "
                    + "(SELECT id FROM ledger_entry WHERE sp_code = 'SPC1')",
                "DELETE FROM ledger_entry WHERE sp_code = 'SPC1'",
                "DELETE FROM payment WHERE sp_code = 'SPC1'",
                "DELETE FROM financial_document_line WHERE document_id IN "
                    + "(SELECT id FROM financial_document WHERE sp_code = 'SPC1')",
                "DELETE FROM financial_document WHERE sp_code = 'SPC1'",
                "DELETE FROM account WHERE sp_code = 'SPC1'",
                "DELETE FROM document_number_sequence WHERE sp_code = 'SPC1'",
                "DELETE FROM sp_payment_setting WHERE sp_code = 'SPC1'",
                "DELETE FROM chart_of_accounts WHERE sp_code = 'SPC1'",
                "DELETE FROM service_provider WHERE sp_code = 'SPC1'",
                "DELETE FROM app_user WHERE email = 'cb@ujian.test'"}) {
            try {
                tx.executeWithoutResult(st -> em.createNativeQuery(sql).executeUpdate());
            } catch (RuntimeException e) {
                // Jadual yang tidak wujud dalam susun atur ini diabaikan:
                // pembersihan tidak sepatutnya menyembunyikan kegagalan
                // ujian sebenar.
            }
        }
    }

    private Long akaun(String no, String nama) {
        em.createNativeQuery("""
            INSERT INTO account (sp_code, account_no, account_name, charge_frequency,
                                 start_date, status, payer_user_id,
                                 created_at, updated_at, version)
            VALUES ('SPC1', :no, :nm, 'MONTHLY', CURDATE(), 'ACTIVE', :uid,
                    NOW(), NOW(), 0)
            """).setParameter("no", no).setParameter("nm", nama)
                .setParameter("uid", uid).executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM account WHERE account_no = :no")
                .setParameter("no", no).getSingleResult()).longValue();
    }

    private Long invois(Long accId, String docNo, BigDecimal amaun) {
        em.createNativeQuery("""
            INSERT INTO financial_document
              (sp_code, account_id, doc_no, doc_type, doc_date, due_date,
               amount, tax_amount, status, created_at, updated_at, version)
            VALUES ('SPC1', :acc, :no, 'INVOICE', CURDATE(), CURDATE(),
                    :amt, 0.00, 'ACTIVE', NOW(), NOW(), 0)
            """).setParameter("acc", accId).setParameter("no", docNo)
                .setParameter("amt", amaun).executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM financial_document WHERE doc_no = :no")
                .setParameter("no", docNo).getSingleResult()).longValue();
    }

    private BigDecimal baki(Long docId) {
        Object v = tx.execute(st -> em.createNativeQuery("""
                SELECT (d.amount + d.tax_amount)
                         - COALESCE((SELECT SUM(al.amount) FROM fi_allocation al
                                     WHERE al.debit_document_id = d.id
                                       AND al.status = 'ACTIVE'), 0)
                FROM   financial_document d WHERE d.id = :id
                """).setParameter("id", docId).getSingleResult());
        return new BigDecimal(v.toString());
    }

    /**
     * DUA resit, satu bagi setiap akaun.
     *
     * Inilah ujian yang menangkap kegagalan idempotency: ourRef tunggal
     * untuk kedua-dua akaun bermakna bayaran kedua ditolak sebagai pendua
     * (ADR 0004), dan hanya SATU resit tercipta — pelanggan membayar untuk
     * dua akaun tetapi satu invois kekal terbuka.
     */
    @Test
    @DisplayName("bayaran dua akaun menghasilkan dua resit dan melunaskan kedua-dua invois")
    void duaAkaunDuaResit() {
        var hasil = tx.execute(st ->
                gateway.startMulti(uid, List.of(invA, invB), new BigDecimal("130.00")));
        gateway.handleCallback(hasil.ourRef(), "{}");

        assertThat(baki(invA)).isEqualByComparingTo("0.00");
        assertThat(baki(invB)).isEqualByComparingTo("0.00");

        Number bilResit = (Number) tx.execute(st -> em.createNativeQuery("""
                SELECT COUNT(*) FROM financial_document
                WHERE  sp_code = 'SPC1' AND doc_type = 'RECEIPT' AND status = 'ACTIVE'
                """).getSingleResult());
        assertThat(bilResit.longValue()).isEqualTo(2L);
    }

    /**
     * Setiap resit tergolong kepada akaunnya sendiri.
     *
     * Resit terikat kepada akaun dalam skema: nombor akaun tercetak di
     * atasnya, dan penyata akaun mesti menunjukkan resit yang menjelaskan
     * invoisnya.
     */
    @Test
    @DisplayName("resit dipautkan kepada akaun yang betul")
    void resitIkutAkaun() {
        var hasil = tx.execute(st ->
                gateway.startMulti(uid, List.of(invA, invB), new BigDecimal("130.00")));
        gateway.handleCallback(hasil.ourRef(), "{}");

        Number resitA = (Number) tx.execute(st -> em.createNativeQuery("""
                SELECT COUNT(*) FROM financial_document
                WHERE  account_id = :acc AND doc_type = 'RECEIPT' AND status = 'ACTIVE'
                """).setParameter("acc", akaunA).getSingleResult());
        Number resitB = (Number) tx.execute(st -> em.createNativeQuery("""
                SELECT COUNT(*) FROM financial_document
                WHERE  account_id = :acc AND doc_type = 'RECEIPT' AND status = 'ACTIVE'
                """).setParameter("acc", akaunB).getSingleResult());

        assertThat(resitA.longValue()).isEqualTo(1L);
        assertThat(resitB.longValue()).isEqualTo(1L);
    }

    /**
     * Yuran ditolak SEKALI, bukan sekali setiap akaun.
     *
     * Pelanggan menghantar RM132.50 untuk invois RM130 dengan caj RM2.50.
     * Menolak yuran daripada setiap bayaran akaun bermakna RM5.00 hilang
     * dan kedua-dua invois kekal terbuka sebahagian.
     */
    @Test
    @DisplayName("caj transaksi ditolak sekali sahaja merentas semua akaun")
    void yuranDitolakSekali() {
        var hasil = tx.execute(st ->
                gateway.startMulti(uid, List.of(invA, invB), new BigDecimal("130.00")));

        // absorb = 0, dua akaun: RM130 + RM2.50
        assertThat(hasil.charged()).isEqualByComparingTo("132.50");
        assertThat(hasil.fee()).isEqualByComparingTo("2.50");

        gateway.handleCallback(hasil.ourRef(), "{}");

        // Kedua-dua invois dijelaskan PENUH: yuran tidak memakan bayaran.
        assertThat(baki(invA)).isEqualByComparingTo("0.00");
        assertThat(baki(invB)).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("callback berulang tidak mencipta resit tambahan")
    void callbackBerulang() {
        var hasil = tx.execute(st ->
                gateway.startMulti(uid, List.of(invA, invB), new BigDecimal("130.00")));
        gateway.handleCallback(hasil.ourRef(), "{}");
        gateway.handleCallback(hasil.ourRef(), "{}");

        Number bilResit = (Number) tx.execute(st -> em.createNativeQuery("""
                SELECT COUNT(*) FROM financial_document
                WHERE  sp_code = 'SPC1' AND doc_type = 'RECEIPT' AND status = 'ACTIVE'
                """).getSingleResult());
        assertThat(bilResit.longValue()).isEqualTo(2L);
    }
}
