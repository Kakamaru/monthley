package com.monthley.platform.internal;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Katalog modul — superadmin sahaja.
 *
 * Di sinilah sektor mana boleh melihat modul mana ditetapkan. JMB tidak
 * sepatutnya melihat 'Pengurusan Pelajar' walaupun sebagai tawaran, dan
 * penapis itu ialah keputusan perniagaan yang berubah — jadi ia data,
 * bukan kod.
 *
 * Laluan /api/v1/platform/** sudah disekat kepada SUPERADMIN dalam
 * SecurityConfig.
 */
@RestController
@RequestMapping("/api/v1/platform/modules")
class ModuleCatalogController {

    @PersistenceContext
    private EntityManager em;

    record ModuleRow(String code, String name, String description, String videoUrl,
                     Long productId, String productCode, BigDecimal price,
                     List<String> businessTypes, int sortOrder, boolean active,
                     long subscriberCount) {}

    record SaveRequest(String description, String videoUrl, Long productId,
                       List<String> businessTypes, Integer sortOrder, Boolean active) {}

    @GetMapping
    @SuppressWarnings("unchecked")
    List<ModuleRow> list() {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT m.code, m.name, m.description, m.video_url,
                       m.product_id, p.code, p.unit_rate,
                       m.business_types, m.sort_order, m.status,
                       (SELECT COUNT(*) FROM sp_module s
                        WHERE s.module_code = m.code AND s.status = 'ACTIVE')
                FROM   ref_module m
                LEFT   JOIN product p ON p.id = m.product_id
                ORDER  BY m.sort_order
                """).getResultList();

        List<ModuleRow> out = new ArrayList<>();
        for (Object[] r : rows) {
            String bt = (String) r[7];
            List<String> sektor = (bt == null || bt.isBlank())
                    ? List.of()
                    : List.of(bt.split(","));
            out.add(new ModuleRow(
                    (String) r[0], (String) r[1], (String) r[2], (String) r[3],
                    r[4] == null ? null : ((Number) r[4]).longValue(),
                    (String) r[5], (BigDecimal) r[6],
                    sektor, ((Number) r[8]).intValue(),
                    "ACTIVE".equals(r[9]), ((Number) r[10]).longValue()));
        }
        return out;
    }

    /**
     * Kemas kini modul.
     *
     * Senarai sektor KOSONG bermakna semua sektor — ia disimpan sebagai
     * NULL dan bukan rentetan kosong, supaya query penapis mempunyai satu
     * keadaan untuk diperiksa dan bukan dua.
     */
    @PutMapping("/{code}")
    @Transactional
    ResponseEntity<?> update(@PathVariable String code, @RequestBody SaveRequest r) {
        Number wujud = (Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM ref_module WHERE code = :c")
                .setParameter("c", code).getSingleResult();
        if (wujud.intValue() == 0) {
            throw new IllegalStateException("Modul tidak wujud: " + code);
        }

        String sektor = (r.businessTypes() == null || r.businessTypes().isEmpty())
                ? null
                : String.join(",", r.businessTypes());

        em.createNativeQuery("""
                UPDATE ref_module
                SET    description = :descr, video_url = :video, product_id = :prod,
                       business_types = :bt, sort_order = :urut, status = :st,
                       updated_at = NOW()
                WHERE  code = :c
                """)
                .setParameter("descr", r.description())
                .setParameter("video", r.videoUrl())
                .setParameter("prod", r.productId())
                .setParameter("bt", sektor)
                .setParameter("urut", r.sortOrder() == null ? 0 : r.sortOrder())
                .setParameter("st", (r.active() == null || r.active()) ? "ACTIVE" : "INACTIVE")
                .setParameter("c", code)
                .executeUpdate();

        return ResponseEntity.ok(Map.of("message", "Modul " + code + " dikemas kini."));
    }

    record BusinessTypeOption(String code, String name) {}

    @GetMapping("/business-types")
    @SuppressWarnings("unchecked")
    List<BusinessTypeOption> businessTypes() {
        List<Object[]> rows = em.createNativeQuery(
                "SELECT code, name FROM ref_business_type WHERE status='ACTIVE' ORDER BY sort_order")
                .getResultList();
        List<BusinessTypeOption> out = new ArrayList<>();
        for (Object[] r : rows) {
            out.add(new BusinessTypeOption((String) r[0], (String) r[1]));
        }
        return out;
    }

    record ProductOption(Long id, String code, String name, BigDecimal price) {}

    /** Produk bawah SP platform — pilihan untuk memaut harga modul. */
    @GetMapping("/products")
    @SuppressWarnings("unchecked")
    List<ProductOption> products() {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT p.id, p.code, p.name, p.unit_rate
                FROM   product p
                JOIN   service_provider owner ON owner.sp_code = p.sp_code
                                             AND owner.is_platform_owner = 1
                WHERE  p.status = 'ACTIVE' AND p.account_limit IS NULL
                ORDER  BY p.code
                """).getResultList();
        List<ProductOption> out = new ArrayList<>();
        for (Object[] r : rows) {
            out.add(new ProductOption(((Number) r[0]).longValue(),
                    (String) r[1], (String) r[2], (BigDecimal) r[3]));
        }
        return out;
    }
}
