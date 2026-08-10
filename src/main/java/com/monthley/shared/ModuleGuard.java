package com.monthley.shared;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adakah SP semasa mempunyai HAK untuk modul ini? (ADR 0016)
 *
 * Corak sama dengan {@link Access#requireRole}, dengan SATU perbezaan penting:
 * <b>superadmin TIDAK melepasi guard ini.</b>
 *
 * {@code Access.hasRole()} memulangkan true untuk superadmin kerana peranan
 * ialah tentang PENGGUNA. Hak modul ialah tentang SP. Superadmin yang
 * mencipta data Aduan untuk SP yang tidak melanggan menghasilkan data yatim:
 * ia wujud dalam DB tetapi tiada siapa boleh melihatnya, kerana skrin modul
 * tersebut tertutup untuk SP itu. Superadmin MELULUSKAN modul; superadmin
 * tidak MENGGUNAKAN modul bagi pihak SP.
 *
 * <p>Skop: endpoint <b>TULIS</b> sahaja. Endpoint baca dibenarkan dan
 * memulangkan keadaan kosong — 'benarkan masuk, sekat transaksi' (soalan 28,
 * disahkan semula dalam ADR 0016). Menyembunyikan modul sepenuhnya ialah
 * jualan yang hilang, dan menu yang lenyap secara misteri bila pakej berubah.
 *
 * <p>Sekatan UI sahaja tidak memadai: sesiapa yang tahu URL API boleh
 * mencipta data tanpa bayar, dan kebocoran itu hanya disedari selepas ada
 * berpuluh SP.
 */
@Component
public class ModuleGuard {

    /** Kod modul — pemalar, bukan rentetan bertaburan. */
    public static final String PERBELANJAAN = "PERBELANJAAN";
    public static final String ADUAN        = "ADUAN";
    public static final String SUMBANGAN    = "SUMBANGAN";
    public static final String MEMO         = "MEMO";

    @PersistenceContext
    private EntityManager em;

    /** SP semasa ada hak aktif untuk modul ini? */
    @Transactional(readOnly = true)
    public boolean has(String moduleCode) {
        String sp = TenantContext.get();
        if (sp == null || sp.isBlank()) return false;

        Number n = (Number) em.createNativeQuery("""
                SELECT COUNT(*) FROM sp_module
                WHERE  sp_code = :sp AND module_code = :m AND status = 'ACTIVE'
                  AND  start_date <= CURDATE()
                  AND  (end_date IS NULL OR end_date >= CURDATE())
                """)
                .setParameter("sp", sp)
                .setParameter("m", moduleCode)
                .getSingleResult();
        return n.intValue() > 0;
    }

    /**
     * Tolak jika SP tiada hak. Guna di awal setiap endpoint TULIS modul.
     *
     * @param action perbuatan dalam bahasa manusia, untuk mesej ralat
     */
    public void require(String moduleCode, String action) {
        if (!has(moduleCode)) {
            throw new Access.AccessDeniedException(
                    "Modul ini belum dilanggan. Anda tidak boleh " + action
                    + ". Hubungi admin organisasi untuk memohon modul.");
        }
    }
}
