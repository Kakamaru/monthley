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
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

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
        return controller.list(true, null, produkId, null, null, 0, 50)
                .items().stream()
                .map(AccountController.AccountDto::no)
                .toList();
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
