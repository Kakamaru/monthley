package com.monthley.expenses.internal;

import com.monthley.ledger.api.GlAccounts;
import com.monthley.ledger.internal.ChartOfAccountSeeder;
import com.monthley.shared.ModuleGuard;
import com.monthley.shared.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Posting ledger modul Perbelanjaan.
 *
 * Ini bahagian yang paling senyap bila salah: jurnal yang tidak seimbang
 * ditolak oleh LedgerPort, tetapi jurnal yang SEIMBANG dengan akaun SALAH
 * lulus tanpa aduan dan Untung Rugi menjadi salah tanpa amaran.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ExpPostingTest {

    @Autowired ExpInvoiceService invoiceService;
    @Autowired ExpPaymentService paymentService;
    @Autowired ChartOfAccountSeeder seeder;
    @PersistenceContext EntityManager em;

    Long supplierId;
    Long catgUtiliti;
    Long catgPentadbiran;

    @BeforeEach
    void setup() {
        em.createNativeQuery("""
            INSERT IGNORE INTO service_provider (sp_code, name, status, created_at, updated_at, version)
            VALUES ('SPX1', 'Ujian Perbelanjaan', 'ACTIVE', NOW(), NOW(), 0)
            """).executeUpdate();
        seeder.seedFor("SPX1");

        em.createNativeQuery("""
            INSERT IGNORE INTO ref_module (code, name, sort_order, status, created_at, updated_at, version)
            VALUES ('PERBELANJAAN', 'Perbelanjaan', 1, 'ACTIVE', NOW(), NOW(), 0)
            """).executeUpdate();
        em.createNativeQuery("""
            INSERT INTO sp_module (sp_code, module_code, status, start_date, created_at, updated_at, version)
            VALUES ('SPX1', 'PERBELANJAAN', 'ACTIVE', CURDATE(), NOW(), NOW(), 0)
            """).executeUpdate();

        em.createNativeQuery("""
            INSERT INTO exp_supplier (sp_code, name, status, created_at, updated_at, version)
            VALUES ('SPX1', 'TNB Ujian', 'ACTIVE', NOW(), NOW(), 0)
            """).executeUpdate();
        supplierId = ((Number) em.createNativeQuery(
                "SELECT id FROM exp_supplier WHERE sp_code='SPX1'").getSingleResult()).longValue();

        // Kategori induk dengan GL, dan satu anak yang mewarisi.
        catgUtiliti = ciptaKategori("Utiliti", null, GlAccounts.EXPENSE_UTILITY);
        catgPentadbiran = ciptaKategori("Pentadbiran", null, GlAccounts.EXPENSE_ADMIN);
        em.flush();

        TenantContext.set("SPX1");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("u", "n/a",
                        List.of(new SimpleGrantedAuthority("SP_SPX1_SP_ADMIN"))));
    }

    private Long ciptaKategori(String nama, Long parentId, String glCode) {
        Long glId = glCode == null ? null : ((Number) em.createNativeQuery(
                "SELECT id FROM chart_of_accounts WHERE sp_code='SPX1' AND code=:c")
                .setParameter("c", glCode).getSingleResult()).longValue();
        em.createNativeQuery("""
            INSERT INTO exp_category (sp_code, name, parent_id, gl_account_id, sort_order,
                                      status, created_at, updated_at, version)
            VALUES ('SPX1', :n, :p, :gl, 0, 'ACTIVE', NOW(), NOW(), 0)
            """).setParameter("n", nama).setParameter("p", parentId)
                .setParameter("gl", glId).executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM exp_category WHERE sp_code='SPX1' AND name=:n")
                .setParameter("n", nama).getSingleResult()).longValue();
    }

    @AfterEach
    void clear() { TenantContext.clear(); SecurityContextHolder.clearContext(); }

    private record Leg(String gl, BigDecimal debit, BigDecimal credit) {}

    @SuppressWarnings("unchecked")
    private List<Leg> legs(Long journalEntryId) {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT c.code, l.debit_amount, l.credit_amount
                FROM   journal_line l
                JOIN   chart_of_accounts c ON c.id = l.gl_account_id
                WHERE  l.journal_entry_id = :j
                ORDER  BY c.code
                """).setParameter("j", journalEntryId).getResultList();
        return rows.stream().map(r -> new Leg((String) r[0],
                (BigDecimal) r[1], (BigDecimal) r[2])).toList();
    }

    @Test
    @DisplayName("invois pembekal — Dr Belanja per kategori / Cr AP, SST masuk belanja")
    void postingInvois() {
        Long invId = invoiceService.create(new ExpInvoiceService.NewInvoice(
                supplierId, "TNB-001", LocalDate.now(), null, null,
                List.of(new ExpInvoiceService.NewItem(catgUtiliti, "Elektrik", new BigDecimal("600.00")),
                        new ExpInvoiceService.NewItem(catgPentadbiran, "Audit", new BigDecimal("400.00")))));
        em.flush();

        Object[] inv = (Object[]) em.createNativeQuery(
                "SELECT subtotal, sst_amount, total, journal_entry_id FROM exp_invoice WHERE id=:i")
                .setParameter("i", invId).getSingleResult();

        assertThat((BigDecimal) inv[0]).isEqualByComparingTo("1000.00");
        assertThat((BigDecimal) inv[2]).isEqualByComparingTo("1000.00");   // SST dimatikan

        List<Leg> l = legs(((Number) inv[3]).longValue());
        assertThat(l).hasSize(3);

        // AP dikredit dengan JUMLAH; setiap kategori didebit secara berasingan.
        assertThat(cari(l, GlAccounts.ACCOUNTS_PAYABLE).credit()).isEqualByComparingTo("1000.00");
        assertThat(cari(l, GlAccounts.EXPENSE_UTILITY).debit()).isEqualByComparingTo("600.00");
        assertThat(cari(l, GlAccounts.EXPENSE_ADMIN).debit()).isEqualByComparingTo("400.00");
    }

    @Test
    @DisplayName("SST masuk akaun BELANJA, bukan 2100 SST Payable")
    void sstMasukBelanja() {
        em.createNativeQuery("""
            INSERT INTO exp_setting (sp_code, sst_enabled, sst_rate, updated_at, version)
            VALUES ('SPX1', 1, 8.00, NOW(), 0)
            """).executeUpdate();
        em.flush();

        Long invId = invoiceService.create(new ExpInvoiceService.NewInvoice(
                supplierId, "TNB-SST", LocalDate.now(), null, null,
                List.of(new ExpInvoiceService.NewItem(catgUtiliti, "Elektrik", new BigDecimal("1000.00")))));
        em.flush();

        Object[] inv = (Object[]) em.createNativeQuery(
                "SELECT subtotal, sst_amount, total, journal_entry_id FROM exp_invoice WHERE id=:i")
                .setParameter("i", invId).getSingleResult();

        assertThat((BigDecimal) inv[1]).isEqualByComparingTo("80.00");
        assertThat((BigDecimal) inv[2]).isEqualByComparingTo("1080.00");

        List<Leg> l = legs(((Number) inv[3]).longValue());

        // Kos sebenar ialah 1080, bukan 1000 + baris cukai berasingan.
        // SST Malaysia tiada tuntutan input, jadi ia sebahagian kos.
        assertThat(cari(l, GlAccounts.EXPENSE_UTILITY).debit()).isEqualByComparingTo("1080.00");
        assertThat(l).noneMatch(x -> GlAccounts.TAX_PAYABLE.equals(x.gl()));
    }

    @Test
    @DisplayName("bayaran PV — Dr AP / Cr Bank, dan baki invois berkurang")
    void postingBayaran() {
        Long invId = invoiceService.create(new ExpInvoiceService.NewInvoice(
                supplierId, "TNB-PAY", LocalDate.now(), null, null,
                List.of(new ExpInvoiceService.NewItem(catgUtiliti, "Elektrik", new BigDecimal("500.00")))));
        em.flush();

        Long pvId = paymentService.payInvoice(new ExpPaymentService.NewPv(
                invId, LocalDate.now(), new BigDecimal("200.00"), "BANK", null, null));
        em.flush();

        Object[] pv = (Object[]) em.createNativeQuery(
                "SELECT pv_no, journal_entry_id FROM exp_payment WHERE id=:i")
                .setParameter("i", pvId).getSingleResult();

        List<Leg> l = legs(((Number) pv[1]).longValue());
        assertThat(cari(l, GlAccounts.ACCOUNTS_PAYABLE).debit()).isEqualByComparingTo("200.00");
        assertThat(cari(l, GlAccounts.BANK).credit()).isEqualByComparingTo("200.00");

        // Baki DIDERIVE — tiada lajur baki untuk menyimpang.
        Object baki = em.createNativeQuery(
                "SELECT balance FROM exp_invoice_balance WHERE invoice_id=:i")
                .setParameter("i", invId).getSingleResult();
        assertThat(new BigDecimal(baki.toString())).isEqualByComparingTo("300.00");
    }

    @Test
    @DisplayName("bayaran melebihi baki ditolak")
    void bayaranLebihBaki() {
        Long invId = invoiceService.create(new ExpInvoiceService.NewInvoice(
                supplierId, "TNB-OVER", LocalDate.now(), null, null,
                List.of(new ExpInvoiceService.NewItem(catgUtiliti, "Elektrik", new BigDecimal("100.00")))));
        em.flush();

        assertThatThrownBy(() -> paymentService.payInvoice(new ExpPaymentService.NewPv(
                invId, LocalDate.now(), new BigDecimal("150.00"), "BANK", null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("melebihi baki");
    }

    @Test
    @DisplayName("bayaran terus — Dr Belanja / Cr Bank, tiada AP")
    void postingBayaranTerus() {
        Long id = paymentService.recordCashEntry(new ExpPaymentService.NewCashEntry(
                LocalDate.now(), catgPentadbiran, "Ali bin Abu", "Gaji Ogos",
                new BigDecimal("2500.00"), "BANK", null));
        em.flush();

        Object[] e = (Object[]) em.createNativeQuery(
                "SELECT voucher_no, journal_entry_id FROM exp_cash_entry WHERE id=:i")
                .setParameter("i", id).getSingleResult();

        List<Leg> l = legs(((Number) e[1]).longValue());
        assertThat(l).hasSize(2);
        assertThat(cari(l, GlAccounts.EXPENSE_ADMIN).debit()).isEqualByComparingTo("2500.00");
        assertThat(cari(l, GlAccounts.BANK).credit()).isEqualByComparingTo("2500.00");
        assertThat(l).noneMatch(x -> GlAccounts.ACCOUNTS_PAYABLE.equals(x.gl()));
    }

    /**
     * Prefix dari exp_setting mesti sampai ke nombor yang dijana.
     *
     * Ujian asal tidak menyemak nombor langsung, jadi pepijat ini lolos:
     * DocumentNumberService mencipta baris turutan bila ia belum wujud,
     * kemudian memanggil dirinya semula — tetapi melalui varian
     * dua-parameter yang MENCARI tetapan semula. Modul luar mendapat lalai
     * "DOC" dan PV pertama setiap SP keluar sebagai DOC000001.
     *
     * Ia hanya berlaku pada panggilan PERTAMA (bila turutan dicipta), jadi
     * ia kelihatan bekerja sebaik sahaja seorang menguji kali kedua.
     */
    @Test
    @DisplayName("nombor PV guna prefix dari tetapan modul, bukan lalai DOC")
    void prefixPvDariTetapan() {
        em.createNativeQuery("""
            INSERT INTO exp_setting (sp_code, sst_enabled, sst_rate, pv_prefix, pv_no_size,
                                     created_at, version)
            VALUES ('SPX1', 0, 0.00, 'BV', 4, NOW(), 0)
            """).executeUpdate();
        em.flush();

        Long invId = invoiceService.create(new ExpInvoiceService.NewInvoice(
                supplierId, "TNB-PREFIX", LocalDate.now(), null, null,
                List.of(new ExpInvoiceService.NewItem(catgUtiliti, "X", new BigDecimal("50.00")))));
        em.flush();

        Long pvId = paymentService.payInvoice(new ExpPaymentService.NewPv(
                invId, LocalDate.now(), new BigDecimal("10.00"), "TUNAI", null, null));
        em.flush();

        String pvNo = (String) em.createNativeQuery(
                "SELECT pv_no FROM exp_payment WHERE id = :i")
                .setParameter("i", pvId).getSingleResult();

        // Panggilan PERTAMA — turutan dicipta di sini, iaitu laluan yang pecah.
        assertThat(pvNo).isEqualTo("BV0001");
    }

    @Test
    @DisplayName("SP tanpa hak modul ditolak oleh ModuleGuard")
    void tanpaHakModul() {
        em.createNativeQuery("UPDATE sp_module SET status='ENDED' WHERE sp_code='SPX1'")
                .executeUpdate();
        em.flush();

        assertThatThrownBy(() -> invoiceService.create(new ExpInvoiceService.NewInvoice(
                supplierId, "TNB-NOMOD", LocalDate.now(), null, null,
                List.of(new ExpInvoiceService.NewItem(catgUtiliti, "X", new BigDecimal("10.00"))))))
                .hasMessageContaining("belum dilanggan");
    }

    private Leg cari(List<Leg> legs, String gl) {
        return legs.stream().filter(x -> gl.equals(x.gl())).findFirst()
                .orElseThrow(() -> new AssertionError("Tiada leg untuk GL " + gl + " dalam " + legs));
    }
}
