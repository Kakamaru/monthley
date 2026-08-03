package com.monthley.payment.internal;

import com.monthley.document.api.DocumentPort;
import com.monthley.document.api.NewDocumentLine;
import com.monthley.document.api.NewInvoice;
import com.monthley.ledger.internal.ChartOfAccountSeeder;
import com.monthley.payment.api.NewPayment;
import com.monthley.payment.api.PaymentMethod;
import com.monthley.payment.api.PaymentPort;
import com.monthley.shared.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.*;
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
 * Pembatalan dokumen — DUIT BERGERAK, jadi setiap invarian diuji.
 *
 * BAKI BETUL SECARA AUTOMATIK. account_document_entry memberi
 * signed_amount = 0 untuk dokumen CANCELLED, dan account_balance dikira
 * daripada dokumen (ADR 0009). Tiada cache untuk menyimpang — itu sebab
 * pepijat RM9.70 legacy tidak boleh berulang di sini.
 *
 * Yang TIDAK automatik: alokasi. Melepaskannya menentukan sama ada duit
 * kembali menjadi advance atau terperangkap pada dokumen yang tidak lagi
 * wujud.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CancelDocumentTest {

    private static final String SP = "SPCX";

    @Autowired PaymentPort payment;
    @Autowired DocumentPort documents;
    @Autowired ChartOfAccountSeeder seeder;
    @Autowired com.monthley.document.api.DocumentAccessPort access;
    @Autowired JdbcClient jdbc;
    @PersistenceContext EntityManager em;

    private long acc;

    @BeforeEach
    void seed() {
        jdbc.sql("""
                INSERT IGNORE INTO service_provider (sp_code, name, status, version)
                VALUES (:sp, 'SP Ujian Batal', 'ACTIVE', 0)
                """).param("sp", SP).update();
        seeder.seedFor(SP);
        jdbc.sql("INSERT IGNORE INTO sp_document_setting (sp_code, version) VALUES (:sp, 0)")
                .param("sp", SP).update();
        jdbc.sql("""
                INSERT IGNORE INTO sp_billing_setting (sp_code, currency, language, version)
                VALUES (:sp, 'MYR', 'ms', 0)
                """).param("sp", SP).update();

        String no = "CX-" + System.nanoTime();
        jdbc.sql("""
                INSERT INTO account (sp_code, account_no, account_name, billto_name, status)
                VALUES (:sp, :no, 'HAFIZ', 'HAFIZ', 'ACTIVE')
                """).param("sp", SP).param("no", no).update();
        acc = jdbc.sql("SELECT id FROM account WHERE sp_code=:sp AND account_no=:no")
                .param("sp", SP).param("no", no).query(Long.class).single();

        TenantContext.set(SP);
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    private long produk(String kod, String harga) {
        jdbc.sql("""
                INSERT IGNORE INTO product
                  (sp_code, code, name, charge_frequency, unit_rate,
                   main_product, mandatory, prorated, late_penalty, status, version)
                VALUES (:sp, :k, :k, 'MONTHLY', :h, 0,0,0,0,'ACTIVE',0)
                """).param("sp", SP).param("k", kod)
                .param("h", new BigDecimal(harga)).update();
        return jdbc.sql("SELECT id FROM product WHERE sp_code=:sp AND code=:k")
                .param("sp", SP).param("k", kod).query(Long.class).single();
    }

    private long periodJulai() {
        return jdbc.sql("SELECT period_id FROM fi_period "
                        + "WHERE start_dt='2026-07-01' AND end_dt='2026-07-31' LIMIT 1")
                .query(Long.class).single();
    }

    private long invois(String docNo, String amaun) {
        long pid = periodJulai();
        var line = new NewDocumentLine(produk("P-" + docNo, amaun), acc, pid, null,
                "Yuran", BigDecimal.ONE, new BigDecimal(amaun), BigDecimal.ONE,
                new BigDecimal(amaun), BigDecimal.ZERO,
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), false);
        long id = documents.createInvoice(new NewInvoice(SP, acc, pid,
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31),
                docNo, List.of(line))).orElseThrow();
        em.flush();
        return id;
    }

    private BigDecimal baki() {
        em.flush();
        em.clear();
        return jdbc.sql("SELECT balance FROM account_balance WHERE account_id = :a")
                .param("a", acc).query(BigDecimal.class).optional()
                .orElse(BigDecimal.ZERO);
    }

    private long alokasiAktif(long docId, boolean debit) {
        String lajur = debit ? "debit_document_id" : "credit_document_id";
        return jdbc.sql("SELECT COUNT(*) FROM fi_allocation "
                        + "WHERE " + lajur + " = :id AND status = 'ACTIVE'")
                .param("id", docId).query(Long.class).single();
    }

    // ── batal resit ──────────────────────────────────────────────────

    @Test
    @DisplayName("batal RESIT: baki kembali ke jumlah invois penuh")
    void batalResitBakiKembali() {
        invois("CX-INV-1", "500.00");
        assertThat(baki()).isEqualByComparingTo("500.00");

        var r = payment.receivePayment(new NewPayment(SP, acc,
                new BigDecimal("300.00"), PaymentMethod.CASH, null,
                List.of(), null, null, null));
        assertThat(baki()).isEqualByComparingTo("200.00");

        payment.cancelReceipt(r.paymentId(), "silap masuk", 99L);

        assertThat(baki())
                .as("resit CANCELLED memberi signed_amount 0; baki kembali "
                    + "tanpa apa-apa cache dikemas kini")
                .isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("batal RESIT: alokasi dilepaskan, invois terbuka semula")
    void batalResitAlokasiDilepas() {
        long inv = invois("CX-INV-2", "100.00");
        var r = payment.receivePayment(new NewPayment(SP, acc,
                new BigDecimal("100.00"), PaymentMethod.CASH, null,
                List.of(), null, null, null));
        em.flush();
        assertThat(alokasiAktif(inv, true)).isEqualTo(1);

        payment.cancelReceipt(r.paymentId(), "cek pulang", null);
        em.flush();

        assertThat(alokasiAktif(inv, true))
                .as("invois mesti terbuka semula untuk bayaran akan datang")
                .isZero();
    }

    @Test
    @DisplayName("batal RESIT: sebab dan pembatal DIREKOD")
    void batalResitSebabDirekod() {
        invois("CX-INV-3", "80.00");
        var r = payment.receivePayment(new NewPayment(SP, acc,
                new BigDecimal("80.00"), PaymentMethod.CASH, null,
                List.of(), null, null, null));
        em.flush();

        payment.cancelReceipt(r.paymentId(), "pelanggan minta batal", 42L);
        em.flush();
        em.clear();

        var rec = jdbc.sql("""
                SELECT cancel_reason, cancelled_by, cancelled_at IS NOT NULL
                FROM   financial_document WHERE id = :id
                """).param("id", r.receiptDocumentId())
                .query((rs, n) -> new Object[]{
                        rs.getString(1), rs.getString(2), rs.getBoolean(3)})
                .single();

        assertThat((String) rec[0])
                .as("dialog Cancel mempunyai medan Remarks WAJIB; lajur ini "
                    + "wujud sejak V1 tetapi tidak pernah diisi")
                .isEqualTo("pelanggan minta batal");
        assertThat((String) rec[1]).isEqualTo("42");
        assertThat((Boolean) rec[2]).isTrue();
    }

    @Test
    @DisplayName("batal RESIT: pautan awam berhenti berfungsi")
    void batalResitTokenDibatalkan() {
        invois("CX-INV-4", "60.00");
        var r = payment.receivePayment(new NewPayment(SP, acc,
                new BigDecimal("60.00"), PaymentMethod.CASH, null,
                List.of(), null, null, null));
        em.flush();

        String token = access.tokenFor(SP, r.receiptDocumentId(),
                com.monthley.document.api.DocumentType.RECEIPT);
        em.flush();
        assertThat(access.resolve(token)).isPresent();

        payment.cancelReceipt(r.paymentId(), "batal", null);
        em.flush();
        em.clear();

        assertThat(access.resolve(token))
                .as("pelanggan membuka pautan e-mel dan melihat resit yang "
                    + "dibatalkan, lalu menganggapnya bukti bayaran")
                .isEmpty();
    }

    @Test
    @DisplayName("batal RESIT dua kali ditolak")
    void batalResitDuaKali() {
        invois("CX-INV-5", "40.00");
        var r = payment.receivePayment(new NewPayment(SP, acc,
                new BigDecimal("40.00"), PaymentMethod.CASH, null,
                List.of(), null, null, null));
        em.flush();

        payment.cancelReceipt(r.paymentId(), "sekali", null);
        em.flush();

        assertThatThrownBy(() -> payment.cancelReceipt(r.paymentId(), "dua kali", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sudah dibatalkan");
    }

    // ── batal invois ─────────────────────────────────────────────────

    @Test
    @DisplayName("batal INVOIS belum dibayar: baki jadi sifar")
    void batalInvoisBelumDibayar() {
        long inv = invois("CX-INV-6", "250.00");
        assertThat(baki()).isEqualByComparingTo("250.00");

        payment.cancelInvoice(inv, "dijana tersilap", 7L);

        assertThat(baki()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("INVARIAN: batal invois yang DIBAYAR meninggalkan duit sebagai KREDIT")
    void batalInvoisDibayarMeninggalkanKredit() {
        long inv = invois("CX-INV-7", "500.00");
        payment.receivePayment(new NewPayment(SP, acc,
                new BigDecimal("300.00"), PaymentMethod.CASH, null,
                List.of(), null, null, null));
        assertThat(baki()).isEqualByComparingTo("200.00");

        payment.cancelInvoice(inv, "invois salah amaun", null);

        assertThat(baki())
                .as("duit RM300 masih diterima. Invois hilang, jadi baki mesti "
                    + "menjadi KREDIT RM300 — bukan sifar, dan bukan 200")
                .isEqualByComparingTo("-300.00");
    }

    @Test
    @DisplayName("batal INVOIS melepaskan alokasi supaya duit boleh dipakai semula")
    void batalInvoisAlokasiDilepas() {
        long inv = invois("CX-INV-8", "100.00");
        var r = payment.receivePayment(new NewPayment(SP, acc,
                new BigDecimal("100.00"), PaymentMethod.CASH, null,
                List.of(), null, null, null));
        em.flush();
        assertThat(alokasiAktif(inv, true)).isEqualTo(1);

        payment.cancelInvoice(inv, "batal", null);
        em.flush();

        assertThat(alokasiAktif(inv, true)).isZero();
        assertThat(alokasiAktif(r.receiptDocumentId(), false))
                .as("resit kekal 'digunakan' pada invois yang tidak lagi wujud "
                    + "jika alokasi tidak dilepaskan, dan advance itu tidak "
                    + "boleh dicapai")
                .isZero();
    }

    @Test
    @DisplayName("batal INVOIS membalikkan ledger sebagai CONTRA, bukan padam")
    void batalInvoisLedgerContra() {
        long inv = invois("CX-INV-9", "150.00");
        em.flush();

        // documents.createInvoice mencipta DOKUMEN sahaja; catatan ledger
        // di-post oleh billing. Ujian ini memanggil document terus, jadi
        // catatan itu ditambah di sini — jika tidak ujian mengesahkan
        // pembalikan sesuatu yang tidak pernah ada.
        jdbc.sql("""
                INSERT INTO journal_entry
                  (sp_code, entry_no, entry_date, source_type, source_document_id,
                   description, status, version)
                VALUES (:sp, :no, '2026-07-01', 'INVOICE', :id,
                        'Ujian invois', 'POSTED', 0)
                """)
                .param("sp", SP)
                .param("no", "JE-CX-" + System.nanoTime())
                .param("id", inv)
                .update();
        em.flush();
        em.clear();

        long sebelum = jdbc.sql(
                "SELECT COUNT(*) FROM journal_entry WHERE source_document_id = :id")
                .param("id", inv).query(Long.class).single();
        assertThat(sebelum).isEqualTo(1);

        payment.cancelInvoice(inv, "batal", null);
        em.flush();
        em.clear();

        long selepas = jdbc.sql(
                "SELECT COUNT(*) FROM journal_entry WHERE source_document_id = :id")
                .param("id", inv).query(Long.class).single();
        long contra = jdbc.sql("""
                SELECT COUNT(*) FROM journal_entry
                WHERE source_document_id = :id AND source_type = 'CANCELLATION'
                  AND reverses_entry_id IS NOT NULL
                """).param("id", inv).query(Long.class).single();
        long asalDibalikkan = jdbc.sql("""
                SELECT COUNT(*) FROM journal_entry
                WHERE source_document_id = :id AND source_type = 'INVOICE'
                  AND status = 'REVERSED'
                """).param("id", inv).query(Long.class).single();

        assertThat(selepas)
                .as("entri asal TIDAK dipadam — jejak audit kekal")
                .isEqualTo(2);
        assertThat(contra).isEqualTo(1);
        assertThat(asalDibalikkan)
                .as("entri asal ditanda REVERSED, bukan dibuang")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("cancelInvoice menolak RESIT — laluan salah")
    void cancelInvoiceTolakResit() {
        invois("CX-INV-10", "70.00");
        var r = payment.receivePayment(new NewPayment(SP, acc,
                new BigDecimal("70.00"), PaymentMethod.CASH, null,
                List.of(), null, null, null));
        em.flush();

        assertThatThrownBy(() ->
                payment.cancelInvoice(r.receiptDocumentId(), "salah laluan", null))
                .as("resit perlu menanda entiti Payment juga")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cancelReceipt");
    }

    @Test
    @DisplayName("batal INVOIS membebaskan idem_key — kerani boleh jana semula")
    void batalInvoisBolehJanaSemula() {
        // Produk dan period SAMA untuk kedua-dua percubaan; hanya doc_no
        // berbeza. Kalau produk berbeza, idem_key berbeza dan ujian ini
        // lulus tanpa membuktikan apa-apa.
        long pid = periodJulai();
        long prod = produk("P-REGEN", "150.00");

        long pertama = buatInvois(prod, pid, "CX-REGEN-1", "150.00").orElseThrow();

        // Sebelum batal: kunci dipegang, penjanaan semula DITOLAK.
        assertThat(buatInvois(prod, pid, "CX-REGEN-2", "150.00"))
                .as("idem_key menghalang pendua semasa invois masih aktif")
                .isEmpty();

        payment.cancelInvoice(pertama, "amaun salah", 99L);
        em.flush();
        em.clear();

        // Selepas batal: kunci dibebaskan (V52 doc_cancelled), jana semula
        // dibenarkan. Legacy: penunjuk last_charged_period kekal set dan
        // produk tersekat selamanya.
        assertThat(buatInvois(prod, pid, "CX-REGEN-3", "150.00"))
                .as("batal mesti membebaskan idem_key")
                .isPresent();

        // Dokumen batal KEKAL — dipapar dengan kesan sifar, tidak hilang.
        assertThat(jdbc.sql("SELECT status FROM financial_document WHERE id = :id")
                        .param("id", pertama).query(String.class).single())
                .isEqualTo("CANCELLED");
    }

    /** Invois atas produk dan period yang DIBERI — helper invois() mencipta
     *  produk baharu setiap kali, jadi ia tidak boleh menguji idempotency. */
    private java.util.Optional<Long> buatInvois(long produkId, long pid,
                                                String docNo, String amaun) {
        var line = new NewDocumentLine(produkId, acc, pid,
                "Yuran", null, BigDecimal.ONE, new BigDecimal(amaun), BigDecimal.ONE,
                new BigDecimal(amaun), BigDecimal.ZERO,
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), false);
        var id = documents.createInvoice(new NewInvoice(SP, acc, pid,
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31),
                docNo, List.of(line)));
        em.flush();
        return id;
    }

    @Test
    @DisplayName("batal INVOIS dua kali ditolak")
    void batalInvoisDuaKali() {
        long inv = invois("CX-INV-11", "90.00");
        payment.cancelInvoice(inv, "sekali", null);
        em.flush();

        assertThatThrownBy(() -> payment.cancelInvoice(inv, "dua kali", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sudah dibatalkan");
    }
}
