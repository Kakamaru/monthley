package com.monthley.complaints.internal;

import com.monthley.shared.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Aduan — sisi PELANGGAN.
 *
 * Skopnya MERENTAS SP dan bukan TenantContext: pelanggan boleh membayar
 * beberapa SP (JMB tempat tinggal, kelab, sekolah anak) dan mahu melihat
 * semua aduannya dalam satu senarai. Corak sama dengan 'Akaun Saya'.
 *
 * Pemilikan disemak melalui account.payer_user_id pada SETIAP operasi.
 * Menyemaknya sekali semasa senarai dan mempercayai id selepas itu
 * bermakna pelanggan boleh membaca aduan orang lain dengan menukar id
 * dalam URL.
 */
@RestController
@RequestMapping("/api/v1/my-complaints")
class AduCustomerController {

    private final AduService service;

    @PersistenceContext
    private EntityManager em;

    AduCustomerController(AduService service) {
        this.service = service;
    }

    record MyComplaintRow(Long id, String complaintNo, String subject,
                          String spName, String accountNo, String categoryName,
                          String status, LocalDateTime createdAt) {}

    record MyReply(String message, String byName, boolean fromSp, LocalDateTime createdAt) {}

    record MyDetail(MyComplaintRow header, String detail, List<MyReply> replies,
                    boolean canReply) {}

    record MyAccount(Long accountId, String accountNo, String accountName,
                     String spCode, String spName) {}

    record NewRequest(Long accountId, Long categoryId, String subject, String detail) {}

    record ReplyRequest(String message) {}

    /** Akaun yang pelanggan bayar — pilihan dalam borang Buat Aduan. */
    @GetMapping("/accounts")
    @SuppressWarnings("unchecked")
    List<MyAccount> accounts() {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT a.id, a.account_no, a.account_name, sp.sp_code, sp.name
                FROM   account a
                JOIN   service_provider sp ON sp.sp_code = a.sp_code
                JOIN   sp_module m ON m.sp_code = a.sp_code
                                  AND m.module_code = 'ADUAN' AND m.status = 'ACTIVE'
                WHERE  a.payer_user_id = :uid AND a.status = 'ACTIVE'
                ORDER  BY sp.name, a.account_no
                """).setParameter("uid", uid()).getResultList();

        List<MyAccount> out = new ArrayList<>();
        for (Object[] r : rows) {
            out.add(new MyAccount(((Number) r[0]).longValue(), (String) r[1],
                    (String) r[2], (String) r[3], (String) r[4]));
        }
        return out;
    }

    /** Kategori bagi SP yang memiliki akaun ini. */
    @GetMapping("/categories")
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> categories(@RequestParam Long accountId) {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT g.id, g.name
                FROM   adu_category g
                JOIN   account a ON a.sp_code = g.sp_code
                WHERE  a.id = :acc AND a.payer_user_id = :uid AND g.status = 'ACTIVE'
                ORDER  BY g.sort_order, g.name
                """).setParameter("acc", accountId).setParameter("uid", uid())
                .getResultList();

        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) {
            out.add(Map.of("id", ((Number) r[0]).longValue(), "name", r[1]));
        }
        return out;
    }

    @GetMapping
    @SuppressWarnings("unchecked")
    List<MyComplaintRow> list(@RequestParam(required = false) String status,
                              @RequestParam(required = false) Long category) {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT c.id, c.complaint_no, c.subject, sp.name, a.account_no,
                       g.name, c.status, c.created_at
                FROM   adu_complaint c
                JOIN   account a ON a.id = c.account_id
                JOIN   service_provider sp ON sp.sp_code = c.sp_code
                LEFT   JOIN adu_category g ON g.id = c.category_id
                WHERE  a.payer_user_id = :uid
                  AND (:st  IS NULL OR c.status = :st)
                  AND (:cat IS NULL OR c.category_id = :cat)
                ORDER  BY c.created_at DESC
                """)
                .setParameter("uid", uid())
                .setParameter("st", (status == null || status.isBlank()
                        || "ALL".equalsIgnoreCase(status)) ? null : status.toUpperCase())
                .setParameter("cat", category)
                .getResultList();

        List<MyComplaintRow> out = new ArrayList<>();
        for (Object[] r : rows) out.add(baris(r));
        return out;
    }

    @GetMapping("/{id}")
    @SuppressWarnings("unchecked")
    MyDetail get(@PathVariable Long id) {
        List<?> ketua = em.createNativeQuery("""
                SELECT c.id, c.complaint_no, c.subject, sp.name, a.account_no,
                       g.name, c.status, c.created_at, c.detail
                FROM   adu_complaint c
                JOIN   account a ON a.id = c.account_id
                JOIN   service_provider sp ON sp.sp_code = c.sp_code
                LEFT   JOIN adu_category g ON g.id = c.category_id
                WHERE  c.id = :id AND a.payer_user_id = :uid
                """).setParameter("id", id).setParameter("uid", uid()).getResultList();

        if (ketua.isEmpty()) {
            throw new IllegalStateException("Aduan tidak dijumpai.");
        }
        Object[] h = (Object[]) ketua.get(0);

        // Nota dalaman DITAPIS — pengadu tidak nampak.
        List<Object[]> rows = em.createNativeQuery("""
                SELECT r.message, u.full_name, r.from_sp, r.created_at
                FROM   adu_reply r
                LEFT   JOIN app_user u ON u.id = r.replied_by
                WHERE  r.complaint_id = :id AND r.internal = 0
                ORDER  BY r.created_at
                """).setParameter("id", id).getResultList();

        List<MyReply> thread = new ArrayList<>();
        for (Object[] r : rows) {
            thread.add(new MyReply((String) r[0], (String) r[1], bool(r[2]), toDt(r[3])));
        }

        return new MyDetail(baris(h), (String) h[8], thread, true);
    }

    @PostMapping
    ResponseEntity<?> create(@RequestBody NewRequest r) {
        String sp = spBagiAkaun(r.accountId());
        // Konteks ditetapkan daripada AKAUN dan bukan header: pelanggan
        // tidak mempunyai SP semasa — dia membayar beberapa.
        String asal = TenantContext.get();
        try {
            TenantContext.set(sp);
            Long id = service.create(new AduService.NewComplaint(
                    r.accountId(), r.categoryId(), r.subject(), r.detail(),
                    null, namaSaya(), telefonSaya()), uid(), false);
            return ResponseEntity.ok(Map.of("id", id));
        } finally {
            if (asal == null) TenantContext.clear(); else TenantContext.set(asal);
        }
    }

    @PostMapping("/{id}/reply")
    ResponseEntity<?> reply(@PathVariable Long id, @RequestBody ReplyRequest r) {
        String sp = spBagiAduan(id);
        String asal = TenantContext.get();
        try {
            TenantContext.set(sp);
            service.reply(id, new AduService.ReplyRequest(
                    r.message(), null, null, null, false), uid(), false);
            return ResponseEntity.ok(Map.of("message", "Balasan dihantar."));
        } finally {
            if (asal == null) TenantContext.clear(); else TenantContext.set(asal);
        }
    }

    // ---------- helper ----------

    private MyComplaintRow baris(Object[] r) {
        return new MyComplaintRow(((Number) r[0]).longValue(), (String) r[1],
                (String) r[2], (String) r[3], (String) r[4], (String) r[5],
                (String) r[6], toDt(r[7]));
    }

    /** Sahkan pemilikan DAN dapatkan SP dalam satu query. */
    private String spBagiAkaun(Long accountId) {
        List<?> r = em.createNativeQuery(
                "SELECT sp_code FROM account WHERE id = :a AND payer_user_id = :uid")
                .setParameter("a", accountId).setParameter("uid", uid()).getResultList();
        if (r.isEmpty()) {
            throw new IllegalStateException("Akaun bukan milik anda.");
        }
        return (String) r.get(0);
    }

    private String spBagiAduan(Long complaintId) {
        List<?> r = em.createNativeQuery("""
                SELECT c.sp_code FROM adu_complaint c
                JOIN   account a ON a.id = c.account_id
                WHERE  c.id = :id AND a.payer_user_id = :uid
                """).setParameter("id", complaintId).setParameter("uid", uid())
                .getResultList();
        if (r.isEmpty()) {
            throw new IllegalStateException("Aduan bukan milik anda.");
        }
        return (String) r.get(0);
    }

    private String namaSaya() {
        List<?> r = em.createNativeQuery("SELECT full_name FROM app_user WHERE id = :uid")
                .setParameter("uid", uid()).getResultList();
        return r.isEmpty() ? null : (String) r.get(0);
    }

    private String telefonSaya() {
        List<?> r = em.createNativeQuery("SELECT mobile FROM app_user WHERE id = :uid")
                .setParameter("uid", uid()).getResultList();
        return r.isEmpty() ? null : (String) r.get(0);
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

    private Long uid() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new IllegalStateException("Tiada pengguna dalam konteks.");
        }
        return Long.valueOf(auth.getName());
    }
}
