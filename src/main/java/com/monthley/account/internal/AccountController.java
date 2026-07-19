package com.monthley.account.internal;

import com.monthley.shared.ChargeFrequency;
import com.monthley.shared.PageResponse;
import com.monthley.shared.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
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

    private final AccountRepository accounts;

    AccountController(AccountRepository accounts) {
        this.accounts = accounts;
    }

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

    record SaveAccountRequest(
            @NotBlank String accountNo,
            @NotBlank String accountName,
            Long categoryId,
            String chargeFrequency,
            LocalDate startDate,
            LocalDate expiryDate,
            // Ahli
            String memberName, String memberIdNo, String memberEmail, String memberMobile,
            // Alamat akaun
            String addrLine1, String addrLine2, String addrLine3, String addrLine4,
            String addrPostcode, String addrState, String addrCountry,
            // Bil kepada
            String billtoName, String billtoEmail, String billtoMobile,
            String billtoAddrLine1, String billtoAddrLine2, String billtoAddrLine3, String billtoAddrLine4,
            String billtoPostcode, String billtoState, String billtoCountry) {}

    @PostMapping
    @Transactional
    ResponseEntity<?> create(@Valid @RequestBody SaveAccountRequest r) {
        String sp = sp();

        // account_no mesti unik dalam SP
        Long wujud = ((Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM account WHERE sp_code = :sp AND account_no = :no")
                .setParameter("sp", sp).setParameter("no", r.accountNo().trim())
                .getSingleResult()).longValue();
        if (wujud > 0) {
            return ResponseEntity.badRequest()
                    .body(java.util.Map.of("message", "No. akaun " + r.accountNo() + " sudah wujud."));
        }

        Account a = new Account(sp, r.accountNo().trim(), r.accountName().trim());
        apply(a, r);
        Account saved = accounts.save(a);
        return ResponseEntity.ok(java.util.Map.of("id", saved.getId(),
                "message", "Akaun " + saved.getAccountNo() + " dicipta."));
    }

    private void apply(Account a, SaveAccountRequest r) {
        a.setCategoryId(r.categoryId());
        if (r.chargeFrequency() != null && !r.chargeFrequency().isBlank()) {
            a.setChargeFrequency(ChargeFrequency.valueOf(r.chargeFrequency()));
        }
        a.setStartDate(r.startDate());
        a.setExpiryDate(r.expiryDate());

        a.setMemberName(r.memberName());
        a.setMemberIdNo(r.memberIdNo());
        a.setMemberEmail(r.memberEmail());
        a.setMemberMobile(r.memberMobile());

        a.setAddrLine1(r.addrLine1());
        a.setAddrLine2(r.addrLine2());
        a.setAddrLine3(r.addrLine3());
        a.setAddrLine4(r.addrLine4());
        a.setAddrPostcode(r.addrPostcode());
        a.setAddrState(r.addrState());
        a.setAddrCountry(r.addrCountry());

        a.setBilltoName(r.billtoName());
        a.setBilltoEmail(r.billtoEmail());
        a.setBilltoMobile(r.billtoMobile());
        a.setBilltoAddrLine1(r.billtoAddrLine1());
        a.setBilltoAddrLine2(r.billtoAddrLine2());
        a.setBilltoAddrLine3(r.billtoAddrLine3());
        a.setBilltoAddrLine4(r.billtoAddrLine4());
        a.setBilltoPostcode(r.billtoPostcode());
        a.setBilltoState(r.billtoState());
        a.setBilltoCountry(r.billtoCountry());
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
