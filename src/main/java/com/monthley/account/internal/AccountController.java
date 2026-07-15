package com.monthley.account.internal;

import com.monthley.shared.PageResponse;
import com.monthley.shared.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * REST untuk skrin Accounts (rujuk handoff §5).
 *   GET /api/v1/accounts?status=&category=&linked=&q=&page=&size=
 *
 * BAKI DIDERIVE dari dokumen (invois − peruntukan aktif), bukan cached_balance.
 * Ini prinsip teras revamp: cache bukan sumber kebenaran.
 */
@RestController
@RequestMapping("/api/v1/accounts")
class AccountController {

    @PersistenceContext
    private EntityManager em;

    record AccountDto(
            Long id, String no, String name, String billTo,
            BigDecimal balance, boolean linked, Long categoryId,
            String chargeFrequency, boolean active) {}

    @GetMapping
    @SuppressWarnings("unchecked")
    PageResponse<AccountDto> list(
            @RequestParam(defaultValue = "true") boolean active,
            @RequestParam(required = false) Long category,
            @RequestParam(required = false) Boolean linked,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        String status = active ? "ACTIVE" : "INACTIVE";
        String search = (q == null || q.isBlank()) ? null : "%" + q.trim().toLowerCase() + "%";

        String where = """
            WHERE a.sp_code = :sp
              AND a.status = :status
              AND (:category IS NULL OR a.category_id = :category)
              AND (:linkedFlag IS NULL
                   OR (:linkedFlag = 1 AND a.payer_user_id IS NOT NULL)
                   OR (:linkedFlag = 0 AND a.payer_user_id IS NULL))
              AND (:q IS NULL OR LOWER(a.account_no) LIKE :q OR LOWER(a.account_name) LIKE :q)
            """;

        // Jumlah rekod
        var countQ = em.createNativeQuery("SELECT COUNT(*) FROM account a " + where);
        bind(countQ, status, category, linked, search);
        long total = ((Number) countQ.getSingleResult()).longValue();

        // Data + baki diderive
        String sql = """
            SELECT a.id, a.account_no, a.account_name, a.payer_user_id, a.category_id,
                   a.charge_frequency, a.status,
                   COALESCE((
                     SELECT SUM(d.amount + d.tax_amount)
                     FROM financial_document d
                     WHERE d.account_id = a.id AND d.doc_type = 'INVOICE'
                       AND d.status <> 'CANCELLED'
                   ), 0)
                   -
                   COALESCE((
                     SELECT SUM(al.amount)
                     FROM fi_allocation al
                     JOIN financial_document d2 ON d2.id = al.debit_document_id
                     WHERE d2.account_id = a.id AND al.status = 'ACTIVE'
                   ), 0) AS balance
            FROM account a
            """ + where + " ORDER BY a.account_no LIMIT :lim OFFSET :off";

        var dataQ = em.createNativeQuery(sql);
        bind(dataQ, status, category, linked, search);
        dataQ.setParameter("lim", size);
        dataQ.setParameter("off", page * size);

        List<Object[]> rows = dataQ.getResultList();
        List<AccountDto> items = new ArrayList<>();
        for (Object[] r : rows) {
            items.add(new AccountDto(
                    ((Number) r[0]).longValue(),
                    (String) r[1],
                    (String) r[2],
                    (String) r[2],                       // billTo — sementara guna nama akaun
                    (BigDecimal) r[7],
                    r[3] != null,                        // linked
                    r[4] == null ? null : ((Number) r[4]).longValue(),
                    (String) r[5],
                    "ACTIVE".equals(r[6])));
        }
        return new PageResponse<>(items, total, page, size);
    }

    private void bind(jakarta.persistence.Query query, String status, Long category,
                      Boolean linked, String search) {
        query.setParameter("sp", sp());
        query.setParameter("status", status);
        query.setParameter("category", category);
        query.setParameter("linkedFlag", linked == null ? null : (linked ? 1 : 0));
        query.setParameter("q", search);
    }

    private String sp() {
        String sp = TenantContext.get();
        if (sp == null || sp.isBlank()) {
            throw new IllegalStateException("Header X-SP-Id diperlukan");
        }
        return sp;
    }
}
