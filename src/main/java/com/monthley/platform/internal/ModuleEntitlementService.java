package com.monthley.platform.internal;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Satu-satunya tempat hak modul berubah.
 *
 * Duduk dalam `platform` dan bukan `expenses`: ia menguruskan hak untuk
 * SEMUA modul, bukan Perbelanjaan sahaja. Dalam `expenses`, modul Aduan
 * kemudian terpaksa bergantung pada modul Perbelanjaan untuk mendapatkan
 * haknya sendiri — dan Modulith menolaknya dengan betul.
 *
 * HAK (sp_module) dan BIL (account_subscription) mesti sentiasa berubah
 * BERSAMA. Mengubahnya berasingan bermakna SP dibil untuk modul yang
 * tidak boleh diguna, atau menggunakan modul secara percuma selama
 * berbulan — dan kedua-duanya hanya disedari apabila seseorang menyemak
 * secara manual.
 *
 * TARIKH: satu peristiwa, dua tarikh berbeza (ADR 0016).
 *
 *   Diluluskan 15 Ogos  ->  hak aktif 15 Ogos, bil bermula 1 September
 *   Henti diluluskan 15 Ogos -> hak tamat 31 Ogos, tiada caj September
 *
 * Bil dikira dari tarikh KELULUSAN dan bukan tarikh permohonan: tarikh
 * kelulusan yang SP nampak dan boleh sahkan. Permohonan 28 Julai yang
 * diluluskan 3 Ogos tidak sepatutnya menghasilkan invois untuk tempoh
 * sebelum SP mempunyai akses.
 */
@Service
public class ModuleEntitlementService {

    @PersistenceContext
    private EntityManager em;

    /**
     * Beri hak modul dan mulakan bil.
     *
     * @param approvedBy platform_admin.id
     */
    @Transactional
    public void grant(String spCode, String moduleCode, Long approvedBy) {
        Number sudahAda = (Number) em.createNativeQuery("""
                SELECT COUNT(*) FROM sp_module
                WHERE  sp_code = :sp AND module_code = :m AND status = 'ACTIVE'
                """).setParameter("sp", spCode).setParameter("m", moduleCode)
                .getSingleResult();
        if (sudahAda.intValue() > 0) {
            throw new IllegalStateException(
                    "SP " + spCode + " sudah mempunyai modul " + moduleCode + ".");
        }

        LocalDate hariIni = LocalDate.now();

        // 1. HAK — serta-merta.
        em.createNativeQuery("""
                INSERT INTO sp_module (sp_code, module_code, status, start_date,
                                       approved_by, created_at, updated_at, version)
                VALUES (:sp, :m, 'ACTIVE', :mula, :by, NOW(), NOW(), 0)
                """)
                .setParameter("sp", spCode).setParameter("m", moduleCode)
                .setParameter("mula", hariIni).setParameter("by", approvedBy)
                .executeUpdate();

        // 2. BIL — 1hb bulan berikutnya.
        Long produkId = produkModul(moduleCode);
        if (produkId == null) {
            // Modul tanpa produk tidak dibil. Sah untuk modul percuma,
            // tetapi ia tidak sepatutnya senyap — hak masih diberi.
            return;
        }

        Long akaunBil = akaunBilSp(spCode);
        if (akaunBil == null) {
            throw new IllegalStateException(
                    "SP " + spCode + " tiada akaun bil. Modul tidak boleh dilanggan "
                    + "sehingga akaun dicipta di bawah SP platform.");
        }

        LocalDate mulaBil = hariIni.withDayOfMonth(1).plusMonths(1);

        em.createNativeQuery("""
                INSERT INTO account_subscription
                  (sp_code, account_id, product_id, quantity, start_date, status,
                   notes, created_at, created_by, updated_at, version)
                VALUES (:sp, :acc, :prod, 1, :mula, 'ACTIVE', :nota, NOW(), 'module', NOW(), 0)
                """)
                .setParameter("sp", platformSpCode())
                .setParameter("acc", akaunBil)
                .setParameter("prod", produkId)
                .setParameter("mula", mulaBil)
                .setParameter("nota", "Modul " + moduleCode + " — diluluskan " + hariIni)
                .executeUpdate();
    }

    /**
     * Tamatkan hak modul dan hentikan bil.
     *
     * Hak tamat HUJUNG BULAN yang telah dibil — SP sudah membayar untuk
     * bulan ini, jadi ia diguna sehingga habis. Simetri dengan permohonan:
     * mohon pertengahan bulan bermakna guna terus dan caj 1hb; henti
     * pertengahan bulan bermakna guna sampai hujung bulan dan tiada caj
     * 1hb.
     */
    @Transactional
    public void revoke(String spCode, String moduleCode, Long approvedBy) {
        LocalDate hariIni = LocalDate.now();
        LocalDate hujungBulan = hariIni.withDayOfMonth(hariIni.lengthOfMonth());

        int n = em.createNativeQuery("""
                UPDATE sp_module
                SET    status = 'ENDED', end_date = :tamat, approved_by = :by,
                       updated_at = NOW()
                WHERE  sp_code = :sp AND module_code = :m AND status = 'ACTIVE'
                """)
                .setParameter("tamat", hujungBulan).setParameter("by", approvedBy)
                .setParameter("sp", spCode).setParameter("m", moduleCode)
                .executeUpdate();

        if (n == 0) {
            throw new IllegalStateException(
                    "SP " + spCode + " tiada modul " + moduleCode + " yang aktif.");
        }

        Long produkId = produkModul(moduleCode);
        Long akaunBil = akaunBilSp(spCode);
        if (produkId == null || akaunBil == null) return;

        em.createNativeQuery("""
                UPDATE account_subscription
                SET    status = 'ENDED', end_date = :tamat, updated_at = NOW(),
                       updated_by = 'module'
                WHERE  account_id = :acc AND product_id = :prod AND status = 'ACTIVE'
                """)
                .setParameter("tamat", hujungBulan)
                .setParameter("acc", akaunBil).setParameter("prod", produkId)
                .executeUpdate();
    }

    /** Tukar pelan SP — kuota akaun dan produk bil berubah serentak. */
    @Transactional
    public void changePlan(String spCode, Long planProductId, Long approvedBy) {
        Long akaunBil = akaunBilSp(spCode);
        Long produkLama = (Long) em.createNativeQuery(
                "SELECT plan_product_id FROM service_provider WHERE sp_code = :sp")
                .setParameter("sp", spCode)
                .getResultList().stream().findFirst()
                .map(v -> v == null ? null : ((Number) v).longValue()).orElse(null);

        em.createNativeQuery(
                "UPDATE service_provider SET plan_product_id = :prod, updated_at = NOW() "
                + "WHERE sp_code = :sp")
                .setParameter("prod", planProductId).setParameter("sp", spCode)
                .executeUpdate();

        if (akaunBil == null) return;

        LocalDate hariIni = LocalDate.now();
        LocalDate hujungBulan = hariIni.withDayOfMonth(hariIni.lengthOfMonth());
        LocalDate mulaBaharu = hariIni.withDayOfMonth(1).plusMonths(1);

        // Pelan lama tamat hujung bulan, pelan baharu bermula 1hb — sama
        // seperti modul. Bertindih bermakna SP dibil dua pelan pada bulan
        // yang sama.
        if (produkLama != null) {
            em.createNativeQuery("""
                    UPDATE account_subscription
                    SET    status = 'ENDED', end_date = :tamat, updated_at = NOW(),
                           updated_by = 'module'
                    WHERE  account_id = :acc AND product_id = :prod AND status = 'ACTIVE'
                    """)
                    .setParameter("tamat", hujungBulan)
                    .setParameter("acc", akaunBil).setParameter("prod", produkLama)
                    .executeUpdate();
        }

        em.createNativeQuery("""
                INSERT INTO account_subscription
                  (sp_code, account_id, product_id, quantity, start_date, status,
                   notes, created_at, created_by, updated_at, version)
                VALUES (:sp, :acc, :prod, 1, :mula, 'ACTIVE', :nota, NOW(), 'module', NOW(), 0)
                """)
                .setParameter("sp", platformSpCode())
                .setParameter("acc", akaunBil)
                .setParameter("prod", planProductId)
                .setParameter("mula", mulaBaharu)
                .setParameter("nota", "Tukar pelan — diluluskan " + hariIni)
                .executeUpdate();
    }

    // ---------- helper ----------

    private Long produkModul(String moduleCode) {
        List<?> r = em.createNativeQuery(
                "SELECT product_id FROM ref_module WHERE code = :c")
                .setParameter("c", moduleCode).getResultList();
        if (r.isEmpty() || r.get(0) == null) return null;
        return ((Number) r.get(0)).longValue();
    }

    private Long akaunBilSp(String spCode) {
        List<?> r = em.createNativeQuery(
                "SELECT billing_account_id FROM service_provider WHERE sp_code = :sp")
                .setParameter("sp", spCode).getResultList();
        if (r.isEmpty() || r.get(0) == null) return null;
        return ((Number) r.get(0)).longValue();
    }

    private String platformSpCode() {
        return (String) em.createNativeQuery(
                "SELECT sp_code FROM service_provider WHERE is_platform_owner = 1")
                .getSingleResult();
    }
}
