package com.monthley.memo.internal;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Memo — sisi PELANGGAN.
 *
 * Merentas SP seperti aduan: pelanggan membayar beberapa SP dan mahu satu
 * senarai. Memo dipapar hanya untuk SP yang pelanggan mempunyai akaun
 * AKTIF dengannya — memo bukan awam.
 *
 * Baca sahaja; tiada balasan.
 */
@RestController
@RequestMapping("/api/v1/my-memos")
class MemoCustomerController {

    @PersistenceContext
    private EntityManager em;

    record MyMemo(Long id, String spName, String title, String body,
                  LocalDateTime publishedAt, LocalDate expiresOn, boolean expired) {}

    /**
     * @param scope "ACTIVE" (lalai) atau "PAST"
     *
     * Memo tanpa tarikh luput sentiasa AKTIF — itu maksud NULL.
     */
    @GetMapping
    @SuppressWarnings("unchecked")
    List<MyMemo> list(@RequestParam(required = false) String scope) {
        boolean lama = "PAST".equalsIgnoreCase(scope);

        List<Object[]> rows = em.createNativeQuery("""
                SELECT DISTINCT m.id, sp.name, m.title, m.body,
                       m.published_at, m.expires_on
                FROM   memo_notice m
                JOIN   service_provider sp ON sp.sp_code = m.sp_code
                JOIN   account a ON a.sp_code = m.sp_code
                WHERE  a.payer_user_id = :uid AND a.status = 'ACTIVE'
                  AND  m.status = 'PUBLISHED'
                  AND (
                        (:lama = 0 AND (m.expires_on IS NULL OR m.expires_on >= CURDATE()))
                     OR (:lama = 1 AND  m.expires_on IS NOT NULL AND m.expires_on < CURDATE())
                      )
                ORDER  BY m.published_at DESC
                """)
                .setParameter("uid", uid())
                .setParameter("lama", lama ? 1 : 0)
                .getResultList();

        List<MyMemo> out = new ArrayList<>();
        for (Object[] r : rows) {
            out.add(new MyMemo(((Number) r[0]).longValue(), (String) r[1],
                    (String) r[2], (String) r[3], toDt(r[4]), toDate(r[5]), lama));
        }
        return out;
    }

    private static LocalDate toDate(Object v) {
        if (v == null) return null;
        if (v instanceof LocalDate d) return d;
        if (v instanceof java.sql.Date d) return d.toLocalDate();
        return null;
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
