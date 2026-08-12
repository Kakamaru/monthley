package com.monthley.complaints.internal;

import com.monthley.shared.Access;
import com.monthley.shared.ModuleGuard;
import com.monthley.shared.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Dashboard dan tetapan modul Aduan.
 *
 *   GET /api/v1/complaints/dashboard
 *   GET|PUT /api/v1/complaints/settings
 *   GET|POST|PUT|DELETE /api/v1/complaints/categories
 */
@RestController
@RequestMapping("/api/v1/complaints")
class AduDashboardController {

    private final AduCategoryRepository categories;
    private final AduSettingRepository settings;
    private final ModuleGuard modules;

    @PersistenceContext
    private EntityManager em;

    AduDashboardController(AduCategoryRepository categories,
                           AduSettingRepository settings, ModuleGuard modules) {
        this.categories = categories;
        this.settings = settings;
        this.modules = modules;
    }

    // ---------- Dashboard ----------

    record TrendPoint(String label, long received, long resolved) {}
    record CategoryCount(String name, long count) {}
    record UrgentRow(Long id, String complaintNo, String subject, String accountNo,
                     String categoryName, String priority, int ageDays, boolean overSla) {}
    record Dashboard(long total, long baru, long dalamProses, long selesai,
                     long dibukaSemula, long melebihiSla,
                     BigDecimal kadarSelesai, BigDecimal purataMaklumBalasJam,
                     BigDecimal purataSelesaiHari,
                     List<TrendPoint> trend, List<CategoryCount> byCategory,
                     List<UrgentRow> urgent, int slaDays) {}

    /**
     * Ringkasan aduan.
     *
     * 'Purata masa maklum balas' dikira daripada balasan SP PERTAMA bagi
     * setiap aduan — bukan mana-mana balasan. Ia mengukur berapa lama
     * pengadu menunggu sebelum mendengar apa-apa, dan itulah yang paling
     * dirasai.
     *
     * Aduan tanpa balasan SP DIKECUALIKAN daripada purata dan bukan
     * dikira sebagai sifar; memasukkannya menjadikan purata bertambah
     * baik apabila aduan diabaikan.
     */
    @GetMapping("/dashboard")
    @SuppressWarnings("unchecked")
    Dashboard dashboard() {
        Access.requireAnyRole("melihat dashboard aduan", "SP_ADMIN", "CLERK");
        String sp = sp();
        int sla = slaDays(sp);

        Object[] kira = (Object[]) em.createNativeQuery("""
                SELECT COUNT(*),
                       SUM(status = 'NEW'),
                       SUM(status = 'IN_PROGRESS'),
                       SUM(status = 'RESOLVED'),
                       SUM(status = 'REOPENED'),
                       SUM(status <> 'RESOLVED'
                           AND DATEDIFF(CURDATE(), DATE(created_at)) > :sla)
                FROM   adu_complaint WHERE sp_code = :sp
                """).setParameter("sp", sp).setParameter("sla", sla).getSingleResult();

        long total = num(kira[0]), baru = num(kira[1]), proses = num(kira[2]),
             selesai = num(kira[3]), reopen = num(kira[4]), lewat = num(kira[5]);

        BigDecimal kadar = total == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(selesai * 100.0 / total).setScale(0, RoundingMode.HALF_UP);

        // Balasan SP pertama bagi setiap aduan.
        Object jam = em.createNativeQuery("""
                SELECT AVG(TIMESTAMPDIFF(MINUTE, c.created_at, r.pertama)) / 60
                FROM   adu_complaint c
                JOIN  (SELECT complaint_id, MIN(created_at) AS pertama
                       FROM   adu_reply WHERE from_sp = 1 AND internal = 0
                       GROUP  BY complaint_id) r ON r.complaint_id = c.id
                WHERE  c.sp_code = :sp
                """).setParameter("sp", sp).getSingleResult();

        Object hari = em.createNativeQuery("""
                SELECT AVG(TIMESTAMPDIFF(HOUR, created_at, resolved_at)) / 24
                FROM   adu_complaint
                WHERE  sp_code = :sp AND resolved_at IS NOT NULL
                """).setParameter("sp", sp).getSingleResult();

        // Trend enam bulan: diterima berbanding selesai.
        List<Object[]> tr = em.createNativeQuery("""
                SELECT bulan, SUM(masuk), SUM(siap) FROM (
                    SELECT DATE_FORMAT(created_at,'%Y-%m') AS bulan, 1 AS masuk, 0 AS siap
                    FROM   adu_complaint
                    WHERE  sp_code = :sp
                      AND  created_at >= DATE_SUB(CURDATE(), INTERVAL 5 MONTH)
                    UNION ALL
                    SELECT DATE_FORMAT(resolved_at,'%Y-%m'), 0, 1
                    FROM   adu_complaint
                    WHERE  sp_code = :sp AND resolved_at IS NOT NULL
                      AND  resolved_at >= DATE_SUB(CURDATE(), INTERVAL 5 MONTH)
                ) x GROUP BY bulan ORDER BY bulan
                """).setParameter("sp", sp).getResultList();

        List<TrendPoint> trend = new ArrayList<>();
        for (Object[] r : tr) {
            trend.add(new TrendPoint((String) r[0], num(r[1]), num(r[2])));
        }

        List<Object[]> cats = em.createNativeQuery("""
                SELECT COALESCE(g.name, '(tiada kategori)'), COUNT(*)
                FROM   adu_complaint c
                LEFT   JOIN adu_category g ON g.id = c.category_id
                WHERE  c.sp_code = :sp
                GROUP  BY g.name ORDER BY COUNT(*) DESC
                """).setParameter("sp", sp).getResultList();

        List<CategoryCount> byCategory = new ArrayList<>();
        for (Object[] r : cats) byCategory.add(new CategoryCount((String) r[0], num(r[1])));

        // Perlu tindakan segera: belum selesai, paling lama dahulu.
        List<Object[]> urg = em.createNativeQuery("""
                SELECT c.id, c.complaint_no, c.subject, a.account_no, g.name,
                       c.priority, DATEDIFF(CURDATE(), DATE(c.created_at))
                FROM   adu_complaint c
                JOIN   account a ON a.id = c.account_id
                LEFT   JOIN adu_category g ON g.id = c.category_id
                WHERE  c.sp_code = :sp AND c.status <> 'RESOLVED'
                ORDER  BY FIELD(c.priority,'HIGH','MEDIUM','LOW'), c.created_at
                LIMIT  6
                """).setParameter("sp", sp).getResultList();

        List<UrgentRow> urgent = new ArrayList<>();
        for (Object[] r : urg) {
            int umur = ((Number) r[6]).intValue();
            urgent.add(new UrgentRow(((Number) r[0]).longValue(), (String) r[1],
                    (String) r[2], (String) r[3], (String) r[4], (String) r[5],
                    umur, umur > sla));
        }

        return new Dashboard(total, baru, proses, selesai, reopen, lewat, kadar,
                dec(jam), dec(hari), trend, byCategory, urgent, sla);
    }

    // ---------- Kategori ----------

    record CategoryDto(Long id, String name, int sortOrder, boolean active, long used) {}
    record SaveCategory(String name, Integer sortOrder, Boolean active) {}

    @GetMapping("/categories")
    @SuppressWarnings("unchecked")
    List<CategoryDto> listCategories() {
        Access.requireAnyRole("melihat kategori aduan", "SP_ADMIN", "CLERK");

        List<Object[]> rows = em.createNativeQuery("""
                SELECT g.id, g.name, g.sort_order, g.status,
                       (SELECT COUNT(*) FROM adu_complaint c WHERE c.category_id = g.id)
                FROM   adu_category g WHERE g.sp_code = :sp
                ORDER  BY g.sort_order, g.name
                """).setParameter("sp", sp()).getResultList();

        List<CategoryDto> out = new ArrayList<>();
        for (Object[] r : rows) {
            out.add(new CategoryDto(((Number) r[0]).longValue(), (String) r[1],
                    ((Number) r[2]).intValue(), "ACTIVE".equals(r[3]), num(r[4])));
        }
        return out;
    }

    @PostMapping("/categories")
    @Transactional
    ResponseEntity<?> createCategory(@RequestBody SaveCategory r) {
        Access.requireRole("SP_ADMIN", "menambah kategori aduan");
        modules.require(ModuleGuard.ADUAN, "menambah kategori aduan");

        String nama = r.name() == null ? "" : r.name().trim();
        if (nama.isBlank()) throw new IllegalStateException("Nama kategori wajib diisi.");

        AduCategory c = new AduCategory(sp(), nama);
        if (r.sortOrder() != null) c.setSortOrder(r.sortOrder());
        return ResponseEntity.ok(categories.save(c).getId());
    }

    @PutMapping("/categories/{id}")
    @Transactional
    ResponseEntity<?> updateCategory(@PathVariable Long id, @RequestBody SaveCategory r) {
        Access.requireRole("SP_ADMIN", "mengubah kategori aduan");
        modules.require(ModuleGuard.ADUAN, "mengubah kategori aduan");

        AduCategory c = categories.findByIdAndSpCode(id, sp()).orElseThrow(
                () -> new IllegalStateException("Kategori tidak wujud."));
        if (r.name() != null && !r.name().isBlank()) c.setName(r.name().trim());
        if (r.sortOrder() != null) c.setSortOrder(r.sortOrder());
        if (r.active() != null) {
            c.setStatus(r.active() ? AduCategory.Status.ACTIVE : AduCategory.Status.INACTIVE);
        }
        return ResponseEntity.ok(Map.of("message", "Kategori dikemas kini."));
    }

    /** Nyahaktif, bukan padam — kategori dirujuk oleh aduan sedia ada. */
    @DeleteMapping("/categories/{id}")
    @Transactional
    ResponseEntity<?> deactivateCategory(@PathVariable Long id) {
        Access.requireRole("SP_ADMIN", "menyahaktifkan kategori aduan");
        modules.require(ModuleGuard.ADUAN, "menyahaktifkan kategori aduan");

        AduCategory c = categories.findByIdAndSpCode(id, sp()).orElseThrow(
                () -> new IllegalStateException("Kategori tidak wujud."));
        c.setStatus(AduCategory.Status.INACTIVE);
        return ResponseEntity.ok(Map.of("message", "Kategori " + c.getName() + " dinyahaktifkan."));
    }

    // ---------- Tetapan ----------

    record SettingDto(String prefix, int noSize, long noStart, int slaDays) {}

    @GetMapping("/settings")
    @Transactional
    SettingDto settings() {
        Access.requireAnyRole("melihat tetapan aduan", "SP_ADMIN", "CLERK");
        String sp = sp();
        AduSetting s = settings.findById(sp).orElseGet(() -> settings.save(new AduSetting(sp)));
        return new SettingDto(s.getPrefix(), s.getNoSize(), s.getNoStart(), s.getSlaDays());
    }

    @PutMapping("/settings")
    @Transactional
    ResponseEntity<?> updateSettings(@RequestBody SettingDto r) {
        Access.requireRole("SP_ADMIN", "mengubah tetapan aduan");
        modules.require(ModuleGuard.ADUAN, "mengubah tetapan aduan");

        String sp = sp();
        AduSetting s = settings.findById(sp).orElseGet(() -> settings.save(new AduSetting(sp)));

        if (r.prefix() != null && !r.prefix().isBlank()) s.setPrefix(r.prefix().trim());
        if (r.noSize() >= 1 && r.noSize() <= 18) s.setNoSize(r.noSize());
        if (r.noStart() >= 0) s.setNoStart(r.noStart());
        // SLA sifar bermakna setiap aduan melebihi SLA serta-merta.
        if (r.slaDays() >= 1 && r.slaDays() <= 365) s.setSlaDays(r.slaDays());

        return ResponseEntity.ok(new SettingDto(s.getPrefix(), s.getNoSize(),
                s.getNoStart(), s.getSlaDays()));
    }

    // ---------- helper ----------

    private int slaDays(String sp) {
        List<?> r = em.createNativeQuery(
                "SELECT sla_days FROM adu_setting WHERE sp_code = :sp")
                .setParameter("sp", sp).getResultList();
        return r.isEmpty() ? 5 : ((Number) r.get(0)).intValue();
    }

    private static long num(Object v) {
        return v == null ? 0L : ((Number) v).longValue();
    }

    private static BigDecimal dec(Object v) {
        return v == null ? BigDecimal.ZERO
                : new BigDecimal(v.toString()).setScale(1, RoundingMode.HALF_UP);
    }

    private String sp() {
        String sp = TenantContext.get();
        if (sp == null || sp.isBlank()) {
            throw new IllegalStateException("Header X-SP-Id diperlukan");
        }
        return sp;
    }
}
