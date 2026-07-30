package com.monthley.document.internal;

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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Finance Documents — carian.
 *
 * SATU skrin untuk semua jenis dokumen. SP menggunakannya setiap hari
 * untuk mencari dan mencetak semula.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class FinanceDocumentSearchTest {

    private static final String SP = "SPFD";
    private static final String LAIN = "SPFX";

    @Autowired FinanceDocumentController controller;
    @Autowired JdbcClient jdbc;
    @PersistenceContext EntityManager em;

    private long acc;

    @BeforeEach
    void seed() {
        for (String sp : new String[]{SP, LAIN}) {
            jdbc.sql("""
                    INSERT IGNORE INTO service_provider (sp_code, name, status, version)
                    VALUES (:sp, 'SP Ujian Dokumen', 'ACTIVE', 0)
                    """).param("sp", sp).update();
        }
        // Tetapan SP menentukan lajur 'Title'.
        jdbc.sql("""
                INSERT INTO sp_document_setting (sp_code, invoice_title, receipt_title, version)
                VALUES (:sp, 'INVOIS', 'RESIT', 0)
                ON DUPLICATE KEY UPDATE invoice_title='INVOIS', receipt_title='RESIT'
                """).param("sp", SP).update();

        acc = akaun(SP, "FD-" + System.nanoTime(), "SITI AMINAH");

        dokumen(SP, acc, "FD-INV-1", "INVOICE",  "2026-07-01", "500.00", null);
        dokumen(SP, acc, "FD-RCP-1", "RECEIPT",  "2026-07-15", "300.00", "TRF-98765");
        dokumen(SP, acc, "FD-DN-1",  "DEBIT_NOTE", "2026-08-01", "50.00", null);

        seedBerbayar();

        long accLain = akaun(LAIN, "FX-" + System.nanoTime(), "ORANG LAIN");
        dokumen(LAIN, accLain, "FX-INV-1", "INVOICE", "2026-07-01", "999.00", null);

        em.flush();
        em.clear();

        TenantContext.set(SP);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("clerk", "n/a",
                        List.of(new SimpleGrantedAuthority("SP_" + SP + "_CLERK"))));
    }

    @AfterEach
    void clear() { TenantContext.clear(); SecurityContextHolder.clearContext(); }

    private long invBerbayar;
    private long produkId;
    /**
     * Akaun BERASINGAN untuk ujian status bayaran.
     *
     * Menambah dokumen ke akaun utama memecahkan lima ujian yang mengira
     * bilangan — dan melaraskan nombor setiap kali seed berkembang
     * bermakna ujian mengesahkan seed, bukan tingkah laku.
     */
    private long accBayar;

    /**
     * Invois dengan baris produk sebenar — tapisan produk memerlukan
     * financial_document_line.product_id.
     */
    private void seedBerbayar() {
        jdbc.sql("""
                INSERT IGNORE INTO product
                  (sp_code, code, name, charge_frequency, unit_rate,
                   main_product, mandatory, prorated, late_penalty, status, version)
                VALUES (:sp, 'FD-PROD', 'Yuran Ujian', 'MONTHLY', 500.00,
                        0,0,0,0,'ACTIVE',0)
                """).param("sp", SP).update();
        produkId = jdbc.sql("SELECT id FROM product WHERE sp_code=:sp AND code='FD-PROD'")
                .param("sp", SP).query(Long.class).single();

        accBayar = akaun(SP, "FDB-" + System.nanoTime(), "NUR BAYAR");
        invBerbayar = dokumen(SP, accBayar, "FD-INV-PAID", "INVOICE",
                "2026-07-10", "500.00", null);

        // Lajur SEBENAR: tiada sp_code (baris milik dokumen), tiada
        // rate_multiplier, tiada version. Percubaan pertama menulis enam
        // lajur yang tidak wujud, dan Spring gagal semasa @BeforeEach
        // dengan 'Unable to determine Dialect' — gejala yang menyembunyikan
        // 'Unknown column sp_code' di bawahnya.
        long pid = periodJulai();
        jdbc.sql("""
                INSERT INTO financial_document_line
                  (document_id, product_id, period_id, once_only, description,
                   quantity, proration_ratio, unit_price, amount, tax_amount,
                   account_id, period_start, period_end, active)
                VALUES (:doc, :prod, :pid, 0, 'Yuran Ujian',
                        1, 1, 500.00, 500.00, 0,
                        :acc, '2026-07-01', '2026-07-31', 1)
                """)
                .param("doc", invBerbayar).param("prod", produkId)
                .param("pid", pid).param("acc", accBayar)
                .update();
    }

    /**
     * Alokasi peringkat BARIS — mod baris bergantung pada
     * debit_document_line_id, bukan debit_document_id.
     */
    private void bayarBaris(String amaun) {
        long lineId = jdbc.sql(
                "SELECT id FROM financial_document_line WHERE document_id = :d LIMIT 1")
                .param("d", invBerbayar).query(Long.class).single();
        long resit = dokumen(SP, accBayar, "FD-RCP-LINE-" + System.nanoTime(),
                "RECEIPT", "2026-07-16", amaun, null);
        jdbc.sql("""
                INSERT INTO fi_allocation
                  (sp_code, account_id, debit_document_id, debit_document_line_id,
                   credit_document_id, amount, status, version)
                VALUES (:sp, :acc, :inv, :line, :rcp, :amt, 'ACTIVE', 0)
                """)
                .param("sp", SP).param("acc", accBayar)
                .param("inv", invBerbayar).param("line", lineId)
                .param("rcp", resit)
                .param("amt", new java.math.BigDecimal(amaun))
                .update();
        em.flush();
        em.clear();
    }

    /** Alokasi terus — memintas PaymentService supaya amaun boleh dikawal. */
    private void bayarInvois(long invoiceId, String amaun) {
        long resit = dokumen(SP, accBayar, "FD-RCP-BAYAR-" + System.nanoTime(),
                "RECEIPT", "2026-07-15", amaun, null);
        jdbc.sql("""
                INSERT INTO fi_allocation
                  (sp_code, account_id, debit_document_id, credit_document_id,
                   amount, status, version)
                VALUES (:sp, :acc, :inv, :rcp, :amt, 'ACTIVE', 0)
                """)
                .param("sp", SP).param("acc", accBayar)
                .param("inv", invoiceId).param("rcp", resit)
                .param("amt", new java.math.BigDecimal(amaun))
                .update();
        em.flush();
        em.clear();
    }

    private long akaun(String sp, String no, String nama) {
        jdbc.sql("""
                INSERT INTO account (sp_code, account_no, account_name, billto_name, status)
                VALUES (:sp, :no, :nama, :nama, 'ACTIVE')
                """).param("sp", sp).param("no", no).param("nama", nama).update();
        return jdbc.sql("SELECT id FROM account WHERE sp_code=:sp AND account_no=:no")
                .param("sp", sp).param("no", no).query(Long.class).single();
    }

    private long periodJulai() {
        return jdbc.sql("SELECT period_id FROM fi_period "
                        + "WHERE start_dt='2026-07-01' AND end_dt='2026-07-31' LIMIT 1")
                .query(Long.class).single();
    }

    /** @return id dokumen — diperlukan untuk alokasi dan baris produk. */
    private long dokumen(String sp, long accountId, String docNo, String type,
                         String tarikh, String amaun, String payRef) {
        jdbc.sql("""
                INSERT INTO financial_document
                  (sp_code, doc_no, doc_type, account_id, doc_date,
                   amount, tax_amount, status, title, currency, payment_ref_no)
                VALUES (:sp, :no, :type, :acc, :d, :amt, 0, 'ACTIVE',
                        'Keterangan dokumen', 'MYR', :ref)
                """)
                .param("sp", sp).param("no", docNo).param("type", type)
                .param("acc", accountId).param("d", LocalDate.parse(tarikh))
                .param("amt", new java.math.BigDecimal(amaun))
                .param("ref", payRef)
                .update();
        return jdbc.sql("SELECT id FROM financial_document WHERE sp_code=:sp AND doc_no=:no")
                .param("sp", sp).param("no", docNo).query(Long.class).single();
    }

    @Test
    @DisplayName("carian kosong memulangkan semua dokumen SP ini SAHAJA")
    void asasDanPengasinganPenyewa() {
        var r = controller.search(null, null, null, null, null, null, null, null, null, 0, 50);

        // Empat: tiga pada akaun utama, satu pada akaun ujian bayaran.
        assertThat(r.items()).hasSize(4);
        assertThat(r.items()).extracting(FinanceDocumentController.DocumentRow::docNo)
                .as("dokumen SP lain tidak boleh muncul")
                .doesNotContain("FX-INV-1");
    }

    @Test
    @DisplayName("lajur Title datang daripada TETAPAN SP, bukan document.title")
    void titleDaripadaTetapan() {
        var r = controller.search(null, null, null, null, null, null, null, null, null, 0, 50);

        var inv = r.items().stream()
                .filter(d -> "FD-INV-1".equals(d.docNo())).findFirst().orElseThrow();
        var rcp = r.items().stream()
                .filter(d -> "FD-RCP-1".equals(d.docNo())).findFirst().orElseThrow();

        assertThat(inv.title())
                .as("document.title ialah 'Keterangan dokumen'; lajur ini "
                    + "sepatutnya label JENIS daripada tetapan")
                .isEqualTo("INVOIS");
        assertThat(rcp.title()).isEqualTo("RESIT");
    }

    @Test
    @DisplayName("nota debit/kredit dapat label lalai — tiada tetapan untuknya")
    void notaLabelLalai() {
        var r = controller.search(null, null, null, "DEBIT_NOTE", null, null, null, null, null, 0, 50);
        assertThat(r.items()).hasSize(1);
        assertThat(r.items().get(0).title()).isEqualTo("Nota Debit");
    }

    @Test
    @DisplayName("tapis: no. dokumen separa, tidak sensitif huruf besar")
    void tapisDocNo() {
        assertThat(controller.search("fd-rcp", null, null, null, null, null, null, null, null, 0, 50)
                .items()).hasSize(1);
    }

    @Test
    @DisplayName("tapis: nama akaun ATAU no. akaun")
    void tapisAkaun() {
        assertThat(controller.search(null, "siti", null, null, null, null, null, null, null, 0, 50)
                .items()).hasSize(3);
        assertThat(controller.search(null, "tiada-siapa", null, null, null, null, null, null, null, 0, 50)
                .items()).isEmpty();
    }

    @Test
    @DisplayName("tapis: payment ref — no. transaksi bank")
    void tapisPayRef() {
        var r = controller.search(null, null, null, null, "98765", null, null, null, null, 0, 50);
        assertThat(r.items()).hasSize(1);
        assertThat(r.items().get(0).docNo()).isEqualTo("FD-RCP-1");
    }

    @Test
    @DisplayName("tapis: julat tarikh dikeluarkan")
    void tapisTarikh() {
        var julai = controller.search(null, null, null, null, null,
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), null, null, 0, 50);
        assertThat(julai.items()).hasSize(3);

        var ogos = controller.search(null, null, null, null, null,
                LocalDate.of(2026, 8, 1), null, null, null, 0, 50);
        assertThat(ogos.items()).hasSize(1);
    }

    @Test
    @DisplayName("tersusun TERBARU dahulu — SP mencari yang baru dikeluarkan")
    void tersusunTerbaru() {
        var r = controller.search(null, null, null, null, null, null, null, null, null, 0, 50);
        assertThat(r.items()).extracting(FinanceDocumentController.DocumentRow::docNo)
                .containsExactly("FD-DN-1", "FD-RCP-1", "FD-INV-PAID", "FD-INV-1");
    }

    @Test
    @DisplayName("status BAYARAN, bukan 'Aktif' untuk semua invois")
    void statusBayaran() {
        // Invois lain dalam seed tiada bayaran.
        var r = controller.search(null, null, null, "INVOICE", null, null, null,
                null, null, 0, 50);

        assertThat(r.items())
                .as("'Aktif' pada invois tidak memberitahu apa-apa — SEMUA "
                    + "invois aktif sampai dibatalkan")
                .allMatch(d -> "UNPAID".equals(d.paymentStatus()));
    }

    @Test
    @DisplayName("invois DIBAYAR PENUH menjadi PAID")
    void invoisLunas() {
        bayarInvois(invBerbayar, "500.00");

        var r = controller.search("FD-INV-PAID", null, null, null, null, null, null,
                null, null, 0, 50);
        assertThat(r.items()).hasSize(1);
        var d = r.items().get(0);

        assertThat(d.paymentStatus()).isEqualTo("PAID");
        assertThat(d.paid()).isEqualByComparingTo("500.00");
        assertThat(d.outstanding()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("invois DIBAYAR SEBAHAGIAN menjadi PARTIAL dengan baki")
    void invoisSebahagian() {
        bayarInvois(invBerbayar, "200.00");

        var d = controller.search("FD-INV-PAID", null, null, null, null, null, null,
                null, null, 0, 50).items().get(0);

        assertThat(d.paymentStatus()).isEqualTo("PARTIAL");
        assertThat(d.paid()).isEqualByComparingTo("200.00");
        assertThat(d.outstanding())
                .as("SP perlu tahu berapa lagi, bukan hanya 'belum lunas'")
                .isEqualByComparingTo("300.00");
    }

    @Test
    @DisplayName("RESIT tiada tunggakan — ia bayaran, bukan hutang")
    void resitTiadaTunggakan() {
        var d = controller.search("FD-RCP-1", null, null, null, null, null, null,
                null, null, 0, 50).items().get(0);

        assertThat(d.paymentStatus()).isEqualTo("ACTIVE");
        assertThat(d.outstanding())
                .as("V45 memaparkan outstanding = total untuk resit kerana "
                    + "tiada alokasi di mana resit ialah pihak DEBIT; angka "
                    + "itu tunggakan hantu")
                .isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("tapis ikut status bayaran — 'bayaran mana belum masuk'")
    void tapisStatusBayaran() {
        bayarInvois(invBerbayar, "500.00");

        var lunas = controller.search(null, null, null, null, null, null, null,
                "PAID", null, 0, 50);
        assertThat(lunas.items()).hasSize(1);
        assertThat(lunas.items().get(0).docNo()).isEqualTo("FD-INV-PAID");

        var belum = controller.search(null, null, null, null, null, null, null,
                "UNPAID", null, 0, 50);
        assertThat(belum.items())
                .as("invois tanpa bayaran sahaja")
                .isNotEmpty()
                .allMatch(d -> "UNPAID".equals(d.paymentStatus()));
    }

    @Test
    @DisplayName("tapis ikut PRODUK — 'pilih produk, tengok siapa belum bayar'")
    void tapisProduk() {
        var r = controller.search(null, null, null, null, null, null, null,
                null, produkId, 0, 50);

        assertThat(r.items())
                .as("permintaan pelanggan: pilih produk dan lihat mana yang "
                    + "sudah dan belum dibayar")
                .hasSize(1);
        assertThat(r.items().get(0).docNo()).isEqualTo("FD-INV-PAID");
    }

    @Test
    @DisplayName("mod BARIS: tapis produk memberi satu baris per baris invois")
    void modBarisIkutProduk() {
        var r = controller.searchLines(null, null, produkId, null, null,
                null, null, 0, 50);

        assertThat(r.items())
                .as("granulariti BARIS, bukan dokumen — SP mahu tahu bahagian "
                    + "produk ini sudah dibayar atau belum")
                .hasSize(1);
        var l = r.items().get(0);
        assertThat(l.productName()).isEqualTo("Yuran Ujian");
        assertThat(l.docNo()).isEqualTo("FD-INV-PAID");
        assertThat(l.total()).isEqualByComparingTo("500.00");
        assertThat(l.paymentStatus()).isEqualTo("UNPAID");
    }

    @Test
    @DisplayName("mod BARIS: status ikut ALOKASI PERINGKAT BARIS")
    void modBarisStatusIkutAlokasiBaris() {
        bayarBaris("500.00");

        var l = controller.searchLines(null, null, produkId, null, null,
                null, null, 0, 50).items().get(0);

        assertThat(l.paymentStatus()).isEqualTo("PAID");
        assertThat(l.paid()).isEqualByComparingTo("500.00");
        assertThat(l.outstanding()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("mod BARIS: bayaran sebahagian memberi PARTIAL dengan baki")
    void modBarisSebahagian() {
        bayarBaris("150.00");

        var l = controller.searchLines(null, null, produkId, null, null,
                null, null, 0, 50).items().get(0);

        assertThat(l.paymentStatus()).isEqualTo("PARTIAL");
        assertThat(l.outstanding()).isEqualByComparingTo("350.00");
    }

    @Test
    @DisplayName("mod BARIS: tapis UNPAID menjawab 'siapa belum bayar produk ini'")
    void modBarisTapisBelumBayar() {
        var belum = controller.searchLines(null, null, produkId, null, "UNPAID",
                null, null, 0, 50);
        assertThat(belum.items()).hasSize(1);

        bayarBaris("500.00");

        assertThat(controller.searchLines(null, null, produkId, null, "UNPAID",
                null, null, 0, 50).items())
                .as("selepas dibayar ia keluar daripada senarai belum bayar")
                .isEmpty();
        assertThat(controller.searchLines(null, null, produkId, null, "PAID",
                null, null, 0, 50).items()).hasSize(1);
    }

    @Test
    @DisplayName("mod BARIS: NOTA DEBIT tidak muncul — tiada baris produk")
    void modBarisNotaDebitTiada() {
        var r = controller.searchLines(null, null, null, null, null,
                null, null, 0, 50);

        assertThat(r.items())
                .as("nota debit ialah pelarasan tanpa baris produk; keempat "
                    + "alokasi dengan line_id NULL dalam data pembangunan "
                    + "semuanya nota debit dengan sifar baris")
                .extracting(FinanceDocumentController.ProductLineRow::docNo)
                .doesNotContain("FD-DN-1");
    }

    @Test
    @DisplayName("mod BARIS: pengasingan penyewa")
    void modBarisPengasingan() {
        var r = controller.searchLines(null, null, null, null, null,
                null, null, 0, 50);
        assertThat(r.items())
                .allMatch(l -> !"FX-INV-1".equals(l.docNo()));
    }

    @Test
    @DisplayName("pagination: jumlah ialah keseluruhan, bukan saiz halaman")
    void pagination() {
        var r = controller.search(null, null, null, null, null, null, null, null, null, 0, 2);
        assertThat(r.items()).hasSize(2);
        assertThat(r.total()).isEqualTo(4);
    }
}
