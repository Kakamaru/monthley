package com.monthley.billing.internal;

import com.monthley.shared.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Templat, parse dan simpan caj penggunaan (V58).
 *
 * Peraturan yang paling mudah tersilap: KUANTITI ATAU AMAUN, dan amaun
 * menang. Meter yang dibaca pihak ketiga datang sebagai jumlah, bukan
 * unit.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class UsageChargeServiceTest {

    private static final String SP = "SPUS";

    @Autowired UsageChargeService usage;
    @PersistenceContext EntityManager em;

    private long produk;
    private long periodId;

    @BeforeEach
    void seed() {
        em.createNativeQuery("""
                INSERT IGNORE INTO service_provider (sp_code, name, status, version)
                VALUES (:sp, 'SP Ujian Usage Service', 'ACTIVE', 0)
                """).setParameter("sp", SP).executeUpdate();

        String kod = "US-" + System.nanoTime();
        em.createNativeQuery("""
                INSERT INTO product (sp_code, code, name, charge_frequency, unit_rate,
                                     main_product, mandatory, prorated, late_penalty,
                                     status, version)
                VALUES (:sp, :k, 'Sukaneka', 'PER_USE', 25.00, 0,0,0,0, 'ACTIVE', 0)
                """).setParameter("sp", SP).setParameter("k", kod).executeUpdate();
        produk = ((Number) em.createNativeQuery(
                "SELECT id FROM product WHERE sp_code=:sp AND code=:k")
                .setParameter("sp", SP).setParameter("k", kod)
                .getSingleResult()).longValue();

        periodId = ((Number) em.createNativeQuery(
                "SELECT period_id FROM fi_period WHERE charge_code='MO' "
                + "AND YEAR(start_dt)=2026 AND MONTH(start_dt)=7")
                .getSingleResult()).longValue();

        TenantContext.set(SP);
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    private long akaun(String no, String jenis) {
        em.createNativeQuery("""
                INSERT INTO account (sp_code, account_no, account_name, account_type, status)
                VALUES (:sp, :no, :nm, :t, 'ACTIVE')
                """).setParameter("sp", SP).setParameter("no", no)
                .setParameter("nm", "Nama " + no).setParameter("t", jenis)
                .executeUpdate();
        em.flush();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM account WHERE sp_code=:sp AND account_no=:no")
                .setParameter("sp", SP).setParameter("no", no)
                .getSingleResult()).longValue();
    }

    /** Fail Excel dengan baris (akaun, qty, amt) — null bermakna kosong. */
    private byte[] fail(Object[]... baris) {
        try (Workbook wb = new XSSFWorkbook();
             ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            Sheet sh = wb.createSheet("X");
            Row hd = sh.createRow(0);
            String[] lajur = { "Account", "Bill To", "Remarks", "Quantity", "Amount" };
            for (int c = 0; c < lajur.length; c++) hd.createCell(c).setCellValue(lajur[c]);

            int r = 1;
            for (Object[] b : baris) {
                Row row = sh.createRow(r++);
                row.createCell(0).setCellValue((String) b[0]);
                row.createCell(1).setCellValue("");
                row.createCell(2).setCellValue(b.length > 3 && b[3] != null ? (String) b[3] : "");
                if (b[1] != null) row.createCell(3).setCellValue(((Number) b[1]).doubleValue());
                if (b[2] != null) row.createCell(4).setCellValue(((Number) b[2]).doubleValue());
            }
            wb.write(os);
            return os.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("Templat: akaun aktif sahaja, ADHOC-SALES dikecualikan")
    void templatAkaunAktif() {
        akaun("T-1", null);
        akaun("ADHOC-SALES", "ADHOC");
        em.flush();

        byte[] xlsx = usage.templat(SP);

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(xlsx))) {
            Sheet sh = wb.getSheetAt(0);
            assertThat(sh.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Account");

            List<String> no = new java.util.ArrayList<>();
            for (int i = 1; i <= sh.getLastRowNum(); i++) {
                no.add(sh.getRow(i).getCell(0).getStringCellValue());
            }
            assertThat(no).contains("T-1").doesNotContain("ADHOC-SALES");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("Kuantiti sahaja: didarab kadar produk")
    void kuantitiDidarabKadar() {
        akaun("Q-1", null);

        var baris = usage.parse(SP, produk,
                new ByteArrayInputStream(fail(new Object[]{ "Q-1", 19, null })));

        assertThat(baris).singleElement().satisfies(b -> {
            assertThat(b.quantity()).isEqualByComparingTo("19");
            assertThat(b.amount()).as("19 x 25.00").isEqualByComparingTo("475.00");
            assertThat(b.sah()).isTrue();
        });
    }

    @Test
    @DisplayName("Amaun diisi: amaun MENANG, kadar diabaikan")
    void amaunMenang() {
        // Meter yang dibaca pihak ketiga datang sebagai jumlah, bukan
        // unit. Mendarab kuantiti dengan kadar akan memberi nombor yang
        // berbeza daripada apa yang SP sudah persetujui.
        akaun("A-1", null);

        var baris = usage.parse(SP, produk,
                new ByteArrayInputStream(fail(new Object[]{ "A-1", 19, 300 })));

        assertThat(baris).singleElement()
                .extracting(UsageChargeService.Baris::amount)
                .satisfies(a -> assertThat((BigDecimal) a)
                        .as("300, bukan 19 x 25 = 475")
                        .isEqualByComparingTo("300"));
    }

    @Test
    @DisplayName("Baris kosong DILANGKAU, akaun tidak dijumpai DILAPORKAN")
    void kosongDilangkauTidakDijumpaiDilaporkan() {
        // Templat mengandungi setiap akaun dan kebanyakannya memang
        // tidak berkenaan — melaporkannya sebagai ralat menjadikan skrin
        // pratonton tidak boleh dibaca.
        akaun("K-1", null);

        var baris = usage.parse(SP, produk, new ByteArrayInputStream(fail(
                new Object[]{ "K-1", 5, null },
                new Object[]{ "K-KOSONG", null, null },
                new Object[]{ "TIADA-AKAUN", 3, null })));

        assertThat(baris).hasSize(2);
        assertThat(baris.get(0).sah()).isTrue();
        assertThat(baris.get(1).sah()).isFalse();
        assertThat(baris.get(1).masalah()).contains("tidak dijumpai");
    }

    @Test
    @DisplayName("Simpan: pendua DILANGKAU, baris sah tetap disimpan")
    void penduaDilangkau() {
        // Satu pendua tidak boleh membuang sembilan puluh sembilan baris
        // yang sah.
        long a1 = akaun("S-1", null);
        long a2 = akaun("S-2", null);
        em.createNativeQuery("""
                INSERT INTO account_usage_charge
                  (sp_code, account_id, product_id, period_id, quantity, amount, status)
                VALUES (:sp, :acc, :prod, :per, 1, 25.00, 'PENDING')
                """).setParameter("sp", SP).setParameter("acc", a1)
                .setParameter("prod", produk).setParameter("per", periodId)
                .executeUpdate();
        em.flush();

        var hasil = usage.simpan(SP, produk, periodId, List.of(
                new UsageChargeService.Baris(a1, "S-1", "", null,
                        BigDecimal.ONE, new BigDecimal("25.00"), null),
                new UsageChargeService.Baris(a2, "S-2", "", null,
                        BigDecimal.ONE, new BigDecimal("25.00"), null)), "ujian");

        assertThat(hasil.disimpan()).isEqualTo(1);
        assertThat(hasil.dilangkau()).isEqualTo(1);
        assertThat(hasil.sebab()).singleElement().asString().contains("S-1");
    }
}
