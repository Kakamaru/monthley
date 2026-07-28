package com.monthley.payment.internal;

import com.monthley.billing.internal.InvoiceGenerationService;
import com.monthley.document.api.DocumentPort;
import com.monthley.document.api.NewDocumentLine;
import com.monthley.document.api.NewInvoice;
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
 * INVARIAN: invois terbuka dan advance tidak boleh wujud SERENTAK.
 *
 * Jika pelanggan berhutang RM300 dan kita memegang RM200 duitnya yang
 * tidak dipadankan, sesuatu terlepas. Resit mesti knock off invois
 * terbuka SEBELUM bakinya menjadi advance.
 *
 * Ditemui semasa menyiasat portal: M04 memaparkan Baki 500.59 tetapi
 * Tunggakan 700.59. Jurang itu BUKAN keadaan sah — ia tanda alokasi
 * tidak lengkap. Data M04 mendahului auto-knock ADR 0009 P3 (ea16e9e,
 * 24 Julai) jadi ia menjelaskan kes itu.
 *
 * Auto-knock hidup dalam InvoiceGenerationService, bukan dalam
 * DocumentPort. Ujian kedua menjaga sempadan itu.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AdvanceVsOpenInvoiceTest {

    private static final String SP = "SPADV";

    @Autowired PaymentPort payment;
    @Autowired DocumentPort documents;
    @Autowired ChartOfAccountSeeder seeder;
    @Autowired JdbcClient jdbc;
    @PersistenceContext EntityManager em;

    private long acc;

    @BeforeEach
    void seed() {
        jdbc.sql("""
                INSERT IGNORE INTO service_provider (sp_code, name, status, version)
                VALUES (:sp, 'SP Ujian Advance', 'ACTIVE', 0)
                """).param("sp", SP).update();
        seeder.seedFor(SP);

        String no = "ADV-" + System.nanoTime();
        jdbc.sql("""
                INSERT INTO account (sp_code, account_no, account_name, status)
                VALUES (:sp, :no, 'Ujian Advance', 'ACTIVE')
                """).param("sp", SP).param("no", no).update();
        acc = jdbc.sql("SELECT id FROM account WHERE sp_code=:sp AND account_no=:no")
                .param("sp", SP).param("no", no).query(Long.class).single();
    }

    /**
     * Tempoh BULANAN sebenar. Setiap invois mesti tempoh berbeza —
     * idem_key menolak produk yang sama dua kali dalam tempoh yang sama,
     * dan itu memang tujuannya.
     */
    private long periodBulan(int bulan) {
        return jdbc.sql("SELECT period_id FROM fi_period "
                        + "WHERE start_dt = :s AND end_dt = :e LIMIT 1")
                .param("s", LocalDate.of(2026, bulan, 1))
                .param("e", LocalDate.of(2026, bulan, 1)
                        .withDayOfMonth(LocalDate.of(2026, bulan, 1).lengthOfMonth()))
                .query(Long.class).single();
    }

    private long invois(String docNo, String amaun, int bulan) {
        long pid = periodBulan(bulan);
        LocalDate mula = LocalDate.of(2026, bulan, 1);
        LocalDate tamat = mula.withDayOfMonth(mula.lengthOfMonth());
        var line = new NewDocumentLine(null, acc, pid, "Yuran", BigDecimal.ONE,
                new BigDecimal(amaun), BigDecimal.ONE, new BigDecimal(amaun),
                BigDecimal.ZERO, mula, tamat, false);
        return documents.createInvoice(new NewInvoice(SP, acc, pid,
                mula, tamat, docNo, List.of(line))).orElseThrow();
    }

    /** Invois belum berbayar merentas akaun. */
    private BigDecimal tunggakan() {
        return jdbc.sql("""
                SELECT COALESCE(SUM((d.amount + d.tax_amount) - COALESCE((
                          SELECT SUM(a.amount) FROM fi_allocation a
                          WHERE a.debit_document_id = d.id AND a.status='ACTIVE'),0)), 0)
                FROM financial_document d
                WHERE d.account_id = :a AND d.doc_type IN ('INVOICE','DEBIT_NOTE')
                  AND d.status <> 'CANCELLED'
                """).param("a", acc).query(BigDecimal.class).single();
    }

    /** Duit diterima yang belum dipadankan. */
    private BigDecimal advance() {
        return jdbc.sql("""
                SELECT COALESCE(SUM((d.amount + d.tax_amount) - COALESCE((
                          SELECT SUM(a.amount) FROM fi_allocation a
                          WHERE a.credit_document_id = d.id AND a.status='ACTIVE'),0)), 0)
                FROM financial_document d
                WHERE d.account_id = :a AND d.doc_type IN ('RECEIPT','CREDIT_NOTE')
                  AND d.status <> 'CANCELLED'
                """).param("a", acc).query(BigDecimal.class).single();
    }

    private BigDecimal baki() {
        return jdbc.sql("SELECT COALESCE(balance,0) FROM account_balance WHERE account_id=:a")
                .param("a", acc).query(BigDecimal.class).single();
    }

    @Test
    @DisplayName("bayaran lebih: invois DITUTUP dahulu, baki jadi advance")
    void bayaranLebihTutupInvoisDahulu() {
        invois("ADV-INV-1", "300.00", 7);
        em.flush();

        payment.receivePayment(new NewPayment(SP, acc, new BigDecimal("500.00"),
                PaymentMethod.FPX, "ADV-MP-1", List.of(), null, null));
        em.flush();

        assertThat(tunggakan())
                .as("RM300 sudah dibayar — tidak boleh kekal terbuka sedangkan "
                    + "kita memegang duit pelanggan")
                .isEqualByComparingTo("0.00");
        assertThat(advance()).isEqualByComparingTo("200.00");
        assertThat(baki()).isEqualByComparingTo("-200.00");
    }

    @Test
    @DisplayName("auto-knock hidup dalam InvoiceGenerationService, bukan DocumentPort")
    void autoKnockMilikEnjinBil() {
        invois("ADV-INV-2", "300.00", 8);
        em.flush();
        payment.receivePayment(new NewPayment(SP, acc, new BigDecimal("500.00"),
                PaymentMethod.FPX, "ADV-MP-2", List.of(), null, null));
        em.flush();

        assertThat(advance()).isEqualByComparingTo("200.00");

        // Mencipta invois melalui DocumentPort TERUS memintas auto-knock:
        // applyAdvance dipanggil oleh InvoiceGenerationService, bukan oleh
        // DocumentPort. Advance kekal tidak diserap.
        //
        // Ini BUKAN pepijat — ia sempadan yang direkodkan. Satu-satunya
        // pemanggil createInvoice dalam pengeluaran ialah
        // InvoiceGenerationService (baris 184), dan ia memanggil
        // applyAdvance sejurus selepas (baris 201).
        //
        // Ujian ini menjaga sempadan itu: jika laluan KEDUA untuk mencipta
        // invois ditambah tanpa auto-knock, invarian tunggakan-lawan-advance
        // akan pecah dalam pengeluaran seperti yang ia pecah di sini.
        invois("ADV-INV-3", "120.00", 9);
        em.flush();

        assertThat(tunggakan())
                .as("DocumentPort tidak menyerap advance — itu kerja enjin bil")
                .isEqualByComparingTo("120.00");
        assertThat(advance())
                .as("advance kekal utuh apabila auto-knock dipintas")
                .isEqualByComparingTo("200.00");

        // Baki TETAP betul walaupun alokasi tidak lengkap — itulah sebab
        // account_balance mengabaikan alokasi sepenuhnya (ADR 0009).
        assertThat(baki())
                .as("300 + 120 - 500; baki tidak bergantung pada alokasi")
                .isEqualByComparingTo("-80.00");
    }
}
