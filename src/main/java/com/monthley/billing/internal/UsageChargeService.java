package com.monthley.billing.internal;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Caj berasaskan penggunaan — templat, parse, simpan (V58).
 *
 * Aliran: kerani memuat turun Excel yang sudah mengandungi senarai
 * akaun, mengisi kuantiti atau amaun, memuat naiknya semula, menyemak
 * di skrin, dan menyimpan.
 *
 * SEMUA AKAUN AKTIF dalam templat, bukan hanya yang berkenaan. Kerani
 * tidak tahu awal-awal siapa yang akan dicaj; baris yang dibiarkan
 * kosong dilangkau semasa parse.
 */
@Service
public class UsageChargeService {

    @PersistenceContext
    private EntityManager em;

    /** Satu baris daripada fail — sudah dipadankan dengan akaun. */
    public record Baris(Long accountId, String accountNo, String accountName,
                        String remarks, BigDecimal quantity, BigDecimal amount,
                        String masalah) {

        /** Baris yang boleh disimpan: akaun dijumpai dan amaun positif. */
        public boolean sah() { return masalah == null; }
    }

    // ── Templat ──────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public byte[] templat(String spCode) {
        List<Object[]> akaun = em.createNativeQuery("""
                SELECT a.account_no,
                       COALESCE(NULLIF(a.billto_name,''), a.account_name)
                FROM   account a
                WHERE  a.sp_code = :sp
                  AND  a.status = 'ACTIVE'
                  AND  COALESCE(a.account_type,'') <> 'ADHOC'
                ORDER  BY a.account_no
                """).setParameter("sp", spCode).getResultList();

        try (Workbook wb = new XSSFWorkbook();
             ByteArrayOutputStream os = new ByteArrayOutputStream()) {

            Sheet sh = wb.createSheet("Caj Penggunaan");

            CellStyle tajuk = wb.createCellStyle();
            Font tebal = wb.createFont();
            tebal.setBold(true);
            tajuk.setFont(tebal);
            tajuk.setAlignment(HorizontalAlignment.CENTER);

            String[] lajur = { "Account", "Bill To", "Remarks", "Quantity", "Amount" };
            Row hd = sh.createRow(0);
            for (int c = 0; c < lajur.length; c++) {
                Cell cell = hd.createCell(c);
                cell.setCellValue(lajur[c]);
                cell.setCellStyle(tajuk);
            }

            int r = 1;
            for (Object[] a : akaun) {
                Row row = sh.createRow(r++);
                // Nombor akaun sebagai TEKS. '1.1.1' dan '005' menjadi
                // 1.1 dan 5 kalau Excel meneka jenisnya, dan padanan
                // semasa muat naik gagal secara senyap.
                row.createCell(0).setCellValue((String) a[0]);
                row.createCell(1).setCellValue((String) a[1]);
                row.createCell(2).setCellValue("");
                row.createCell(3).setCellValue("");
                row.createCell(4).setCellValue("");
            }

            // Unit POI = 1/256 aksara. autoSizeColumn mengambil kira
            // setiap sel dan menjadikan lajur selebar nama terpanjang.
            int[] lebar = { 20, 34, 30, 12, 14 };
            for (int c = 0; c < lebar.length; c++) {
                sh.setColumnWidth(c, lebar[c] * 256);
            }

            wb.write(os);
            return os.toByteArray();

        } catch (java.io.IOException e) {
            throw new IllegalStateException("Gagal menjana templat: " + e.getMessage(), e);
        }
    }

    // ── Parse ────────────────────────────────────────────────────────

    /**
     * Baca fail dan padankan dengan akaun.
     *
     * KUANTITI ATAU AMAUN, AMAUN MENANG. Kalau kerani mengisi kuantiti
     * sahaja, ia didarab dengan kadar produk. Kalau dia mengisi amaun,
     * amaun itu digunakan terus — meter yang dibaca pihak ketiga datang
     * sebagai jumlah, bukan unit.
     *
     * Baris tanpa kedua-duanya DILANGKAU sepenuhnya, tidak dilaporkan
     * sebagai ralat: templat mengandungi setiap akaun, dan kebanyakannya
     * memang tidak berkenaan.
     */
    public List<Baris> parse(String spCode, long productId, InputStream fail) {
        BigDecimal kadar = kadarProduk(spCode, productId);
        Map<String, Object[]> akaun = akaunMengikutNo(spCode);

        List<Baris> out = new ArrayList<>();
        try (Workbook wb = WorkbookFactory.create(fail)) {
            Sheet sh = wb.getSheetAt(0);

            for (int i = 1; i <= sh.getLastRowNum(); i++) {
                Row row = sh.getRow(i);
                if (row == null) continue;

                String no = teks(row.getCell(0));
                if (no.isBlank()) continue;

                String remarks = teks(row.getCell(2));
                BigDecimal qty = nombor(row.getCell(3));
                BigDecimal amt = nombor(row.getCell(4));

                if (qty == null && amt == null) continue;   // tidak berkenaan

                Object[] a = akaun.get(no.toUpperCase());
                if (a == null) {
                    out.add(new Baris(null, no, "", remarks, qty, amt,
                            "Akaun tidak dijumpai"));
                    continue;
                }

                BigDecimal kuantiti = qty == null ? BigDecimal.ONE : qty;
                BigDecimal amaun = amt != null
                        ? amt
                        : kuantiti.multiply(kadar).setScale(2, RoundingMode.HALF_UP);

                String masalah = amaun.signum() <= 0
                        ? "Amaun mesti lebih daripada sifar"
                        : null;

                out.add(new Baris(((Number) a[0]).longValue(), no, (String) a[1],
                        remarks, kuantiti, amaun, masalah));
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Fail tidak boleh dibaca: " + e.getMessage(), e);
        }
        return out;
    }

    // ── Simpan ───────────────────────────────────────────────────────

    public record SimpanHasil(int disimpan, int dilangkau, List<String> sebab) {}

    /**
     * Simpan baris sebagai PENDING.
     *
     * DILANGKAU, bukan menggagalkan kelompok. Muat naik kedua untuk
     * (akaun, produk, tempoh) yang sama ditolak — kerani yang memuat
     * naik fail salah dan mengulanginya tidak sepatutnya mengecaj dua
     * kali — tetapi satu pendua tidak boleh membuang sembilan puluh
     * sembilan baris yang sah.
     */
    @Transactional
    public SimpanHasil simpan(String spCode, long productId, long periodId,
                              List<Baris> baris, String oleh) {
        int simpan = 0, langkau = 0;
        List<String> sebab = new ArrayList<>();

        for (Baris b : baris) {
            if (!b.sah()) {
                langkau++;
                sebab.add(b.accountNo() + ": " + b.masalah());
                continue;
            }

            Long ada = ((Number) em.createNativeQuery("""
                    SELECT COUNT(*) FROM account_usage_charge
                    WHERE  account_id = :acc AND product_id = :prod AND period_id = :per
                    """).setParameter("acc", b.accountId())
                    .setParameter("prod", productId)
                    .setParameter("per", periodId)
                    .getSingleResult()).longValue();
            if (ada > 0) {
                langkau++;
                sebab.add(b.accountNo() + ": sudah ada caj untuk produk dan tempoh ini");
                continue;
            }

            em.createNativeQuery("""
                    INSERT INTO account_usage_charge
                      (sp_code, account_id, product_id, period_id,
                       quantity, amount, remarks, status, created_by)
                    VALUES (:sp, :acc, :prod, :per, :q, :a, :r, 'PENDING', :by)
                    """)
                    .setParameter("sp", spCode)
                    .setParameter("acc", b.accountId())
                    .setParameter("prod", productId)
                    .setParameter("per", periodId)
                    .setParameter("q", b.quantity())
                    .setParameter("a", b.amount())
                    .setParameter("r", b.remarks() == null || b.remarks().isBlank()
                            ? null : b.remarks())
                    .setParameter("by", oleh)
                    .executeUpdate();
            simpan++;
        }
        return new SimpanHasil(simpan, langkau, sebab);
    }

    // ── Helper ───────────────────────────────────────────────────────

    private BigDecimal kadarProduk(String spCode, long productId) {
        return (BigDecimal) em.createNativeQuery(
                "SELECT unit_rate FROM product WHERE id = :id AND sp_code = :sp")
                .setParameter("id", productId).setParameter("sp", spCode)
                .getSingleResult();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object[]> akaunMengikutNo(String spCode) {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT a.id, a.account_name, a.account_no
                FROM   account a
                WHERE  a.sp_code = :sp AND a.status = 'ACTIVE'
                  AND  COALESCE(a.account_type,'') <> 'ADHOC'
                """).setParameter("sp", spCode).getResultList();

        Map<String, Object[]> m = new LinkedHashMap<>();
        for (Object[] r : rows) {
            m.put(((String) r[2]).toUpperCase(), r);
        }
        return m;
    }

    /**
     * Sel sebagai teks, walaupun Excel menyimpannya sebagai nombor.
     *
     * Nombor akaun '005' menjadi 5.0 apabila Excel meneka jenisnya, dan
     * padanan gagal secara senyap. DataFormatter memulihkan apa yang
     * kerani LIHAT.
     */
    private static String teks(Cell c) {
        if (c == null) return "";
        return new DataFormatter().formatCellValue(c).trim();
    }

    private static BigDecimal nombor(Cell c) {
        if (c == null) return null;
        if (c.getCellType() == CellType.NUMERIC) {
            return BigDecimal.valueOf(c.getNumericCellValue());
        }
        String s = new DataFormatter().formatCellValue(c).trim().replace(",", "");
        if (s.isBlank()) return null;
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
