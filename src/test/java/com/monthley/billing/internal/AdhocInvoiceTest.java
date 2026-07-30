package com.monthley.billing.internal;

import com.monthley.ledger.internal.ChartOfAccountSeeder;
import com.monthley.payment.api.NewPayment;
import com.monthley.payment.api.PaymentMethod;
import com.monthley.payment.api.PaymentPort;
import com.monthley.shared.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Invois adhoc — invois kepada orang yang BUKAN pelanggan berdaftar.
 *
 * Semua berkongsi SATU akaun ADHOC-SALES per SP (V50). Akaun itu teknikal:
 * ia wujud supaya journal_line.sub_ledger_account_id tidak NULL, kerana
 * NULL memecahkan rekonsiliasi kawalan-lawan-subsidiari.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AdhocInvoiceTest {

    private static final String SP = "SPAD";

    @Autowired AdhocInvoiceService adhoc;
    @Autowired PaymentPort payment;
    @Autowired ChartOfAccountSeeder seeder;
    @Autowired JdbcClient jdbc;
    @PersistenceContext EntityManager em;

    private long produkA;
    private long produkB;
    private long periodId;

    @BeforeEach
    void seed() {
        jdbc.sql("""
                INSERT IGNORE INTO service_provider (sp_code, name, status, version)
                VALUES (:sp, 'Sekolah Ujian Adhoc', 'ACTIVE', 0)
                """).param("sp", SP).update();
        seeder.seedFor(SP);
        jdbc.sql("""
                INSERT IGNORE INTO sp_billing_setting (sp_code, currency, language, version)
                VALUES (:sp, 'MYR', 'ms', 0)
                """).param("sp", SP).update();
        // settings.forSp menyoal sp_document_setting juga — GL diambil
        // daripada tetapan, bukan pemalar.
        jdbc.sql("""
                INSERT IGNORE INTO sp_document_setting (sp_code, version)
                VALUES (:sp, 0)
                """).param("sp", SP).update();

        produkA = produk("BUKU-A", "25.00");
        produkB = produk("BUKU-B", "40.00");
        periodId = jdbc.sql("SELECT period_id FROM fi_period "
                        + "WHERE start_dt='2026-07-01' AND end_dt='2026-07-31' LIMIT 1")
                .query(Long.class).single();

        TenantContext.set(SP);
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    private long produk(String kod, String harga) {
        jdbc.sql("""
                INSERT IGNORE INTO product
                  (sp_code, code, name, charge_frequency, unit_rate,
                   main_product, mandatory, prorated, late_penalty, status, version)
                VALUES (:sp, :k, :k, 'PER_USE', :h, 0,0,0,0,'ACTIVE',0)
                """).param("sp", SP).param("k", kod)
                .param("h", new BigDecimal(harga)).update();
        return jdbc.sql("SELECT id FROM product WHERE sp_code=:sp AND code=:k")
                .param("sp", SP).param("k", kod).query(Long.class).single();
    }

    private AdhocInvoiceService.Request permintaan(AdhocInvoiceService.AdhocLine... lines) {
        return new AdhocInvoiceService.Request(
                null, "AHMAD PEMBELI", "ahmad@contoh.com", "0123456789",
                periodId, LocalDate.of(2026, 8, 15),
                "Pameran buku 2026", List.of(lines));
    }

    @Test
    @DisplayName("invois dicipta dengan butiran penerima pada DOKUMEN")
    void butiranPadaDokumen() {
        var r = adhoc.create(SP, permintaan(
                new AdhocInvoiceService.AdhocLine(produkA, BigDecimal.ONE)));
        em.flush();
        em.clear();

        assertThat(r.total()).isEqualByComparingTo("25.00");

        var d = jdbc.sql("""
                SELECT issued_to_name, issued_to_email, issued_to_phone, remarks
                FROM   financial_document WHERE id = :id
                """).param("id", r.documentId())
                .query((rs, n) -> new String[]{
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)})
                .single();

        assertThat(d[0])
                .as("akaun ADHOC-SALES dikongsi, jadi ia tidak boleh membawa "
                    + "nama sesiapa — butiran mesti pada dokumen")
                .isEqualTo("AHMAD PEMBELI");
        assertThat(d[1]).isEqualTo("ahmad@contoh.com");
        assertThat(d[2]).isEqualTo("0123456789");
        assertThat(d[3]).isEqualTo("Pameran buku 2026");
    }

    @Test
    @DisplayName("INVARIAN: dokumen dan ledger sepadan")
    void dokumenDanLedgerSepadan() {
        var r = adhoc.create(SP, permintaan(
                new AdhocInvoiceService.AdhocLine(produkA, new BigDecimal("3")),
                new AdhocInvoiceService.AdhocLine(produkB, new BigDecimal("2"))));
        em.flush();
        em.clear();

        // 3 x 25 + 2 x 40 = 155
        assertThat(r.total()).isEqualByComparingTo("155.00");

        BigDecimal dokumen = jdbc.sql(
                "SELECT amount + tax_amount FROM financial_document WHERE id = :id")
                .param("id", r.documentId()).query(BigDecimal.class).single();
        BigDecimal debit = jdbc.sql("""
                SELECT COALESCE(SUM(jl.debit_amount), 0)
                FROM   journal_line jl
                JOIN   journal_entry je ON je.id = jl.journal_entry_id
                WHERE  je.source_document_id = :id
                """).param("id", r.documentId()).query(BigDecimal.class).single();
        BigDecimal kredit = jdbc.sql("""
                SELECT COALESCE(SUM(jl.credit_amount), 0)
                FROM   journal_line jl
                JOIN   journal_entry je ON je.id = jl.journal_entry_id
                WHERE  je.source_document_id = :id
                """).param("id", r.documentId()).query(BigDecimal.class).single();

        assertThat(dokumen).isEqualByComparingTo("155.00");
        assertThat(debit)
                .as("percubaan pertama mengambil produk DUA KALI dan mengira "
                    + "amaun dua kali secara berasingan; kalau formula "
                    + "menyimpang, dokumen dan ledger tidak sepadan")
                .isEqualByComparingTo(dokumen);
        assertThat(kredit).isEqualByComparingTo(debit);
    }

    @Test
    @DisplayName("sub-ledger BUKAN NULL — rekonsiliasi kawalan bergantung padanya")
    void subLedgerBukanNull() {
        var r = adhoc.create(SP, permintaan(
                new AdhocInvoiceService.AdhocLine(produkA, BigDecimal.ONE)));
        em.flush();
        em.clear();

        Long sub = jdbc.sql("""
                SELECT jl.sub_ledger_account_id
                FROM   journal_line jl
                JOIN   journal_entry je ON je.id = jl.journal_entry_id
                WHERE  je.source_document_id = :id AND jl.debit_amount > 0
                """).param("id", r.documentId()).query(Long.class).single();

        assertThat(sub)
                .as("NULL memecahkan rekonsiliasi: kawalan AR bergerak "
                    + "sementara subsidiari tidak (Family 3)")
                .isNotNull();

        String jenis = jdbc.sql("SELECT account_type FROM account WHERE id = :id")
                .param("id", sub).query(String.class).single();
        assertThat(jenis).isEqualTo("ADHOC");
    }

    @Test
    @DisplayName("DUA invois adhoc identik dibenarkan — orang berbeza, caj sama")
    void duaInvoisIdentikDibenarkan() {
        var a = adhoc.create(SP, permintaan(
                new AdhocInvoiceService.AdhocLine(produkA, BigDecimal.ONE)));
        em.flush();
        var b = adhoc.create(SP, permintaan(
                new AdhocInvoiceService.AdhocLine(produkA, BigDecimal.ONE)));
        em.flush();

        assertThat(a.documentId())
                .as("dua pembeli membeli buku yang sama bukan pendua; "
                    + "idem_key menjadi NULL kerana period_start NULL, dan "
                    + "UNIQUE membenarkan berbilang NULL")
                .isNotEqualTo(b.documentId());
    }

    @Test
    @DisplayName("SEMUA invois adhoc berkongsi SATU akaun")
    void satuAkaunDikongsi() {
        var a = adhoc.create(SP, permintaan(
                new AdhocInvoiceService.AdhocLine(produkA, BigDecimal.ONE)));
        var b = adhoc.create(SP, permintaan(
                new AdhocInvoiceService.AdhocLine(produkB, BigDecimal.ONE)));
        em.flush();
        em.clear();

        var akaun = jdbc.sql(
                "SELECT DISTINCT account_id FROM financial_document WHERE id IN (:a, :b)")
                .param("a", a.documentId()).param("b", b.documentId())
                .query(Long.class).list();

        assertThat(akaun)
                .as("satu akaun setiap pembeli bermakna sekolah dengan tiga "
                    + "ratus pembeli pameran melebihi kuota plan")
                .hasSize(1);

        long bilAdhoc = jdbc.sql(
                "SELECT COUNT(*) FROM account WHERE sp_code = :sp AND account_type = 'ADHOC'")
                .param("sp", SP).query(Long.class).single();
        assertThat(bilAdhoc).isEqualTo(1);
    }

    @Test
    @DisplayName("kuantiti mendarab harga produk")
    void kuantitiMendarab() {
        var r = adhoc.create(SP, permintaan(
                new AdhocInvoiceService.AdhocLine(produkB, new BigDecimal("5"))));
        em.flush();

        assertThat(r.total()).isEqualByComparingTo("200.00");
    }

    @Test
    @DisplayName("GUARD: bayaran adhoc TANPA sasaran invois ditolak")
    void bayaranAdhocTanpaSasaranDitolak() {
        var inv = adhoc.create(SP, permintaan(
                new AdhocInvoiceService.AdhocLine(produkA, BigDecimal.ONE)));
        em.flush();

        long akaunAdhoc = jdbc.sql(
                "SELECT id FROM account WHERE sp_code = :sp AND account_type = 'ADHOC'")
                .param("sp", SP).query(Long.class).single();

        assertThatThrownBy(() -> payment.receivePayment(new NewPayment(
                SP, akaunAdhoc, new BigDecimal("25.00"), PaymentMethod.CASH,
                null, List.of(), null, null, null)))
                .as("FIFO merentasi invois orang yang tiada kaitan — bayaran "
                    + "pembeli A akan menutup invois pembeli B")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mesti menyatakan invois");
    }

    @Test
    @DisplayName("GUARD: bayaran adhoc DENGAN sasaran diterima")
    void bayaranAdhocDenganSasaranDiterima() {
        var inv = adhoc.create(SP, permintaan(
                new AdhocInvoiceService.AdhocLine(produkA, BigDecimal.ONE)));
        em.flush();

        long akaunAdhoc = jdbc.sql(
                "SELECT id FROM account WHERE sp_code = :sp AND account_type = 'ADHOC'")
                .param("sp", SP).query(Long.class).single();

        var r = payment.receivePayment(new NewPayment(
                SP, akaunAdhoc, new BigDecimal("25.00"), PaymentMethod.CASH,
                null, List.of(inv.documentId()), null, null, null));
        em.flush();

        assertThat(r.allocated()).isEqualByComparingTo("25.00");
        assertThat(r.deposit()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("GUARD: bayaran LEBIH daripada invois adhoc ditolak")
    void bayaranAdhocLebihanDitolak() {
        var inv = adhoc.create(SP, permintaan(
                new AdhocInvoiceService.AdhocLine(produkA, BigDecimal.ONE)));
        em.flush();

        long akaunAdhoc = jdbc.sql(
                "SELECT id FROM account WHERE sp_code = :sp AND account_type = 'ADHOC'")
                .param("sp", SP).query(Long.class).single();

        // Invois RM25, bayar RM40.
        assertThatThrownBy(() -> payment.receivePayment(new NewPayment(
                SP, akaunAdhoc, new BigDecimal("40.00"), PaymentMethod.CASH,
                null, List.of(inv.documentId()), null, null, null)))
                .as("advance pada akaun kongsi ialah duit orang lain; "
                    + "pembeli seterusnya akan menggunakannya")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("melebihi jumlah invois");
    }

    @Test
    @DisplayName("GUARD: bayaran SEBAHAGIAN dibenarkan — tunggakan direkod")
    void bayaranAdhocSebahagianDibenarkan() {
        var inv = adhoc.create(SP, permintaan(
                new AdhocInvoiceService.AdhocLine(produkB, BigDecimal.ONE)));
        em.flush();

        long akaunAdhoc = jdbc.sql(
                "SELECT id FROM account WHERE sp_code = :sp AND account_type = 'ADHOC'")
                .param("sp", SP).query(Long.class).single();

        // Invois RM40, bayar RM15.
        var r = payment.receivePayment(new NewPayment(
                SP, akaunAdhoc, new BigDecimal("15.00"), PaymentMethod.CASH,
                null, List.of(inv.documentId()), null, null, null));
        em.flush();

        assertThat(r.allocated())
                .as("bayaran kurang bukan masalah — tunggakan direkod dan "
                    + "dicontra kemudian")
                .isEqualByComparingTo("15.00");
    }

    @Test
    @DisplayName("kuantiti sifar atau negatif ditolak")
    void kuantitiTidakSah() {
        for (String q : new String[]{"0", "-1"}) {
            assertThatThrownBy(() -> adhoc.create(SP, permintaan(
                    new AdhocInvoiceService.AdhocLine(produkA, new BigDecimal(q)))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Kuantiti");
        }
    }

    @Test
    @DisplayName("tanpa produk ditolak")
    void tanpaProdukDitolak() {
        assertThatThrownBy(() -> adhoc.create(SP, permintaan()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("produk diperlukan");
    }

    @Test
    @DisplayName("tanpa nama penerima ditolak — resit perlu ditujukan kepada seseorang")
    void tanpaNamaDitolak() {
        var req = new AdhocInvoiceService.Request(
                null, "  ", "a@b.com", "012", periodId,
                LocalDate.of(2026, 8, 15), null,
                List.of(new AdhocInvoiceService.AdhocLine(produkA, BigDecimal.ONE)));

        assertThatThrownBy(() -> adhoc.create(SP, req))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Nama penerima");
    }

    @Test
    @DisplayName("produk SP lain ditolak")
    void produkSpLainDitolak() {
        assertThatThrownBy(() -> adhoc.create(SP, permintaan(
                new AdhocInvoiceService.AdhocLine(99999999L, BigDecimal.ONE))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Produk tidak dijumpai");
    }
}
