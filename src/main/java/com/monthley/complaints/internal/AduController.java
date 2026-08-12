package com.monthley.complaints.internal;

import com.monthley.shared.Access;
import com.monthley.shared.ModuleGuard;
import com.monthley.shared.PageResponse;
import com.monthley.shared.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Aduan — sisi SP.
 *
 *   GET  /api/v1/complaints?status=&category=&priority=&q=&from=&to=&page=&size=
 *   GET  /api/v1/complaints/{id}
 *   POST /api/v1/complaints            (kerani merekod aduan telefon)
 *   POST /api/v1/complaints/{id}/reply
 *   GET  /api/v1/complaints/assignees
 *   GET  /api/v1/complaints/dashboard
 */
@RestController
@RequestMapping("/api/v1/complaints")
class AduController {

    private final AduService service;

    @PersistenceContext
    private EntityManager em;

    AduController(AduService service) {
        this.service = service;
    }

    record ComplaintRow(Long id, String complaintNo, String subject,
                        String accountNo, String accountName, String reporterName,
                        String categoryName, String priority, String status,
                        LocalDateTime createdAt, String assignedName,
                        int ageDays, boolean overSla) {}

    record ReplyRow(Long id, String message, String byName, boolean fromSp,
                    boolean internal, LocalDateTime createdAt) {}

    record ComplaintDetail(ComplaintRow header, String detail, String reporterPhone,
                           String internalNote, Long categoryId, Long assignedTo,
                           List<ReplyRow> replies) {}

    record NewComplaintRequest(Long accountId, Long categoryId, String subject,
                               String detail, String priority,
                               String reporterName, String reporterPhone) {}

    record ReplyRequestBody(String message, String status, Long assignedTo,
                            String internalNote, Boolean internal) {}

    // ---------- Senarai ----------

    @GetMapping
    @SuppressWarnings("unchecked")
    PageResponse<ComplaintRow> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long category,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        Access.requireAnyRole("melihat aduan", "SP_ADMIN", "CLERK");

        String base = """
            FROM   adu_complaint c
            JOIN   account a ON a.id = c.account_id
            LEFT   JOIN adu_category g ON g.id = c.category_id
            LEFT   JOIN app_user u ON u.id = c.assigned_to
            WHERE  c.sp_code = :sp
              AND (:st   IS NULL OR c.status = :st)
              AND (:cat  IS NULL OR c.category_id = :cat)
              AND (:pri  IS NULL OR c.priority = :pri)
              AND (:from IS NULL OR DATE(c.created_at) >= :from)
              AND (:to   IS NULL OR DATE(c.created_at) <= :to)
              AND (:q    IS NULL OR c.complaint_no LIKE :like OR c.subject LIKE :like
                                 OR a.account_name LIKE :like OR a.account_no LIKE :like
                                 OR c.reporter_name LIKE :like)
            """;

        String cari = (q == null || q.isBlank()) ? null : q.trim();
        String like = cari == null ? null : "%" + cari + "%";

        var countQ = em.createNativeQuery("SELECT COUNT(*) " + base);
        isi(countQ, status, category, priority, from, to, cari, like);
        long total = ((Number) countQ.getSingleResult()).longValue();

        var qy = em.createNativeQuery(
                "SELECT c.id, c.complaint_no, c.subject, a.account_no, a.account_name, "
                + "c.reporter_name, g.name, c.priority, c.status, c.created_at, u.full_name, "
                + "DATEDIFF(CURDATE(), DATE(c.created_at)) AS umur "
                + base + " ORDER BY c.created_at DESC LIMIT :lim OFFSET :off");
        isi(qy, status, category, priority, from, to, cari, like);
        qy.setParameter("lim", size).setParameter("off", page * size);

        int sla = slaDays(sp());
        List<ComplaintRow> items = new ArrayList<>();
        for (Object[] r : (List<Object[]>) qy.getResultList()) {
            items.add(baris(r, sla));
        }
        return new PageResponse<>(items, total, page, size);
    }

    private void isi(jakarta.persistence.Query qy, String status, Long category,
                     String priority, LocalDate from, LocalDate to, String cari, String like) {
        qy.setParameter("sp", sp());
        qy.setParameter("st", (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status))
                ? null : status.toUpperCase());
        qy.setParameter("cat", category);
        qy.setParameter("pri", (priority == null || priority.isBlank()
                || "ALL".equalsIgnoreCase(priority)) ? null : priority.toUpperCase());
        qy.setParameter("from", from);
        qy.setParameter("to", to);
        qy.setParameter("q", cari);
        qy.setParameter("like", like);
    }

    @GetMapping("/{id}")
    @SuppressWarnings("unchecked")
    ComplaintDetail get(@PathVariable Long id) {
        Access.requireAnyRole("melihat aduan", "SP_ADMIN", "CLERK");

        Object[] h = (Object[]) em.createNativeQuery("""
                SELECT c.id, c.complaint_no, c.subject, a.account_no, a.account_name,
                       c.reporter_name, g.name, c.priority, c.status, c.created_at,
                       u.full_name, DATEDIFF(CURDATE(), DATE(c.created_at)),
                       c.detail, c.reporter_phone, c.internal_note,
                       c.category_id, c.assigned_to
                FROM   adu_complaint c
                JOIN   account a ON a.id = c.account_id
                LEFT   JOIN adu_category g ON g.id = c.category_id
                LEFT   JOIN app_user u ON u.id = c.assigned_to
                WHERE  c.sp_code = :sp AND c.id = :id
                """).setParameter("sp", sp()).setParameter("id", id).getSingleResult();

        // SP nampak SEMUA balasan termasuk nota dalaman.
        List<ReplyRow> thread = replies(id, true);

        return new ComplaintDetail(baris(h, slaDays(sp())),
                (String) h[12], (String) h[13], (String) h[14],
                h[15] == null ? null : ((Number) h[15]).longValue(),
                h[16] == null ? null : ((Number) h[16]).longValue(),
                thread);
    }

    @SuppressWarnings("unchecked")
    private List<ReplyRow> replies(Long complaintId, boolean termasukDalaman) {
        var q = em.createNativeQuery("""
                SELECT r.id, r.message, u.full_name, r.from_sp, r.internal, r.created_at
                FROM   adu_reply r
                LEFT   JOIN app_user u ON u.id = r.replied_by
                WHERE  r.complaint_id = :id
                  AND (:semua = 1 OR r.internal = 0)
                ORDER  BY r.created_at
                """).setParameter("id", complaintId)
                .setParameter("semua", termasukDalaman ? 1 : 0);

        List<ReplyRow> out = new ArrayList<>();
        for (Object[] r : (List<Object[]>) q.getResultList()) {
            out.add(new ReplyRow(((Number) r[0]).longValue(), (String) r[1],
                    (String) r[2], bool(r[3]), bool(r[4]), toDt(r[5])));
        }
        return out;
    }

    // ---------- Tulis ----------

    /** Kerani merekod aduan daripada panggilan telefon. */
    @PostMapping
    ResponseEntity<?> create(@RequestBody NewComplaintRequest r) {
        Access.requireAnyRole("merekod aduan", "SP_ADMIN", "CLERK");
        Long id = service.create(new AduService.NewComplaint(
                r.accountId(), r.categoryId(), r.subject(), r.detail(),
                r.priority(), r.reporterName(), r.reporterPhone()),
                currentUserId(), true);
        return ResponseEntity.ok(Map.of("id", id));
    }

    @PostMapping("/{id}/reply")
    ResponseEntity<?> reply(@PathVariable Long id, @RequestBody ReplyRequestBody r) {
        Access.requireAnyRole("membalas aduan", "SP_ADMIN", "CLERK");
        service.reply(id, new AduService.ReplyRequest(
                r.message(), r.status(), r.assignedTo(), r.internalNote(),
                r.internal() != null && r.internal()), currentUserId(), true);
        return ResponseEntity.ok(Map.of("message", "Aduan dikemas kini."));
    }

    // ---------- Rujukan ----------

    record Assignee(Long id, String name, String email) {}

    /** Pengguna SP yang boleh ditugaskan — SP_ADMIN dan CLERK sahaja. */
    @GetMapping("/assignees")
    @SuppressWarnings("unchecked")
    List<Assignee> assignees() {
        Access.requireAnyRole("melihat senarai petugas", "SP_ADMIN", "CLERK");

        List<Object[]> rows = em.createNativeQuery("""
                SELECT DISTINCT u.id, u.full_name, u.email
                FROM   app_user u
                JOIN   sp_membership m ON m.user_id = u.id
                WHERE  m.sp_code = :sp AND m.status = 'ACTIVE'
                  AND  m.role IN ('SP_ADMIN','CLERK')
                  AND  u.status = 'ACTIVE'
                ORDER  BY u.full_name
                """).setParameter("sp", sp()).getResultList();

        List<Assignee> out = new ArrayList<>();
        for (Object[] r : rows) {
            out.add(new Assignee(((Number) r[0]).longValue(), (String) r[1], (String) r[2]));
        }
        return out;
    }

    // ---------- helper ----------

    private ComplaintRow baris(Object[] r, int sla) {
        int umur = r[11] == null ? 0 : ((Number) r[11]).intValue();
        String status = (String) r[8];
        boolean belumSelesai = !"RESOLVED".equals(status);
        return new ComplaintRow(
                ((Number) r[0]).longValue(), (String) r[1], (String) r[2],
                (String) r[3], (String) r[4], (String) r[5], (String) r[6],
                (String) r[7], status, toDt(r[9]), (String) r[10],
                umur, belumSelesai && umur > sla);
    }

    private int slaDays(String sp) {
        List<?> r = em.createNativeQuery(
                "SELECT sla_days FROM adu_setting WHERE sp_code = :sp")
                .setParameter("sp", sp).getResultList();
        return r.isEmpty() ? 5 : ((Number) r.get(0)).intValue();
    }

    private static boolean bool(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        return ((Number) v).intValue() != 0;
    }

    private static LocalDateTime toDt(Object v) {
        if (v == null) return null;
        if (v instanceof LocalDateTime d) return d;
        if (v instanceof java.sql.Timestamp t) return t.toLocalDateTime();
        return null;
    }

    private Long currentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new IllegalStateException("Tiada pengguna dalam konteks.");
        }
        return Long.valueOf(auth.getName());
    }

    private String sp() {
        String sp = TenantContext.get();
        if (sp == null || sp.isBlank()) {
            throw new IllegalStateException("Header X-SP-Id diperlukan");
        }
        return sp;
    }
}
