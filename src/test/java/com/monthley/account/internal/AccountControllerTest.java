package com.monthley.account.internal;

import com.monthley.shared.TenantContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import com.monthley.shared.GenMode;
import com.monthley.payment.api.*;
import com.monthley.billing.internal.InvoiceGenerationService;
import com.monthley.billing.internal.BillingContext;
import com.monthley.ledger.api.GlAccounts;
import com.monthley.ledger.internal.ChartOfAccountSeeder;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Menguji AccountController.create() — laluan cipta akaun penuh (teras + ahli +
 * dua alamat). Tanpa ujian ini, 24 field SaveAccountRequest tidak tersentuh.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AccountControllerTest {

    @Autowired AccountController controller;
    @Autowired PaymentPort payment;
    @Autowired InvoiceGenerationService billing;
    @Autowired ChartOfAccountSeeder seeder;
    @PersistenceContext EntityManager em;

    @BeforeEach
    void setup() {
        em.createNativeQuery("""
            INSERT IGNORE INTO service_provider (sp_code, name, status, created_at, updated_at, version)
            VALUES ('SPX', 'Akaun Test', 'ACTIVE', NOW(), NOW(), 0)
            """).executeUpdate();
        TenantContext.set("SPX");
        // Semakan peranan kini dikuatkuasakan pada setiap endpoint akaun.
        // SP_ADMIN memberi akses penuh; ujian yang menguji SEKATAN
        // menetapkan peranan lain secara eksplisit.
        org.springframework.security.core.context.SecurityContextHolder
                .getContext().setAuthentication(
                new org.springframework.security.authentication
                        .UsernamePasswordAuthenticationToken("admin", "n/a",
                        java.util.List.of(
                                new org.springframework.security.core.authority
                                        .SimpleGrantedAuthority("SP_SPX_SP_ADMIN"))));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    // ── Tapisan produk pada senarai akaun ────────────────────────────
    //
    // "Akaun yang tie up dengan produk" bermaksud baris LANGGANAN wujud.
    // Bukan baris dokumen: produk yang baru ditambah belum ada invois,
    // dan akaun itu jelas melanggannya.

    private long produkUji(String kod, String harga) {
        em.createNativeQuery("""
                INSERT INTO product (sp_code, code, name, charge_frequency, unit_rate,
                                     main_product, mandatory, prorated, late_penalty,
                                     status, version)
                VALUES ('SPX', :k, :k, 'MONTHLY', :h, 0,0,0,0, 'ACTIVE', 0)
                """).setParameter("k", kod).setParameter("h", new BigDecimal(harga))
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM product WHERE sp_code='SPX' AND code = :k")
                .setParameter("k", kod).getSingleResult()).longValue();
    }

    private long akaunUji(String no) {
        em.createNativeQuery("""
                INSERT INTO account (sp_code, account_no, account_name, status)
                VALUES ('SPX', :no, :no, 'ACTIVE')
                """).setParameter("no", no).executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM account WHERE sp_code='SPX' AND account_no = :no")
                .setParameter("no", no).getSingleResult()).longValue();
    }

    private void langganUji(long akaun, long produk, String status, String tamat) {
        em.createNativeQuery("""
                INSERT INTO account_subscription
                  (sp_code, account_id, product_id, quantity, status, end_date, version)
                VALUES ('SPX', :acc, :prod, 1, :st, :tamat, 0)
                """).setParameter("acc", akaun).setParameter("prod", produk)
                .setParameter("st", status)
                .setParameter("tamat", tamat == null ? null : java.time.LocalDate.parse(tamat))
                .executeUpdate();
        em.flush();
    }

    private List<String> cariIkutProduk(Long produkId) {
        return controller.list(true, null, produkId, null, null, null, 0, 50)
                .items().stream()
                .map(AccountController.AccountDto::no)
                .toList();
    }

    // ── Semakan peranan ──────────────────────────────────────────────
    //
    // Peranan ialah TUGAS, bukan tingkat:
    //   SP_ADMIN  semua kecuali manual payment
    //   CLERK     bayaran, adhoc, adjustment — TIDAK mencipta akaun
    //   VIEWER    lihat, cari, cetak penyata
    //
    // VIEWER wujud untuk pengawal pondok jaga JMB.

    private void sebagai(String role) {
        org.springframework.security.core.context.SecurityContextHolder
                .getContext().setAuthentication(
                new org.springframework.security.authentication
                        .UsernamePasswordAuthenticationToken("u", "n/a",
                        java.util.List.of(
                                new org.springframework.security.core.authority
                                        .SimpleGrantedAuthority("SP_SPX_" + role))));
    }

    /**
     * Bentuk yang sama seperti createsFullAccount — 33 medan, dan
     * mengira null sendiri mudah tersasar.
     */
    private AccountController.SaveAccountRequest akaunBaharu(String no) {
        return new AccountController.SaveAccountRequest(
                no, "Nama " + no, null, "MONTHLY",
                java.time.LocalDate.of(2026, 1, 1), null,
                "Ahli Ujian", "900101-01-1234", "ujian@contoh.com", "0123456789",
                "No 1", "Jalan Satu", "Taman Dua", null,
                "40000", "Selangor", "MY",
                "Penyewa", "penyewa@contoh.com", "0198765432",
                "No 2", "Jalan Tiga", null, null,
                "50000", "Wp Kuala Lumpur", "MY",
                null, null, null, null, null, java.util.List.of());
    }

    @Test
    @DisplayName("VIEWER: boleh LIHAT, tidak boleh CIPTA")
    void viewerBacaSahaja() {
        // Pengawal pondok jaga memerlukan senarai dan penyata. Tanpa
        // semakan ini dia boleh mencipta akaun, dan tiada apa
        // menghalangnya kecuali skrin yang menyembunyikan butang.
        sebagai("VIEWER");

        assertThatCode(() -> controller.list(true, null, null, null, null, null, 0, 10))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> controller.create(akaunBaharu("VW-1")))
                .isInstanceOf(com.monthley.shared.Access.AccessDeniedException.class)
                .hasMessageContaining("SP_ADMIN");
    }

    @Test
    @DisplayName("CLERK: boleh LIHAT, tidak boleh CIPTA")
    void clerkTidakCipta() {
        // Kerani menerima bayaran. Admin yang perlu memegang kutipan
        // diberi CLERK juga; kerani yang perlu mencipta akaun diberi
        // SP_ADMIN juga. Peranan ialah tugas, bukan tingkat.
        sebagai("CLERK");

        assertThatCode(() -> controller.list(true, null, null, null, null, null, 0, 10))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> controller.create(akaunBaharu("CL-1")))
                .isInstanceOf(com.monthley.shared.Access.AccessDeniedException.class);
    }

    @Test
    @DisplayName("VIEWER: tidak boleh melanggan produk secara pukal")
    void viewerTidakLanggan() {
        sebagai("VIEWER");
        long p = produkUji("P-ROLE-A", "50.00");

        assertThatThrownBy(() -> controller.bulkSubscribe(
                new AccountController.BulkSubscribeRequest(p, java.util.List.of())))
                .isInstanceOf(com.monthley.shared.Access.AccessDeniedException.class);
    }

    @Test
    @DisplayName("Tiada peranan langsung: ditolak")
    void tiadaPerananDitolak() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> controller.list(true, null, null, null, null, null, 0, 10))
                .isInstanceOf(com.monthley.shared.Access.AccessDeniedException.class);
    }

    // ── Caj penggunaan pada skrin akaun ──────────────────────────────

    private long cajPenggunaan(long akaunId, long produkId, String status, Long docId) {
        long per = ((Number) em.createNativeQuery(
                "SELECT period_id FROM fi_period WHERE charge_code='MO' "
                + "AND YEAR(start_dt)=2026 AND MONTH(start_dt)=7")
                .getSingleResult()).longValue();
        em.createNativeQuery("""
                INSERT INTO account_usage_charge
                  (sp_code, account_id, product_id, period_id,
                   quantity, amount, status, document_id)
                VALUES ('SPX', :acc, :prod, :per, 5, 125.00, :st, :doc)
                """).setParameter("acc", akaunId).setParameter("prod", produkId)
                .setParameter("per", per).setParameter("st", status)
                .setParameter("doc", docId).executeUpdate();
        em.flush();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM account_usage_charge WHERE account_id = :acc "
                + "ORDER BY id DESC LIMIT 1")
                .setParameter("acc", akaunId).getSingleResult()).longValue();
    }

    @Test
    @DisplayName("Senarai caj: belum dibil DAN sudah dibil, dengan penanda")
    void senaraiCajPenggunaan() {
        bolehTulis();
        long p = produkUji("P-USG-A", "25.00");
        long acc = akaunUji("USG-1");
        cajPenggunaan(acc, p, "PENDING", null);
        em.clear();

        var senarai = controller.usage(acc);

        assertThat(senarai).singleElement().satisfies(u -> {
            assertThat(u.pending()).isTrue();
            assertThat(u.amount()).isEqualByComparingTo("125.00");
            assertThat(u.periodName()).as("nama tempoh, bukan id").contains("July");
        });
    }

    @Test
    @DisplayName("Padam caj BELUM dibil: berjaya")
    void padamCajBelumDibil() {
        // Kerani menyemak sebelum menjana bil. Yang tersilap dimuat naik
        // boleh dibuang tanpa membatalkan apa-apa dokumen.
        bolehTulis();
        long p = produkUji("P-USG-B", "25.00");
        long acc = akaunUji("USG-2");
        long id = cajPenggunaan(acc, p, "PENDING", null);
        em.clear();

        var resp = controller.deleteUsage(acc, id);

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        em.flush();
        em.clear();
        assertThat(controller.usage(acc)).isEmpty();
    }

    @Test
    @DisplayName("Padam caj SUDAH dibil: ditolak, baris kekal")
    void padamCajSudahDibilDitolak() {
        // Baris yang sudah dibil menjadi baris invois. Memadamnya
        // meninggalkan invois tanpa asal — untuk membatalkannya,
        // batalkan dokumen itu.
        bolehTulis();
        long p = produkUji("P-USG-C", "25.00");
        long acc = akaunUji("USG-3");
        long id = cajPenggunaan(acc, p, "INVOICED", null);
        em.clear();

        var resp = controller.deleteUsage(acc, id);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        em.clear();
        assertThat(controller.usage(acc))
                .as("baris kekal sebagai jejak")
                .hasSize(1);
    }

    @Test
    @DisplayName("Padam caj akaun LAIN: ditolak")
    void padamCajAkaunLainDitolak() {
        // Tanpa semakan akaun, kerani boleh memadam caj akaun lain
        // dengan meneka id.
        bolehTulis();
        long p = produkUji("P-USG-D", "25.00");
        long acc1 = akaunUji("USG-4A");
        long acc2 = akaunUji("USG-4B");
        long id = cajPenggunaan(acc1, p, "PENDING", null);
        em.clear();

        assertThat(controller.deleteUsage(acc2, id).getStatusCode().value())
                .isEqualTo(400);
        em.clear();
        assertThat(controller.usage(acc1)).hasSize(1);
    }

    // ── Songsang: akaun yang BELUM melanggan ─────────────────────────

    private List<String> cariBelumLanggan(Long produkId) {
        return controller.list(true, null, null, produkId, null, null, 0, 50)
                .items().stream()
                .map(AccountController.AccountDto::no)
                .toList();
    }

    @Test
    @DisplayName("excludeProduct: akaun yang MELANGGAN ditapis keluar")
    void songsangTapisYangMelanggan() {
        long p = produkUji("P-EX-A", "50.00");
        long sudah = akaunUji("EX-SUDAH");
        akaunUji("EX-BELUM");
        langganUji(sudah, p, "ACTIVE", null);
        em.clear();

        assertThat(cariBelumLanggan(p))
                .contains("EX-BELUM")
                .doesNotContain("EX-SUDAH");
    }

    @Test
    @DisplayName("excludeProduct: langganan TAMAT boleh melanggan semula")
    void songsangLangganTamatMuncul() {
        // Guard CASE-007 hanya menyekat langganan ACTIVE. Akaun yang
        // berhenti melanggan boleh melanggan semula, dan senarai "siapa
        // boleh ditambah" mesti menunjukkannya — kalau tidak kerani
        // tidak boleh menambahnya melalui dialog langsung.
        long p = produkUji("P-EX-B", "60.00");
        long tamat = akaunUji("EX-TAMAT");
        langganUji(tamat, p, "ENDED", "2025-12-31");
        em.clear();

        assertThat(cariBelumLanggan(p)).contains("EX-TAMAT");
    }

    // ── Langganan pukal (skrin Produk, Add Account) ──────────────────

    private void bolehTulis() {
        org.springframework.security.core.context.SecurityContextHolder
                .getContext().setAuthentication(
                new org.springframework.security.authentication
                        .UsernamePasswordAuthenticationToken("admin", "n/a",
                        java.util.List.of(
                                new org.springframework.security.core.authority
                                        .SimpleGrantedAuthority("SP_SPX_SP_ADMIN"))));
    }

    private long bilanganLanggan(long produkId) {
        return ((Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM account_subscription WHERE product_id = :p")
                .setParameter("p", produkId).getSingleResult()).longValue();
    }

    @Test
    @DisplayName("Pukal: semua akaun dilanggan, kuantiti dan tarikh dihormati")
    void pukalMenambahSemua() {
        bolehTulis();
        long p = produkUji("P-BULK-A", "50.00");
        long a1 = akaunUji("BULK-1");
        long a2 = akaunUji("BULK-2");
        em.flush();

        var hasil = controller.bulkSubscribe(new AccountController.BulkSubscribeRequest(
                p, java.util.List.of(
                        new AccountController.BulkLine(a1, new java.math.BigDecimal("2"),
                                java.time.LocalDate.of(2026, 8, 1), null),
                        new AccountController.BulkLine(a2, null, null, null))));
        em.flush();
        em.clear();

        assertThat(hasil.getBody().ditambah()).isEqualTo(2);
        assertThat(hasil.getBody().dilangkau()).isZero();

        var r = em.createNativeQuery("""
                SELECT quantity, start_date FROM account_subscription
                WHERE  account_id = :a AND product_id = :p
                """).setParameter("a", a1).setParameter("p", p).getSingleResult();
        assertThat(((Object[]) r)[0]).isEqualTo(new java.math.BigDecimal("2.0000"));
        assertThat(tarikhUji(((Object[]) r)[1])).isEqualTo(java.time.LocalDate.of(2026, 8, 1));

        // Kuantiti null -> 1, bukan null. Baris tanpa kuantiti tidak
        // boleh dibil.
        assertThat(em.createNativeQuery(
                "SELECT quantity FROM account_subscription WHERE account_id = :a AND product_id = :p")
                .setParameter("a", a2).setParameter("p", p).getSingleResult())
                .isEqualTo(new java.math.BigDecimal("1.0000"));
    }

    @Test
    @DisplayName("Pukal: akaun yang SUDAH melanggan dilangkau, bukan menggagalkan kelompok")
    void pukalPenduaDilangkau() {
        // Frontend menapis akaun yang sudah melanggan, tetapi senarai
        // boleh basi antara memuat dan menyimpan — kerani lain menambah
        // langganan sementara dialog terbuka.
        //
        // Menggagalkan keseluruhan kelompok kerana satu pendua bermakna
        // sembilan puluh sembilan langganan yang sah hilang.
        bolehTulis();
        long p = produkUji("P-BULK-B", "60.00");
        long lama = akaunUji("BULK-LAMA");
        long baru = akaunUji("BULK-BARU");
        langganUji(lama, p, "ACTIVE", null);
        em.flush();

        var hasil = controller.bulkSubscribe(new AccountController.BulkSubscribeRequest(
                p, java.util.List.of(
                        new AccountController.BulkLine(lama, null, null, null),
                        new AccountController.BulkLine(baru, null, null, null))));
        em.flush();

        assertThat(hasil.getBody().ditambah()).isEqualTo(1);
        assertThat(hasil.getBody().dilangkau()).isEqualTo(1);
        assertThat(hasil.getBody().sebab()).singleElement()
                .asString().contains("BULK-LAMA").contains("sudah melanggan");
        assertThat(bilanganLanggan(p)).as("tiada pendua dicipta").isEqualTo(2L);
    }

    @Test
    @DisplayName("Pukal: akaun SP lain dilangkau")
    void pukalSpLainDilangkau() {
        bolehTulis();
        long p = produkUji("P-BULK-C", "70.00");
        em.createNativeQuery("""
                INSERT IGNORE INTO service_provider (sp_code, name, status, version)
                VALUES ('SPXX', 'SP Lain', 'ACTIVE', 0)
                """).executeUpdate();
        em.createNativeQuery("""
                INSERT INTO account (sp_code, account_no, account_name, status)
                VALUES ('SPXX', 'LAIN-1', 'Akaun SP Lain', 'ACTIVE')
                """).executeUpdate();
        long lain = ((Number) em.createNativeQuery(
                "SELECT id FROM account WHERE sp_code='SPXX' AND account_no='LAIN-1'")
                .getSingleResult()).longValue();
        em.flush();

        var hasil = controller.bulkSubscribe(new AccountController.BulkSubscribeRequest(
                p, java.util.List.of(new AccountController.BulkLine(lain, null, null, null))));
        em.flush();

        assertThat(hasil.getBody().ditambah()).isZero();
        assertThat(bilanganLanggan(p)).isZero();
    }

    private static java.time.LocalDate tarikhUji(Object v) {
        if (v == null) return null;
        if (v instanceof java.time.LocalDate d) return d;
        if (v instanceof java.sql.Date d) return d.toLocalDate();
        return java.time.LocalDate.parse(v.toString());
    }

    // ── Senarai pelanggan produk (skrin Produk) ──────────────────────

    private void akaunTakAktif(String no) {
        em.createNativeQuery("""
                INSERT INTO account (sp_code, account_no, account_name, status)
                VALUES ('SPX', :no, :no, 'INACTIVE')
                """).setParameter("no", no).executeUpdate();
    }

    private long idAkaun(String no) {
        return ((Number) em.createNativeQuery(
                "SELECT id FROM account WHERE sp_code='SPX' AND account_no = :no")
                .setParameter("no", no).getSingleResult()).longValue();
    }

    @Test
    @DisplayName("Pelanggan produk: langganan TAMAT ditapis keluar")
    void pelangganTamatDitapis() {
        // end_date yang sudah lepas bermakna produk tidak lagi dicaj.
        // Senarai menjawab "siapa melanggan SEKARANG".
        long p = produkUji("P-SUB-A", "50.00");
        long kekal = akaunUji("SUB-KEKAL");
        long tamat = akaunUji("SUB-TAMAT");
        langganUji(kekal, p, "ACTIVE", null);
        langganUji(tamat, p, "ACTIVE", "2020-01-01");
        em.clear();

        var hasil = controller.byProduct(p, 0, 50);

        assertThat(hasil.total()).isEqualTo(1L);
        assertThat(hasil.items()).singleElement()
                .extracting(AccountController.SubscriberDto::accountNo)
                .isEqualTo("SUB-KEKAL");
    }

    @Test
    @DisplayName("Pelanggan produk: akaun TIDAK AKTIF dipaparkan dengan penanda")
    void pelangganTakAktifDipaparkan() {
        // Akaun ditutup yang masih melanggan tetap berkaitan. Menyembunyikannya
        // menjadikan kiraan tidak boleh dibandingkan dengan senarai.
        long p = produkUji("P-SUB-B", "60.00");
        long aktif = akaunUji("SUB-AKTIF");
        akaunTakAktif("SUB-MATI");
        long mati = idAkaun("SUB-MATI");
        langganUji(aktif, p, "ACTIVE", null);
        langganUji(mati, p, "ACTIVE", null);
        em.clear();

        var hasil = controller.byProduct(p, 0, 50);

        assertThat(hasil.total()).as("kedua-duanya melanggan").isEqualTo(2L);
        assertThat(hasil.aktif()).as("satu sahaja akaunnya aktif").isEqualTo(1L);
        assertThat(hasil.items())
                .extracting(AccountController.SubscriberDto::accountActive)
                .containsExactlyInAnyOrder(true, false);
    }

    @Test
    @DisplayName("Pelanggan produk: start_date BERISI dipetakan betul")
    void pelangganStartDateBerisi() {
        // Ujian lain menggunakan start_date NULL, jadi cabang cast tidak
        // pernah dijalankan dan ClassCastException hanya muncul apabila
        // seseorang mengisi tarikh pada data sebenar: senarai kosong
        // sementara kiraan menunjukkan enam.
        //
        // Connector/J memulangkan LocalDate, bukan java.sql.Date.
        long p = produkUji("P-SUB-D", "40.00");
        long acc = akaunUji("SUB-TARIKH");
        langganUji(acc, p, "ACTIVE", null);
        em.createNativeQuery("""
                UPDATE account_subscription SET start_date = '2026-06-01'
                WHERE  account_id = :a AND product_id = :p
                """).setParameter("a", acc).setParameter("p", p).executeUpdate();
        em.flush();
        em.clear();

        var hasil = controller.byProduct(p, 0, 50);

        assertThat(hasil.items()).singleElement()
                .extracting(AccountController.SubscriberDto::startDate)
                .isEqualTo(java.time.LocalDate.of(2026, 6, 1));
    }

    @Test
    @DisplayName("Pelanggan produk: kategori akaun dipaparkan")
    void pelangganKategoriDipaparkan() {
        long p = produkUji("P-SUB-E", "45.00");
        long acc = akaunUji("SUB-KAT");
        em.createNativeQuery("""
                INSERT INTO account_category (sp_code, code, name, version)
                VALUES ('SPX', 'K1', 'BLOK UJIAN', 0)
                """).executeUpdate();
        long kat = ((Number) em.createNativeQuery(
                "SELECT id FROM account_category WHERE sp_code='SPX' AND code='K1'")
                .getSingleResult()).longValue();
        em.createNativeQuery("UPDATE account SET category_id = :k WHERE id = :a")
                .setParameter("k", kat).setParameter("a", acc).executeUpdate();
        langganUji(acc, p, "ACTIVE", null);
        em.flush();
        em.clear();

        assertThat(controller.byProduct(p, 0, 50).items()).singleElement()
                .extracting(AccountController.SubscriberDto::categoryName)
                .isEqualTo("BLOK UJIAN");
    }

    @Test
    @DisplayName("Pelanggan produk: ADHOC-SALES dikecualikan")
    void pelangganAdhocDikecualikan() {
        // Akaun teknikal, bukan pelanggan. Ia dikecualikan daripada
        // senarai akaun dan mesti dikecualikan di sini juga — kalau
        // tidak dua skrin memberi kiraan berbeza.
        long p = produkUji("P-SUB-C", "70.00");
        em.createNativeQuery("""
                INSERT INTO account (sp_code, account_no, account_name, account_type, status)
                VALUES ('SPX', 'ADHOC-SALES', 'Jualan Adhoc', 'ADHOC', 'ACTIVE')
                """).executeUpdate();
        langganUji(idAkaun("ADHOC-SALES"), p, "ACTIVE", null);
        em.clear();

        assertThat(controller.byProduct(p, 0, 50).total()).isZero();
    }

    @Test
    @DisplayName("Tapis produk: hanya akaun yang MELANGGAN produk itu")
    void tapisProdukIkutLangganan() {
        long pA = produkUji("P-TAPIS-A", "50.00");
        long pB = produkUji("P-TAPIS-B", "80.00");
        long ada = akaunUji("TP-ADA");
        long lain = akaunUji("TP-LAIN");
        akaunUji("TP-KOSONG");            // tiada langganan langsung
        langganUji(ada, pA, "ACTIVE", null);
        langganUji(lain, pB, "ACTIVE", null);
        em.clear();

        assertThat(cariIkutProduk(pA)).containsExactly("TP-ADA");
        assertThat(cariIkutProduk(pB)).containsExactly("TP-LAIN");
    }

    @Test
    @DisplayName("Tapis produk: langganan TAMAT TEMPOH tetap muncul")
    void tapisProdukLangganaTamatTetapMuncul() {
        // Baris masih wujud dalam senarai langganan akaun; ia hanya hilang
        // apabila kerani membuangnya. Yang dilihat kerani pada skrin akaun
        // ialah yang menentukan sama ada akaun itu muncul.
        long p = produkUji("P-TAPIS-C", "30.00");
        long tamat = akaunUji("TP-TAMAT");
        akaunUji("TP-TAMAT-LAIN");        // tanpa langganan produk ini
        langganUji(tamat, p, "ENDED", "2025-12-31");
        em.clear();

        assertThat(cariIkutProduk(p)).containsExactly("TP-TAMAT");
    }

    @Test
    @DisplayName("Tapis produk NULL: semua akaun, tiada regresi")
    void tapisProdukNullSemua() {
        long p = produkUji("P-TAPIS-D", "20.00");
        long acc = akaunUji("TP-NULL-1");
        akaunUji("TP-NULL-2");
        langganUji(acc, p, "ACTIVE", null);
        em.clear();

        assertThat(cariIkutProduk(null))
                .contains("TP-NULL-1", "TP-NULL-2");
    }

    @Test
    @DisplayName("create() simpan teras + ahli + dua alamat")
    void createsFullAccount() {
        var req = new AccountController.SaveAccountRequest(
                "ACC-100", "Pemilik Unit A", null, "MONTHLY",
                java.time.LocalDate.of(2026, 1, 1), null,
                // ahli
                "Ali bin Ahmad", "900101-01-1234", "ali@test.com", "0123456789",
                // alamat akaun
                "No 1", "Jalan Satu", "Taman Dua", null,
                "40000", "Selangor", "MY",
                // billto
                "Penyewa Baba", "baba@test.com", "0198765432",
                "No 2", "Jalan Tiga", null, null,
                "50000", "Wp Kuala Lumpur", "MY",
                null, null, null, null, null, java.util.List.of());

        var resp = controller.create(req);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();

        // Sahkan tersimpan penuh
        Object[] row = (Object[]) em.createNativeQuery("""
                SELECT account_name, member_name, member_id_no, addr_line1, addr_postcode,
                       addr_state, billto_name, billto_addr_line1, billto_postcode, billto_state
                FROM account WHERE sp_code='SPX' AND account_no='ACC-100'
                """).getSingleResult();

        assertThat(row[0]).isEqualTo("Pemilik Unit A");
        assertThat(row[1]).isEqualTo("Ali bin Ahmad");
        assertThat(row[2]).isEqualTo("900101-01-1234");
        assertThat(row[3]).isEqualTo("No 1");
        assertThat(row[4]).isEqualTo("40000");
        assertThat(row[5]).isEqualTo("Selangor");
        assertThat(row[6]).isEqualTo("Penyewa Baba");     // billto beza dari member
        assertThat(row[7]).isEqualTo("No 2");
        assertThat(row[8]).isEqualTo("50000");
        assertThat(row[9]).isEqualTo("Wp Kuala Lumpur");
    }

    @Test
    @DisplayName("create() dengan langganan produk -> account_subscription terisi")
    void createsWithSubscriptions() {
        // Cipta produk dulu
        em.createNativeQuery("""
            INSERT INTO product (sp_code, code, name, charge_frequency, unit_rate,
                                 main_product, mandatory, prorated, late_penalty, status,
                                 created_at, updated_at, version)
            VALUES ('SPX', 'PRD1', 'Sewa Unit', 'MONTHLY', 500.00, 0, 0, 0, 0, 'ACTIVE', NOW(), NOW(), 0)
            """).executeUpdate();
        Long prodId = ((Number) em.createNativeQuery(
                "SELECT id FROM product WHERE sp_code='SPX' AND code='PRD1'").getSingleResult()).longValue();

        var sub = new AccountController.SubLine(
                prodId, new java.math.BigDecimal("2"),
                java.time.LocalDate.of(2026, 1, 1), null, null);

        var req = new AccountController.SaveAccountRequest(
                "ACC-SUB", "Akaun Langgan", null, "MONTHLY",
                null, null,
                null, null, null, null,
                null, null, null, null, null, null, null,
                "Billto", null, null, null, null, null, null, null, null, null,
                null, null, null, null, null,
                java.util.List.of(sub));

        var resp = controller.create(req);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();

        // Sahkan subscription terisi
        Long accId = ((Number) em.createNativeQuery(
                "SELECT id FROM account WHERE sp_code='SPX' AND account_no='ACC-SUB'")
                .getSingleResult()).longValue();
        Long count = ((Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM account_subscription WHERE account_id = :a AND product_id = :p")
                .setParameter("a", accId).setParameter("p", prodId).getSingleResult()).longValue();
        assertThat(count).isEqualTo(1L);

        // qty betul
        java.math.BigDecimal qty = (java.math.BigDecimal) em.createNativeQuery(
                "SELECT quantity FROM account_subscription WHERE account_id = :a")
                .setParameter("a", accId).getSingleResult();
        assertThat(qty).isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("create() tolak account_no berganda")
    void rejectsDuplicateNo() {
        var req = new AccountController.SaveAccountRequest(
                "ACC-DUP", "Pertama", null, null, null, null,
                null, null, null, null,
                null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, java.util.List.of());
        assertThat(controller.create(req).getStatusCode().is2xxSuccessful()).isTrue();

        // Kedua dengan no sama -> ditolak
        var dup = controller.create(req);
        assertThat(dup.getStatusCode().is4xxClientError()).isTrue();
    }

    @Test
    @DisplayName("update() ubah field + status INACTIVE, account_no/opening kekal")
    void updatesAccount() {
        // Cipta akaun dulu
        var create = new AccountController.SaveAccountRequest(
                "EDIT-1", "Nama Asal", null, "MONTHLY", null, null,
                null, null, null, null,
                null, null, null, null, null, null, null,
                "Billto Asal", null, null, null, null, null, null, null, null, null,
                null, null, java.math.BigDecimal.valueOf(500), null, null, java.util.List.of());
        Long accId = (Long) ((java.util.Map<?,?>) controller.create(create).getBody()).get("id");

        // Edit: tukar nama + status INACTIVE
        var edit = new AccountController.EditAccountRequest(
                "Nama Baru", null, "INACTIVE", "MONTHLY", null,
                null, null, "catatan",
                null, null, null, null, null, null, null,
                "Billto Baru", null, null, null, null, null, null, null, null, null, null,
                java.util.List.of());
        var resp = controller.update(accId, edit);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();

        Object[] row = (Object[]) em.createNativeQuery("""
                SELECT account_name, status, billto_name, opening_amount, account_no
                FROM account WHERE id = :id
                """).setParameter("id", accId).getSingleResult();
        assertThat(row[0]).isEqualTo("Nama Baru");
        assertThat(row[1]).isEqualTo("INACTIVE");
        assertThat(row[2]).isEqualTo("Billto Baru");
        assertThat(((Number) row[3]).intValue()).isEqualTo(500);   // opening KEKAL
        assertThat(row[4]).isEqualTo("EDIT-1");                     // account_no KEKAL
    }

    @Test
    @DisplayName("update() delete subscription -> status ENDED (row kekal)")
    void endsSubscriptionOnDelete() {
        // Produk + akaun + subscription
        em.createNativeQuery("""
            INSERT INTO product (sp_code, code, name, charge_frequency, unit_rate,
                                 main_product, mandatory, prorated, late_penalty, status,
                                 created_at, updated_at, version)
            VALUES ('SPX', 'EP1', 'Prod Edit', 'MONTHLY', 100.00, 0,0,0,0,'ACTIVE',NOW(),NOW(),0)
            """).executeUpdate();
        Long prodId = ((Number) em.createNativeQuery(
                "SELECT id FROM product WHERE sp_code='SPX' AND code='EP1'").getSingleResult()).longValue();

        var create = new AccountController.SaveAccountRequest(
                "EDIT-2", "Akaun Sub", null, "MONTHLY", null, null,
                null,null,null,null, null,null,null,null,null,null,null,
                "Billto", null,null,null,null,null,null,null,null,null,
                null,null,null,null,null,
                java.util.List.of(new AccountController.SubLine(
                        prodId, java.math.BigDecimal.ONE,
                        java.time.LocalDate.of(2026,1,1), null, null)));
        Long accId = (Long) ((java.util.Map<?,?>) controller.create(create).getBody()).get("id");
        Long subId = ((Number) em.createNativeQuery(
                "SELECT id FROM account_subscription WHERE account_id = :a")
                .setParameter("a", accId).getSingleResult()).longValue();

        // Delete subscription (deleted=true)
        var edit = new AccountController.EditAccountRequest(
                "Akaun Sub", null, "ACTIVE", "MONTHLY", null,
                null,null,null, null,null,null,null,null,null,null,
                "Billto", null,null,null,null,null,null,null,null,null,null,
                java.util.List.of(new AccountController.EditSubLine(
                        subId, prodId, java.math.BigDecimal.ONE,
                        java.time.LocalDate.of(2026,1,1), null, null, true)));
        controller.update(accId, edit);

        // Row MASIH ada, status ENDED
        String status = (String) em.createNativeQuery(
                "SELECT status FROM account_subscription WHERE id = :id")
                .setParameter("id", subId).getSingleResult();
        assertThat(status).isEqualTo("ENDED");
    }

    @Test
    @DisplayName("statement() aras txn — invois pecah per line + knock + advance, baki berjalan descending")
    void statementAtTxnLevel() {
        // SP + COA + produk MONTHLY RM80 + akaun + langganan.
        em.createNativeQuery("""
            INSERT IGNORE INTO service_provider (sp_code, name, status, created_at, updated_at, version)
            VALUES ('SPS', 'Statement Test', 'ACTIVE', NOW(), NOW(), 0)
            """).executeUpdate();
        seeder.seedFor("SPS");
        TenantContext.set("SPS");
        // Peranan diikat kepada TENANT: hasRole membina authority sebagai
        // SP_<tenant>_<role>, jadi SP_SPX_SP_ADMIN tidak berguna di sini.
        // Itu pengasingan penyewa berfungsi seperti direka.
        org.springframework.security.core.context.SecurityContextHolder
                .getContext().setAuthentication(
                new org.springframework.security.authentication
                        .UsernamePasswordAuthenticationToken("admin", "n/a",
                        java.util.List.of(
                                new org.springframework.security.core.authority
                                        .SimpleGrantedAuthority("SP_SPS_SP_ADMIN"))));

        em.createNativeQuery("""
            INSERT INTO product (sp_code, code, name, charge_frequency, unit_rate,
                                 main_product, mandatory, prorated, late_penalty, status,
                                 created_at, updated_at, version)
            VALUES ('SPS', 'MF', 'Maintenance', 'MONTHLY', 80.00, 0,0,0,0,'ACTIVE', NOW(), NOW(), 0)
            """).executeUpdate();
        Long productId = ((Number) em.createNativeQuery(
                "SELECT id FROM product WHERE sp_code='SPS' AND code='MF'").getSingleResult()).longValue();

        em.createNativeQuery("""
            INSERT INTO account (sp_code, account_no, account_name, charge_frequency,
                                 start_date, status, cached_balance, created_at, updated_at, version)
            VALUES ('SPS', 'SACC', 'Statement Payer', 'MONTHLY', '2026-01-01', 'ACTIVE', 0, NOW(), NOW(), 0)
            """).executeUpdate();
        Long accId = ((Number) em.createNativeQuery(
                "SELECT id FROM account WHERE sp_code='SPS' AND account_no='SACC'").getSingleResult()).longValue();

        em.createNativeQuery("""
            INSERT INTO account_subscription (sp_code, account_id, product_id, quantity,
                                              start_date, status, created_at, updated_at, version)
            VALUES ('SPS', :acc, :prod, 1, '2026-01-01', 'ACTIVE', NOW(), NOW(), 0)
            """).setParameter("acc", accId).setParameter("prod", productId).executeUpdate();

        // Jana 3 bulan invois (Jan, Feb, Mac 2026) -> 3 invois RM80 = 240.
        BillingContext ctx = BillingContext.of("SPS", BigDecimal.ZERO,
                GlAccounts.ACCOUNTS_RECEIVABLE, GlAccounts.TAX_PAYABLE, GlAccounts.SERVICE_INCOME);
        billing.generateForSp("SPS", YearMonth.of(2026, 1), GenMode.CURRENT, ctx);
        billing.generateForSp("SPS", YearMonth.of(2026, 2), GenMode.CURRENT, ctx);
        billing.generateForSp("SPS", YearMonth.of(2026, 3), GenMode.CURRENT, ctx);
        em.flush();

        // Bayar RM300 -> knock 3x80=240, advance 60.
        PaymentResult r = payment.receivePayment(new NewPayment(
                "SPS", accId, new BigDecimal("300.00"),
                PaymentMethod.FPX, "MP-STMT-1", List.of(), null, null, null));
        em.flush();
        assertThat(r.allocated()).isEqualByComparingTo("240.00");
        assertThat(r.deposit()).isEqualByComparingTo("60.00");   // advance (rename tertunggak)

        var resp = controller.statement(accId, 2026, 0, 100);

        // BENTUK BERUBAH (ADR 0010 keputusan 3). Versi terdahulu ujian ini
        // menjangka 7 baris: 3 baris-invois + 3 knock alokasi + 1 baris
        // 'advance' yang DIKARANG daripada (resit - SUM alokasi).
        //
        // Baris advance itu tidak wujud sebagai rekod. Ia jambatan antara
        // susun-atur-ikut-alokasi dan baki-ikut-dokumen — corak legacy yang
        // CASE-004 bedah. Di bawah ADR 0009 baki digerakkan oleh DOKUMEN
        // sahaja, jadi jurang itu tidak wujud dan jambatan tidak diperlukan.
        //
        // Sekarang: 3 invois + 1 resit = 4 baris. Alokasi turun menjadi
        // sub-baris yang tidak menggerakkan baki.
        //
        // BAKI PENUTUP TIDAK BERUBAH: -60.00 sebelum dan selepas. Hanya
        // susun atur berbeza; angka yang SP lihat kekal sama.
        assertThat(resp.total()).isEqualTo(4);
        assertThat(resp.lines()).hasSize(4);
        assertThat(resp.closingBalance()).isEqualByComparingTo("-60.00");

        // Descending: baris pertama = resit, amaun PENUH -300 (bukan -240
        // diikuti baris advance -60).
        var first = resp.lines().get(0);
        assertThat(first.docType()).isEqualTo("RECEIPT");
        assertThat(first.amount()).isEqualByComparingTo("-300.00");
        assertThat(first.balance()).isEqualByComparingTo("-60.00");

        // Tiga alokasi @80 menjadi sub-baris resit, bukan baris sendiri.
        assertThat(first.matches()).hasSize(3);
        assertThat(first.matches()).allSatisfy(m ->
                assertThat(m.amount()).isEqualByComparingTo("80.00"));

        // Baris terakhir = invois paling awal, baki 80.
        var last = resp.lines().get(resp.lines().size() - 1);
        assertThat(last.docType()).isEqualTo("INVOICE");
        assertThat(last.amount()).isEqualByComparingTo("80.00");
        assertThat(last.balance()).isEqualByComparingTo("80.00");

        // Tiada baris 'advance' di mana-mana.
        assertThat(resp.lines())
                .extracting(AccountController.StatementLine::item)
                .noneMatch(x -> x != null && x.toLowerCase().contains("advance"));

        long invLines = resp.lines().stream()
                .filter(l -> "INVOICE".equals(l.docType())).count();
        assertThat(invLines).isEqualTo(3);
    }

    @Test
    @DisplayName("myAccounts() — akaun linked ikut payer_user_id, rentas SP, isolation")
    void myAccountsListsLinkedOnly() {
        seeder.seedFor("SPX");   // SPX di-seed dalam @BeforeEach setup()

        // Seed dua pengguna (FK payer_user_id -> app_user).
        em.createNativeQuery("""
            INSERT INTO app_user (email, full_name, status, created_at, updated_at, version)
            VALUES ('mine@test.com', 'User Mine', 'ACTIVE', NOW(), NOW(), 0),
                   ('other@test.com', 'User Other', 'ACTIVE', NOW(), NOW(), 0)
            """).executeUpdate();
        Long uMine  = ((Number) em.createNativeQuery(
                "SELECT id FROM app_user WHERE email='mine@test.com'").getSingleResult()).longValue();
        Long uOther = ((Number) em.createNativeQuery(
                "SELECT id FROM app_user WHERE email='other@test.com'").getSingleResult()).longValue();

        // Dua akaun: satu milik uMine, satu milik uOther.
        em.createNativeQuery("""
            INSERT INTO account (sp_code, account_no, account_name, charge_frequency,
                                 start_date, status, cached_balance, payer_user_id,
                                 created_at, updated_at, version)
            VALUES ('SPX', 'MINE', 'Akaun Saya', 'MONTHLY', '2026-01-01', 'ACTIVE', 0, :um, NOW(), NOW(), 0),
                   ('SPX', 'OTHER', 'Akaun Orang', 'MONTHLY', '2026-01-01', 'ACTIVE', 0, :uo, NOW(), NOW(), 0)
            """).setParameter("um", uMine).setParameter("uo", uOther).executeUpdate();

        // Invois RM100 + DEBIT_NOTE RM20 pada akaun MINE -> baki patut 120.
        Long mineAccId = ((Number) em.createNativeQuery(
                "SELECT id FROM account WHERE sp_code='SPX' AND account_no='MINE'").getSingleResult()).longValue();
        em.createNativeQuery("""
            INSERT INTO financial_document (sp_code, doc_no, doc_type, doc_date, account_id,
                                            currency, amount, tax_amount, status, created_at, updated_at, version)
            VALUES ('SPX', 'INV-MINE', 'INVOICE', '2026-01-01', :acc, 'MYR', 100.00, 0.00, 'ACTIVE', NOW(), NOW(), 0),
                   ('SPX', 'DN-MINE', 'DEBIT_NOTE', '2026-01-02', :acc, 'MYR', 20.00, 0.00, 'ACTIVE', NOW(), NOW(), 0)
            """).setParameter("acc", mineAccId).executeUpdate();
        em.flush();

        // Set auth: principal name = userId (macam JwtAuthFilter set subject).
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(uMine), "n/a", java.util.List.of()));
        try {
            var mine = controller.myAccounts();
            assertThat(mine).hasSize(1);
            assertThat(mine.get(0).accountNo()).isEqualTo("MINE");
            assertThat(mine.get(0).spName()).isEqualTo("Akaun Test");   // nama SPX dari setup
            // Baki = INVOICE 100 + DEBIT_NOTE 20 = 120 (DEBIT_NOTE naik baki, ADR 0003).
            assertThat(mine.get(0).balance()).isEqualByComparingTo("120.00");
            // Ada invois -> latest = 100 (DEBIT_NOTE tak dikira sbg "invois terkini").
            assertThat(mine.get(0).latestInvoiceAmount()).isEqualByComparingTo("100.00");
        } finally {
            SecurityContextHolder.clearContext();
        }

        // User 888 nampak akaun dia sahaja (isolation).
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(uOther), "n/a", java.util.List.of()));
        try {
            var other = controller.myAccounts();
            assertThat(other).hasSize(1);
            assertThat(other.get(0).accountNo()).isEqualTo("OTHER");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    @DisplayName("myHistory() — toggle resit/invois + carian, rentas akaun pelanggan")
    void myHistoryFiltersByType() {
        seeder.seedFor("SPX");   // SPX di-seed @BeforeEach

        // User + akaun (linked payer_user_id).
        em.createNativeQuery("""
            INSERT INTO app_user (email, full_name, status, created_at, updated_at, version)
            VALUES ('hist@test.com', 'Hist User', 'ACTIVE', NOW(), NOW(), 0)
            """).executeUpdate();
        Long uHist = ((Number) em.createNativeQuery(
                "SELECT id FROM app_user WHERE email='hist@test.com'").getSingleResult()).longValue();

        em.createNativeQuery("""
            INSERT INTO product (sp_code, code, name, charge_frequency, unit_rate,
                                 main_product, mandatory, prorated, late_penalty, status,
                                 created_at, updated_at, version)
            VALUES ('SPX', 'MFH', 'Maintenance', 'MONTHLY', 80.00, 0,0,0,0,'ACTIVE', NOW(), NOW(), 0)
            """).executeUpdate();
        Long prodId = ((Number) em.createNativeQuery(
                "SELECT id FROM product WHERE sp_code='SPX' AND code='MFH'").getSingleResult()).longValue();

        em.createNativeQuery("""
            INSERT INTO account (sp_code, account_no, account_name, charge_frequency,
                                 start_date, status, cached_balance, payer_user_id,
                                 created_at, updated_at, version)
            VALUES ('SPX', 'HACC', 'Hist Payer', 'MONTHLY', '2026-01-01', 'ACTIVE', 0, :u, NOW(), NOW(), 0)
            """).setParameter("u", uHist).executeUpdate();
        Long accId = ((Number) em.createNativeQuery(
                "SELECT id FROM account WHERE sp_code='SPX' AND account_no='HACC'").getSingleResult()).longValue();

        em.createNativeQuery("""
            INSERT INTO account_subscription (sp_code, account_id, product_id, quantity,
                                              start_date, status, created_at, updated_at, version)
            VALUES ('SPX', :acc, :prod, 1, '2026-01-01', 'ACTIVE', NOW(), NOW(), 0)
            """).setParameter("acc", accId).setParameter("prod", prodId).executeUpdate();

        // Jana 2 invois (Jan + Feb) + bayar 1 resit.
        BillingContext ctx = BillingContext.of("SPX", BigDecimal.ZERO,
                GlAccounts.ACCOUNTS_RECEIVABLE, GlAccounts.TAX_PAYABLE, GlAccounts.SERVICE_INCOME);
        billing.generateForSp("SPX", YearMonth.of(2026, 1), GenMode.CURRENT, ctx);
        billing.generateForSp("SPX", YearMonth.of(2026, 2), GenMode.CURRENT, ctx);
        em.flush();
        payment.receivePayment(new NewPayment("SPX", accId, new BigDecimal("80.00"),
                PaymentMethod.FPX, "MP-H", List.of(), null, null, null));
        em.flush();

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(uHist), "n/a", java.util.List.of()));
        try {
            // INVOICE: 2 baris.
            var inv = controller.myHistory("INVOICE", null, null, null, 0, 10);
            assertThat(inv.total()).isEqualTo(2);
            assertThat(inv.items()).allMatch(h -> "INVOICE".equals(h.docType()));

            // RECEIPT: 1 baris.
            var rec = controller.myHistory("RECEIPT", null, null, null, 0, 10);
            assertThat(rec.total()).isEqualTo(1);
            assertThat(rec.items().get(0).docType()).isEqualTo("RECEIPT");
            assertThat(rec.items().get(0).amount()).isEqualByComparingTo("80.00");
            assertThat(rec.items().get(0).spName()).isEqualTo("Akaun Test");
            assertThat(rec.items().get(0).accountNo()).isEqualTo("HACC");

            // Carian: doc_no resit.
            String rcpNo = rec.items().get(0).docNo();
            var found = controller.myHistory("RECEIPT", null, null, rcpNo, 0, 10);
            assertThat(found.total()).isEqualTo(1);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
