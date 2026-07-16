package com.monthley.platform.internal;

import com.monthley.shared.PageResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Senarai & carian Service Provider — superadmin.
 *   GET /api/v1/platform/service-providers?q=&bizType=&status=&plan=&state=&page=&size=
 *
 * Bilangan akaun DIDERIVE (COUNT), bukan cache — supaya had pakej sentiasa tepat.
 */
@RestController
@RequestMapping("/api/v1/platform/service-providers")
class ServiceProviderController {

    @PersistenceContext
    private EntityManager em;

    record SpRow(
            String spCode, String name, String bizType, String bizTypeName,
            String state, String city, String status,
            String planName, Integer accountLimit, long accountCount,
            String billingPlan, BigDecimal price,
            String adminEmail, LocalDate approvedAt) {}

    @GetMapping
    @SuppressWarnings("unchecked")
    PageResponse<SpRow> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String bizType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long plan,
            @RequestParam(required = false) String state,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        String search = (q == null || q.isBlank()) ? null : "%" + q.trim().toLowerCase() + "%";

        String where = """
            WHERE (:q IS NULL OR LOWER(sp.sp_code) LIKE :q OR LOWER(sp.name) LIKE :q
                   OR LOWER(COALESCE(sp.registration_no,'')) LIKE :q)
              AND (:bizType IS NULL OR sp.business_type = :bizType)
              AND (:status  IS NULL OR sp.status = :status)
              AND (:plan    IS NULL OR sp.service_plan_id = :plan)
              AND (:state   IS NULL OR sp.state = :state)
            """;

        var countQ = em.createNativeQuery("SELECT COUNT(*) FROM service_provider sp " + where);
        bind(countQ, search, bizType, status, plan, state);
        long total = ((Number) countQ.getSingleResult()).longValue();

        String sql = """
            SELECT sp.sp_code, sp.name, sp.business_type, bt.name AS biz_name,
                   sp.state, sp.city, sp.status,
                   pl.name AS plan_name, pl.account_limit,
                   COALESCE((SELECT COUNT(*) FROM account a
                             WHERE a.sp_code = sp.sp_code AND a.status = 'ACTIVE'), 0) AS acc_count,
                   sp.billing_plan,
                   CASE WHEN sp.billing_plan = 'YEARLY' THEN pl.price_yearly ELSE pl.price_monthly END AS price,
                   sp.contact_email, sp.approved_at
            FROM service_provider sp
            LEFT JOIN ref_business_type bt ON bt.code = sp.business_type
            LEFT JOIN service_plan pl      ON pl.id   = sp.service_plan_id
            """ + where + " ORDER BY sp.sp_code LIMIT :lim OFFSET :off";

        var dataQ = em.createNativeQuery(sql);
        bind(dataQ, search, bizType, status, plan, state);
        dataQ.setParameter("lim", size);
        dataQ.setParameter("off", page * size);

        List<Object[]> rows = dataQ.getResultList();
        List<SpRow> items = new ArrayList<>();
        for (Object[] r : rows) {
            items.add(new SpRow(
                    (String) r[0], (String) r[1], (String) r[2], (String) r[3],
                    (String) r[4], (String) r[5], (String) r[6],
                    (String) r[7],
                    r[8] == null ? null : ((Number) r[8]).intValue(),
                    ((Number) r[9]).longValue(),
                    (String) r[10],
                    (BigDecimal) r[11],
                    (String) r[12],
                    toLocalDate(r[13])));
        }
        return new PageResponse<>(items, total, page, size);
    }

    /** Ringkasan untuk kad KPI di atas senarai. */
    @GetMapping("/summary")
    java.util.Map<String, Object> summary() {
        Object[] r = (Object[]) em.createNativeQuery("""
                SELECT COUNT(*),
                       SUM(CASE WHEN status = 'ACTIVE'    THEN 1 ELSE 0 END),
                       SUM(CASE WHEN status = 'PENDING'   THEN 1 ELSE 0 END),
                       SUM(CASE WHEN status = 'SUSPENDED' THEN 1 ELSE 0 END)
                FROM service_provider
                """).getSingleResult();
        return java.util.Map.of(
                "total",     ((Number) r[0]).longValue(),
                "active",    r[1] == null ? 0L : ((Number) r[1]).longValue(),
                "pending",   r[2] == null ? 0L : ((Number) r[2]).longValue(),
                "suspended", r[3] == null ? 0L : ((Number) r[3]).longValue());
    }

    @PatchMapping("/{spCode}/status")
    @org.springframework.transaction.annotation.Transactional
    java.util.Map<String, String> changeStatus(@PathVariable String spCode,
                                               @RequestBody java.util.Map<String, String> body) {
        String newStatus = body.get("status");
        em.createNativeQuery("UPDATE service_provider SET status = :s WHERE sp_code = :sp")
                .setParameter("s", newStatus)
                .setParameter("sp", spCode)
                .executeUpdate();
        return java.util.Map.of("spCode", spCode, "status", newStatus);
    }


    /** Driver MySQL 9 boleh pulangkan LocalDateTime / Timestamp / String — terima semua. */
    private static LocalDate toLocalDate(Object o) {
        if (o == null) return null;
        if (o instanceof LocalDate d) return d;
        if (o instanceof java.time.LocalDateTime dt) return dt.toLocalDate();
        if (o instanceof java.sql.Timestamp ts) return ts.toLocalDateTime().toLocalDate();
        if (o instanceof java.sql.Date d) return d.toLocalDate();
        return null;
    }

    private void bind(jakarta.persistence.Query query, String q, String bizType,
                      String status, Long plan, String state) {
        query.setParameter("q", q);
        query.setParameter("bizType", (bizType == null || bizType.isBlank()) ? null : bizType);
        query.setParameter("status",  (status  == null || status.isBlank())  ? null : status);
        query.setParameter("plan", plan);
        query.setParameter("state",   (state   == null || state.isBlank())   ? null : state);
    }
}
