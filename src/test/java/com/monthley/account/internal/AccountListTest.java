package com.monthley.account.internal;

import com.monthley.account.api.AccountListPort;
import com.monthley.shared.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Senarai Akaun untuk laporan.
 *
 * Laporan yang salah menunjukkan baki pelanggan kepada JMB, jadi baki
 * mesti datang daripada sumber yang sama seperti setiap tempat lain.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AccountListTest {

    private static final String SP = "SPAL";

    @Autowired AccountListPort port;
    @PersistenceContext EntityManager em;

    @BeforeEach
    void seed() {
        em.createNativeQuery("""
                INSERT IGNORE INTO service_provider (sp_code, name, status, version)
                VALUES (:sp, 'SP Ujian Senarai Akaun', 'ACTIVE', 0)
                """).setParameter("sp", SP).executeUpdate();
        TenantContext.set(SP);
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    private long akaun(String no, String status, String jenis, Long kategori) {
        em.createNativeQuery("""
                INSERT INTO account (sp_code, account_no, account_name,
                                     account_type, category_id, status)
                VALUES (:sp, :no, :nm, :t, :c, :st)
                """).setParameter("sp", SP).setParameter("no", no)
                .setParameter("nm", "Nama " + no).setParameter("t", jenis)
                .setParameter("c", kategori).setParameter("st", status)
                .executeUpdate();
        em.flush();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM account WHERE sp_code=:sp AND account_no=:no")
                .setParameter("sp", SP).setParameter("no", no)
                .getSingleResult()).longValue();
    }

    /** Invois memberi akaun baki tertunggak. */
    private void invois(long akaunId, String amaun) {
        em.createNativeQuery("""
                INSERT INTO financial_document
                  (sp_code, doc_no, doc_type, account_id, doc_date, amount,
                   tax_amount, status, title, version)
                VALUES (:sp, :no, 'INVOICE', :acc, '2026-08-01', :amt, 0,
                        'ACTIVE', 'Invois', 0)
                """).setParameter("sp", SP)
                .setParameter("no", "AL-I" + akaunId)
                .setParameter("acc", akaunId)
                .setParameter("amt", new BigDecimal(amaun))
                .executeUpdate();
        em.flush();
    }

    private AccountListPort.Result senarai(Boolean aktif, Long kategori, String cari) {
        em.flush();
        em.clear();
        return port.accountList(new AccountListPort.Query(SP, aktif, kategori, cari));
    }

    @Test
    @DisplayName("ADHOC-SALES dikecualikan — akaun teknikal, bukan pelanggan")
    void adhocDikecualikan() {
        akaun("AL-1", "ACTIVE", null, null);
        akaun("AL-ADHOC", "ACTIVE", "ADHOC", null);

        assertThat(senarai(null, null, null).rows())
                .extracting(AccountListPort.Row::accountNo)
                .contains("AL-1")
                .doesNotContain("AL-ADHOC");
    }

    @Test
    @DisplayName("Baki daripada account_balance, sepadan dengan sub-lejar")
    void bakiDaripadaViewYangSama() {
        // Mengiranya semula di sini bermakna takrifan KEEMPAT bagi 'apa
        // itu baki akaun' — selepas account_balance, lejar SP, dan
        // Imbangan Duga. Yang menyimpang tidak kelihatan menyimpang.
        long acc = akaun("AL-BAKI", "ACTIVE", null, null);
        invois(acc, "250.00");

        var r = senarai(null, null, null);

        assertThat(r.rows())
                .filteredOn(x -> x.accountNo().equals("AL-BAKI"))
                .singleElement()
                .extracting(AccountListPort.Row::balance)
                .satisfies(b -> assertThat((BigDecimal) b).isEqualByComparingTo("250.00"));

        Object subLejar = em.createNativeQuery(
                "SELECT COALESCE(SUM(signed_amount),0) FROM account_document_entry "
                + "WHERE account_id = :a").setParameter("a", acc).getSingleResult();
        assertThat(new BigDecimal(subLejar.toString()))
                .as("sepadan dengan sub-lejar").isEqualByComparingTo("250.00");
    }

    @Test
    @DisplayName("Tapis status: aktif dan tidak aktif diasingkan")
    void tapisStatus() {
        akaun("AL-AKTIF", "ACTIVE", null, null);
        akaun("AL-MATI", "INACTIVE", null, null);

        assertThat(senarai(true, null, null).rows())
                .extracting(AccountListPort.Row::accountNo)
                .contains("AL-AKTIF").doesNotContain("AL-MATI");

        assertThat(senarai(false, null, null).rows())
                .extracting(AccountListPort.Row::accountNo)
                .contains("AL-MATI").doesNotContain("AL-AKTIF");

        var semua = senarai(null, null, null);
        assertThat(semua.activeCount()).isPositive();
        assertThat(semua.inactiveCount()).isPositive();
    }

    @Test
    @DisplayName("Tapis kategori")
    void tapisKategori() {
        em.createNativeQuery("""
                INSERT INTO account_category (sp_code, code, name, version)
                VALUES (:sp, 'ALK', 'BLOK UJIAN', 0)
                """).setParameter("sp", SP).executeUpdate();
        em.flush();
        long kat = ((Number) em.createNativeQuery(
                "SELECT id FROM account_category WHERE sp_code=:sp AND code='ALK'")
                .setParameter("sp", SP).getSingleResult()).longValue();

        akaun("AL-KAT", "ACTIVE", null, kat);
        akaun("AL-TIADA-KAT", "ACTIVE", null, null);

        var r = senarai(null, kat, null);

        assertThat(r.rows()).extracting(AccountListPort.Row::accountNo)
                .contains("AL-KAT").doesNotContain("AL-TIADA-KAT");
        assertThat(r.rows()).singleElement()
                .extracting(AccountListPort.Row::categoryName)
                .isEqualTo("BLOK UJIAN");
    }

    // ── Senarai Langganan ────────────────────────────────────────────

    private long produkUji(String kod) {
        em.createNativeQuery("""
                INSERT INTO product (sp_code, code, name, charge_frequency, unit_rate,
                                     main_product, mandatory, prorated, late_penalty,
                                     status, version)
                VALUES (:sp, :k, :n, 'MONTHLY', 50.00, 0,0,0,0, 'ACTIVE', 0)
                """).setParameter("sp", SP).setParameter("k", kod)
                .setParameter("n", "Produk " + kod).executeUpdate();
        em.flush();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM product WHERE sp_code=:sp AND code=:k")
                .setParameter("sp", SP).setParameter("k", kod)
                .getSingleResult()).longValue();
    }

    private void langgan(long akaunId, long produkId, String status, String endDate) {
        em.createNativeQuery("""
                INSERT INTO account_subscription
                  (sp_code, account_id, product_id, quantity, status, end_date, version)
                VALUES (:sp, :a, :p, 1, :st, :ed, 0)
                """).setParameter("sp", SP).setParameter("a", akaunId)
                .setParameter("p", produkId).setParameter("st", status)
                .setParameter("ed", endDate == null ? null : java.time.LocalDate.parse(endDate))
                .executeUpdate();
        em.flush();
    }

    private AccountListPort.SubResult langganan(Long kategori, Long produk, Boolean status) {
        em.flush();
        em.clear();
        return port.subscriptionList(
                new AccountListPort.SubQuery(SP, kategori, produk, status));
    }

    @Test
    @DisplayName("end_date LEPAS dikira TAMAT walaupun status ACTIVE")
    void endDateLepasDikiraTamat() {
        // Lima langganan dalam data pengeluaran mempunyai status ACTIVE
        // dengan end_date yang sudah berlalu. Bil sudah berhenti dijana
        // untuk mereka, jadi laporan menunjukkannya sebagai Tamat.
        //
        // AKAUN mereka mungkin masih aktif — soalan berbeza, dijawab
        // oleh Senarai Akaun.
        long acc = akaun("SL-1", "ACTIVE", null, null);
        long p = produkUji("SL-P1");
        langgan(acc, p, "ACTIVE", "2020-01-01");

        var r = langganan(null, null, null);

        assertThat(r.rows()).singleElement()
                .extracting(AccountListPort.SubRow::active)
                .isEqualTo(false);
        assertThat(r.endedCount()).isEqualTo(1);
        assertThat(r.activeCount()).isZero();
    }

    @Test
    @DisplayName("end_date NULL atau masa hadapan dikira AKTIF")
    void endDateHadapanAktif() {
        long acc = akaun("SL-2", "ACTIVE", null, null);
        long p1 = produkUji("SL-P2");
        long p2 = produkUji("SL-P3");
        langgan(acc, p1, "ACTIVE", null);
        langgan(acc, p2, "ACTIVE", "2099-12-31");

        var r = langganan(null, null, null);

        assertThat(r.activeCount()).isEqualTo(2);
        assertThat(r.endedCount()).isZero();
    }

    @Test
    @DisplayName("Satu baris per LANGGANAN — akaun muncul beberapa kali")
    void satuBarisPerLangganan() {
        // Akaun yang melanggan tiga produk muncul tiga kali, dengan nama
        // produk pada setiap baris.
        long acc = akaun("SL-3", "ACTIVE", null, null);
        langgan(acc, produkUji("SL-A"), "ACTIVE", null);
        langgan(acc, produkUji("SL-B"), "ACTIVE", null);
        langgan(acc, produkUji("SL-C"), "ACTIVE", null);

        var r = langganan(null, null, null);

        assertThat(r.rows()).hasSize(3)
                .allSatisfy(x -> assertThat(x.accountNo()).isEqualTo("SL-3"));
        assertThat(r.rows()).extracting(AccountListPort.SubRow::productCode)
                .containsExactlyInAnyOrder("SL-A", "SL-B", "SL-C");
    }

    @Test
    @DisplayName("Tapis status: aktif dan tamat diasingkan")
    void tapisStatusLangganan() {
        long acc = akaun("SL-4", "ACTIVE", null, null);
        langgan(acc, produkUji("SL-AKT"), "ACTIVE", null);
        langgan(acc, produkUji("SL-TMT"), "ACTIVE", "2020-01-01");

        assertThat(langganan(null, null, true).rows())
                .extracting(AccountListPort.SubRow::productCode)
                .containsExactly("SL-AKT");

        assertThat(langganan(null, null, false).rows())
                .extracting(AccountListPort.SubRow::productCode)
                .containsExactly("SL-TMT");
    }

    // ── Senarai Tunggakan ────────────────────────────────────────────

    private void resit(long akaunId, String no, String tarikh, String amaun) {
        em.createNativeQuery("""
                INSERT INTO financial_document
                  (sp_code, doc_no, doc_type, account_id, doc_date, amount,
                   tax_amount, status, title, version)
                VALUES (:sp, :no, 'RECEIPT', :acc, :dd, :amt, 0, 'ACTIVE', 'Resit', 0)
                """).setParameter("sp", SP).setParameter("no", no)
                .setParameter("acc", akaunId)
                .setParameter("dd", java.time.LocalDate.parse(tarikh))
                .setParameter("amt", new BigDecimal(amaun)).executeUpdate();
        em.flush();
    }

    private void invoisBertarikh(long akaunId, String no, String tarikh, String amaun) {
        em.createNativeQuery("""
                INSERT INTO financial_document
                  (sp_code, doc_no, doc_type, account_id, doc_date, amount,
                   tax_amount, status, title, version)
                VALUES (:sp, :no, 'INVOICE', :acc, :dd, :amt, 0, 'ACTIVE', 'Invois', 0)
                """).setParameter("sp", SP).setParameter("no", no)
                .setParameter("acc", akaunId)
                .setParameter("dd", java.time.LocalDate.parse(tarikh))
                .setParameter("amt", new BigDecimal(amaun)).executeUpdate();
        em.flush();
    }

    private AccountListPort.ArrearResult tunggakan(String asAt, boolean arrearsOnly) {
        em.flush();
        em.clear();
        return port.arrears(new AccountListPort.ArrearQuery(
                SP, java.time.LocalDate.parse(asAt), arrearsOnly));
    }

    @Test
    @DisplayName("POTRET: bayaran SELEPAS tarikh tidak mengurangkan tunggakan")
    void bayaranSelepasTarikhDiabaikan() {
        // Pelanggan berhutang RM500 pada 31 Julai dan membayar RM200 pada
        // 2 Ogos. Laporan bertarikh 31 Julai mesti menunjukkan RM500.
        //
        // Menapis invois sahaja bermakna bayaran Ogos mengurangkan
        // tunggakan Julai, dan laporan yang sama memberi nombor berbeza
        // setiap kali dijana semula.
        long acc = akaun("AR-1", "ACTIVE", null, null);
        invoisBertarikh(acc, "AR-I1", "2026-07-15", "500.00");
        resit(acc, "AR-R1", "2026-08-02", "200.00");

        assertThat(tunggakan("2026-07-31", true).total())
                .as("bayaran Ogos diabaikan").isEqualByComparingTo("500.00");

        assertThat(tunggakan("2026-08-31", true).total())
                .as("bayaran dikira selepas tarikhnya").isEqualByComparingTo("300.00");
    }

    @Test
    @DisplayName("Invois SELEPAS tarikh tidak muncul")
    void invoisSelepasTarikhDiabaikan() {
        long acc = akaun("AR-2", "ACTIVE", null, null);
        invoisBertarikh(acc, "AR-I2", "2026-07-15", "100.00");
        invoisBertarikh(acc, "AR-I3", "2026-09-01", "250.00");

        assertThat(tunggakan("2026-07-31", true).total())
                .isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("arrearsOnly: akaun BERKREDIT disembunyikan atau ditunjuk")
    void tapisKredit() {
        // Resit boleh mendahului invoisnya — advance, atau tarikh yang
        // dimasukkan secara manual. Pada tarikh laporan, pelanggan itu
        // MEMANG berkredit.
        long acc = akaun("AR-KREDIT", "ACTIVE", null, null);
        resit(acc, "AR-R2", "2026-07-20", "80.00");

        assertThat(tunggakan("2026-07-31", true).rows())
                .as("tunggakan sahaja: kredit disembunyikan")
                .extracting(AccountListPort.ArrearRow::accountNo)
                .doesNotContain("AR-KREDIT");

        assertThat(tunggakan("2026-07-31", false).rows())
                .as("semua: kredit ditunjuk")
                .extracting(AccountListPort.ArrearRow::accountNo)
                .contains("AR-KREDIT");
    }

    // ── Ageing ───────────────────────────────────────────────────────

    /** Invois dengan due_date tertentu, supaya umurnya boleh dikawal. */
    private void invoisDue(long akaunId, String no, String docDate,
                           String dueDate, String amaun) {
        em.createNativeQuery("""
                INSERT INTO financial_document
                  (sp_code, doc_no, doc_type, account_id, doc_date, due_date,
                   amount, tax_amount, status, title, version)
                VALUES (:sp, :no, 'INVOICE', :acc, :dd, :due, :amt, 0,
                        'ACTIVE', 'Invois', 0)
                """).setParameter("sp", SP).setParameter("no", no)
                .setParameter("acc", akaunId)
                .setParameter("dd", java.time.LocalDate.parse(docDate))
                .setParameter("due", java.time.LocalDate.parse(dueDate))
                .setParameter("amt", new BigDecimal(amaun)).executeUpdate();
        em.flush();
    }

    private AccountListPort.AgeResult umur(String asAt) {
        em.flush();
        em.clear();
        return port.ageing(new AccountListPort.AgeQuery(
                SP, java.time.LocalDate.parse(asAt), null, null, true));
    }

    @Test
    @DisplayName("Jumlah bucket SAMA dengan total — sifat yang legacy langgar")
    void bucketSepadanDenganTotal() {
        // Laporan legacy mengulang nombor merentas lajur: baris '004 ali'
        // menunjukkan Amount 6.00 tetapi bucket 60-hari 5,971.00, dan
        // jumlah keseluruhan bucket MELEBIHI jumlah Amount.
        //
        // Bucket sebenar tidak boleh melakukan itu.
        long acc = akaun("AG-1", "ACTIVE", null, null);
        invoisDue(acc, "AG-I1", "2026-01-01", "2026-01-15", "100.00");   // 200 hari
        invoisDue(acc, "AG-I2", "2026-05-01", "2026-05-15", "200.00");   // 81 hari
        invoisDue(acc, "AG-I3", "2026-07-15", "2026-07-25", "300.00");   // 10 hari
        invoisDue(acc, "AG-I4", "2026-08-01", "2026-08-20", "400.00");   // belum matang

        var r = umur("2026-08-04");
        var baris = r.rows().stream()
                .filter(x -> x.accountNo().equals("AG-1")).findFirst().orElseThrow();

        BigDecimal hasilTambah = baris.notDue().add(baris.d30()).add(baris.d60())
                .add(baris.d90()).add(baris.d180()).add(baris.over180());

        assertThat(hasilTambah).isEqualByComparingTo(baris.total());
        assertThat(baris.total()).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("Invois BELUM MATANG masuk lajur sendiri, bukan 0-30")
    void belumMatangBerasingan() {
        // Memasukkannya dalam 0-30 bermakna laporan mengatakan pelanggan
        // sudah lewat sedangkan dia masih ada beberapa hari, dan JMB
        // menghantar peringatan kepada orang yang tidak bersalah.
        long acc = akaun("AG-2", "ACTIVE", null, null);
        invoisDue(acc, "AG-I5", "2026-08-01", "2026-08-20", "500.00");

        var baris = umur("2026-08-04").rows().stream()
                .filter(x -> x.accountNo().equals("AG-2")).findFirst().orElseThrow();

        assertThat(baris.notDue()).isEqualByComparingTo("500.00");
        assertThat(baris.d30()).isZero();
    }

    @Test
    @DisplayName("Umur dikira daripada due_date, dan setiap bucket menerima yang betul")
    void setiapBucketBetul() {
        long acc = akaun("AG-3", "ACTIVE", null, null);
        invoisDue(acc, "AG-B1", "2026-07-01", "2026-08-01", "10.00");   // 3 hari
        invoisDue(acc, "AG-B2", "2026-06-01", "2026-06-20", "20.00");   // 45 hari
        invoisDue(acc, "AG-B3", "2026-05-01", "2026-05-20", "30.00");   // 76 hari
        invoisDue(acc, "AG-B4", "2026-03-01", "2026-03-20", "40.00");   // 137 hari
        invoisDue(acc, "AG-B5", "2025-01-01", "2025-01-20", "50.00");   // 561 hari

        var b = umur("2026-08-04").rows().stream()
                .filter(x -> x.accountNo().equals("AG-3")).findFirst().orElseThrow();

        assertThat(b.d30()).as("0-30").isEqualByComparingTo("10.00");
        assertThat(b.d60()).as("31-60").isEqualByComparingTo("20.00");
        assertThat(b.d90()).as("61-90").isEqualByComparingTo("30.00");
        assertThat(b.d180()).as("91-180").isEqualByComparingTo("40.00");
        assertThat(b.over180()).as("180+").isEqualByComparingTo("50.00");
        assertThat(b.total()).isEqualByComparingTo("150.00");
    }

    @Test
    @DisplayName("POTRET: bayaran selepas tarikh tidak mengurangkan bucket")
    void ageingPotret() {
        long acc = akaun("AG-4", "ACTIVE", null, null);
        invoisDue(acc, "AG-I6", "2026-06-01", "2026-06-15", "200.00");

        // Resit selepas tarikh laporan
        resit(acc, "AG-R1", "2026-09-01", "200.00");
        long inv = ((Number) em.createNativeQuery(
                "SELECT id FROM financial_document WHERE sp_code=:sp AND doc_no='AG-I6'")
                .setParameter("sp", SP).getSingleResult()).longValue();
        long rcp = ((Number) em.createNativeQuery(
                "SELECT id FROM financial_document WHERE sp_code=:sp AND doc_no='AG-R1'")
                .setParameter("sp", SP).getSingleResult()).longValue();
        em.createNativeQuery("""
                INSERT INTO fi_allocation
                  (sp_code, account_id, credit_document_id, debit_document_id,
                   amount, status, version)
                VALUES (:sp, :acc, :cr, :dr, 200.00, 'ACTIVE', 0)
                """).setParameter("sp", SP).setParameter("acc", acc)
                .setParameter("cr", rcp).setParameter("dr", inv).executeUpdate();
        em.flush();

        var pada4Ogos = umur("2026-08-04").rows().stream()
                .filter(x -> x.accountNo().equals("AG-4")).findFirst().orElseThrow();
        assertThat(pada4Ogos.total())
                .as("bayaran September diabaikan").isEqualByComparingTo("200.00");

        assertThat(umur("2026-09-30").rows())
                .as("selepas bayaran, akaun hilang dari senarai")
                .extracting(AccountListPort.AgeRow::accountNo)
                .doesNotContain("AG-4");
    }

    @Test
    @DisplayName("Jumlah baki ialah hasil tambah baris yang dipaparkan")
    void jumlahSepadanDenganBaris() {
        // Jumlah yang dikira daripada set BERBEZA daripada baris yang
        // dipaparkan ialah percanggahan yang JMB akan jumpa sebelum kami.
        long a = akaun("AL-J1", "ACTIVE", null, null);
        long b = akaun("AL-J2", "ACTIVE", null, null);
        invois(a, "100.00");
        invois(b, "50.00");

        var r = senarai(true, null, null);

        BigDecimal hasilTambah = r.rows().stream()
                .map(AccountListPort.Row::balance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(r.totalBalance()).isEqualByComparingTo(hasilTambah);
    }
}
