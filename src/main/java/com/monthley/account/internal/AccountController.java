package com.monthley.account.internal;

import com.monthley.shared.ChargeFrequency;
import com.monthley.tenancy.api.BillingSettingsPort;
import com.monthley.notification.api.EmailPort;
import com.monthley.shared.PageResponse;
import com.monthley.shared.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
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
    private final AccountSubscriptionRepository subscriptions;
    private final BillingSettingsPort settings;
    private final AccountInvitationRepository invitations;
    private final EmailPort email;

    AccountController(AccountRepository accounts, AccountSubscriptionRepository subscriptions,
                      BillingSettingsPort settings, AccountInvitationRepository invitations,
                      EmailPort email) {
        this.accounts = accounts;
        this.subscriptions = subscriptions;
        this.settings = settings;
        this.invitations = invitations;
        this.email = email;
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
                     WHERE d.account_id = a.id AND d.doc_type IN ('INVOICE','DEBIT_NOTE')
                       AND d.status <> 'CANCELLED'
                   ), 0)
                   -
                   COALESCE((
                     SELECT SUM(d2.amount + d2.tax_amount)
                     FROM financial_document d2
                     WHERE d2.account_id = a.id AND d2.doc_type = 'RECEIPT'
                       AND d2.status <> 'CANCELLED'
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
            String billtoPostcode, String billtoState, String billtoCountry,
            // Tambahan (V25)
            String billtoEmailSecondary, java.math.BigDecimal depositAmount,
            java.math.BigDecimal openingAmount, String remarks, String accountType,
            // Langganan produk (jadual)
            List<SubLine> subscriptions) {}

    record SubLine(
            Long productId, java.math.BigDecimal quantity,
            LocalDate startDate, LocalDate endDate, java.math.BigDecimal unitPrice) {}

    @GetMapping("/config")
    java.util.Map<String, Object> config() {
        var cfg = settings.forSp(sp());
        return java.util.Map.of("allowPriceOverride", cfg.allowPriceOverride());
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    ResponseEntity<?> getOne(@PathVariable Long id) {
        String sp = sp();
        Account a = accounts.findById(id).orElse(null);
        if (a == null || !sp.equals(a.getSpCode())) {
            return ResponseEntity.notFound().build();
        }

        List<Object[]> rows = em.createNativeQuery("""
                SELECT s.id, s.product_id, p.code, p.name, s.quantity,
                       s.start_date, s.end_date, s.unit_price, p.charge_frequency, p.unit_rate
                FROM account_subscription s
                JOIN product p ON p.id = s.product_id
                WHERE s.account_id = :aid AND s.status <> 'ENDED'
                ORDER BY s.id
                """).setParameter("aid", id).getResultList();

        List<java.util.Map<String, Object>> subs = new ArrayList<>();
        for (Object[] r : rows) {
            var m = new java.util.HashMap<String, Object>();
            m.put("id", ((Number) r[0]).longValue());
            m.put("productId", ((Number) r[1]).longValue());
            m.put("code", r[2]);
            m.put("name", r[3]);
            m.put("quantity", r[4]);
            m.put("startDate", r[5] == null ? null : r[5].toString());
            m.put("endDate", r[6] == null ? null : r[6].toString());
            m.put("unitPrice", r[7]);
            m.put("frequency", r[8]);
            m.put("rate", r[9]);
            subs.add(m);
        }

        var out = new java.util.HashMap<String, Object>();
        out.put("id", a.getId());
        out.put("accountNo", a.getAccountNo());
        out.put("accountName", a.getAccountName());
        out.put("categoryId", a.getCategoryId());
        out.put("status", a.getStatus().name());
        out.put("chargeFrequency", a.getChargeFrequency() == null ? null : a.getChargeFrequency().name());
        out.put("startDate", a.getStartDate() == null ? null : a.getStartDate().toString());
        out.put("depositAmount", a.getDepositAmount());
        out.put("openingAmount", a.getOpeningAmount());
        out.put("accountType", a.getAccountType());
        out.put("memberIdNo", a.getMemberIdNo());
        out.put("addrLine1", a.getAddrLine1()); out.put("addrLine2", a.getAddrLine2());
        out.put("addrLine3", a.getAddrLine3()); out.put("addrLine4", a.getAddrLine4());
        out.put("addrPostcode", a.getAddrPostcode()); out.put("addrState", a.getAddrState());
        out.put("addrCountry", a.getAddrCountry());
        out.put("billtoName", a.getBilltoName()); out.put("billtoEmail", a.getBilltoEmail());
        out.put("billtoEmailSecondary", a.getBilltoEmailSecondary());
        out.put("billtoMobile", a.getBilltoMobile());
        out.put("billtoAddrLine1", a.getBilltoAddrLine1()); out.put("billtoAddrLine2", a.getBilltoAddrLine2());
        out.put("billtoAddrLine3", a.getBilltoAddrLine3()); out.put("billtoAddrLine4", a.getBilltoAddrLine4());
        out.put("billtoPostcode", a.getBilltoPostcode()); out.put("billtoState", a.getBilltoState());
        out.put("billtoCountry", a.getBilltoCountry());
        out.put("remarks", a.getRemarks());
        out.put("payerUserId", a.getPayerUserId());
        // Email pengguna yang dipaut (untuk papar di UI)
        if (a.getPayerUserId() != null) {
            try {
                Object em2 = em.createNativeQuery("SELECT email FROM app_user WHERE id = :uid")
                        .setParameter("uid", a.getPayerUserId()).getSingleResult();
                out.put("linkedEmail", em2 == null ? null : em2.toString());
            } catch (Exception ignore) { out.put("linkedEmail", null); }
        } else {
            out.put("linkedEmail", null);
        }
        out.put("subscriptions", subs);
        return ResponseEntity.ok(out);
    }

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

        // Langganan produk: cipta account_subscription untuk setiap baris ditick
        int subCount = 0;
        if (r.subscriptions() != null) {
            for (SubLine line : r.subscriptions()) {
                if (line.productId() == null) continue;
                var qty = line.quantity() == null ? java.math.BigDecimal.ONE : line.quantity();
                var start = line.startDate() == null ? java.time.LocalDate.now() : line.startDate();
                var sub = new AccountSubscription(sp, saved.getId(), line.productId(), qty, start);
                if (line.unitPrice() != null) sub.setUnitPrice(line.unitPrice());
                if (line.endDate() != null) sub.setEndDate(line.endDate());
                subscriptions.save(sub);
                subCount++;
            }
        }

        // Auto-link: cek billto email berdaftar & aktif
        String bemail = r.billtoEmail() == null ? "" : r.billtoEmail().trim().toLowerCase();
        boolean autoLinked = false, autoInvited = false;
        if (!bemail.isEmpty()) {
            List<?> u = em.createNativeQuery(
                    "SELECT id FROM app_user WHERE LOWER(email) = :e AND status='ACTIVE' AND email_verified_at IS NOT NULL")
                    .setParameter("e", bemail).getResultList();
            if (!u.isEmpty()) {
                saved.setPayerUserId(((Number) u.get(0)).longValue());
                saved.setLinkDate(java.time.LocalDateTime.now());
                accounts.save(saved);
                autoLinked = true;
            } else {
                invitations.save(new AccountInvitation(sp, saved.getId(), bemail, sp));
                sendInvite(sp, bemail);
                autoInvited = true;
            }
        }

        return ResponseEntity.ok(java.util.Map.of("id", saved.getId(),
                "subscriptions", subCount,
                "linked", autoLinked, "invited", autoInvited,
                "message", "Akaun " + saved.getAccountNo() + " dicipta"
                        + (subCount > 0 ? " dengan " + subCount + " langganan." : ".")
                        + (autoLinked ? " Dipautkan ke " + bemail + "." : "")
                        + (autoInvited ? " Jemputan dihantar ke " + bemail + "." : "")));
    }

    // ── Kemas kini akaun (Edit) ──
    record EditSubLine(
            Long id, Long productId, java.math.BigDecimal quantity,
            LocalDate startDate, LocalDate endDate, java.math.BigDecimal unitPrice,
            boolean deleted) {}

    record EditAccountRequest(
            @NotBlank String accountName, Long categoryId, String status,
            String chargeFrequency, LocalDate startDate,
            String memberIdNo, String accountType, String remarks,
            // alamat akaun
            String addrLine1, String addrLine2, String addrLine3, String addrLine4,
            String addrPostcode, String addrState, String addrCountry,
            // billing
            String billtoName, String billtoEmail, String billtoEmailSecondary, String billtoMobile,
            String billtoAddrLine1, String billtoAddrLine2, String billtoAddrLine3, String billtoAddrLine4,
            String billtoPostcode, String billtoState, String billtoCountry,
            List<EditSubLine> subscriptions) {}

    @PutMapping("/{id}")
    @Transactional
    ResponseEntity<?> update(@PathVariable Long id, @Valid @RequestBody EditAccountRequest r) {
        String sp = sp();
        Account a = accounts.findById(id).orElse(null);
        if (a == null || !sp.equals(a.getSpCode())) {
            return ResponseEntity.notFound().build();
        }

        // Field boleh ubah (account_no, balance, opening, deposit KEKAL — tak disentuh)
        a.setAccountName(r.accountName().trim());
        a.setCategoryId(r.categoryId());
        if (r.status() != null) {
            a.setStatus(Account.Status.valueOf(r.status()));
        }
        if (r.chargeFrequency() != null && !r.chargeFrequency().isBlank()) {
            a.setChargeFrequency(ChargeFrequency.valueOf(r.chargeFrequency()));
        }
        a.setStartDate(r.startDate());
        a.setMemberIdNo(r.memberIdNo());
        a.setAccountType(r.accountType());
        a.setRemarks(r.remarks());
        a.setAddrLine1(r.addrLine1()); a.setAddrLine2(r.addrLine2());
        a.setAddrLine3(r.addrLine3()); a.setAddrLine4(r.addrLine4());
        a.setAddrPostcode(r.addrPostcode()); a.setAddrState(r.addrState());
        a.setAddrCountry(r.addrCountry());
        a.setBilltoName(r.billtoName()); a.setBilltoEmail(r.billtoEmail());
        a.setBilltoEmailSecondary(r.billtoEmailSecondary());
        a.setBilltoMobile(r.billtoMobile());
        a.setBilltoAddrLine1(r.billtoAddrLine1()); a.setBilltoAddrLine2(r.billtoAddrLine2());
        a.setBilltoAddrLine3(r.billtoAddrLine3()); a.setBilltoAddrLine4(r.billtoAddrLine4());
        a.setBilltoPostcode(r.billtoPostcode()); a.setBilltoState(r.billtoState());
        a.setBilltoCountry(r.billtoCountry());
        accounts.save(a);

        // Subscription: id ada + deleted -> ENDED; id ada -> kemas kini; id null -> cipta
        if (r.subscriptions() != null) {
            for (EditSubLine line : r.subscriptions()) {
                if (line.id() != null) {
                    var sub = subscriptions.findById(line.id()).orElse(null);
                    if (sub == null || !sp.equals(sub.getSpCode())) continue;
                    if (line.deleted()) {
                        sub.setStatus(AccountSubscription.Status.ENDED);   // rekod kekal
                    } else {
                        if (line.quantity() != null) sub.setQuantity(line.quantity());
                        if (line.startDate() != null) sub.setStartDate(line.startDate());
                        sub.setEndDate(line.endDate());
                        sub.setUnitPrice(line.unitPrice());
                    }
                    subscriptions.save(sub);
                } else if (!line.deleted() && line.productId() != null) {
                    var qty = line.quantity() == null ? java.math.BigDecimal.ONE : line.quantity();
                    var start = line.startDate() == null ? java.time.LocalDate.now() : line.startDate();
                    var sub = new AccountSubscription(sp, a.getId(), line.productId(), qty, start);
                    if (line.unitPrice() != null) sub.setUnitPrice(line.unitPrice());
                    if (line.endDate() != null) sub.setEndDate(line.endDate());
                    subscriptions.save(sub);
                }
            }
        }

        return ResponseEntity.ok(java.util.Map.of("id", a.getId(),
                "message", "Akaun " + a.getAccountNo() + " dikemas kini."));
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
        a.setBilltoEmailSecondary(r.billtoEmailSecondary());
        a.setDepositAmount(r.depositAmount());
        a.setOpeningAmount(r.openingAmount());
        a.setRemarks(r.remarks());
        a.setAccountType(r.accountType());
    }

    // ── Cari pengguna berdaftar (untuk confirmation sebelum link) ──
    // ── Penyata akaun (ARAS TRANSAKSI / ITEM) ──
    // Setiap baris invois (financial_document_line) = 1 baris debit.
    // Setiap knock resit (fi_allocation ACTIVE) = 1 baris kredit, rujuk invois dibayar.
    // Advance = lebihan resit (resit - SUM alokasi ACTIVE) = baris kredit asing.
    //   Nota: advance ni matematik = Cr gl 2273 (balanced-entry invariant,
    //   accounting-invariants.md) — tak perlu join ledger, angka sama.
    // Baki berjalan on-the-fly (SUM kumulatif) — TIDAK disimpan, elak drift (§9).
    // Descending untuk paparan; pagination di hujung.
    record StatementLine(String date, String docNo, String docType, String item,
                         String period, java.math.BigDecimal debit, java.math.BigDecimal credit,
                         java.math.BigDecimal balance) {}
    record StatementResponse(Long accountId, String accountNo, String accountName,
                             java.math.BigDecimal openingBalance,
                             java.math.BigDecimal closingBalance,
                             int total, int page, int size,
                             List<StatementLine> lines) {}

    @GetMapping("/{id}/statement")
    @SuppressWarnings("unchecked")
    StatementResponse statement(@PathVariable Long id,
                                @org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") int page,
                                @org.springframework.web.bind.annotation.RequestParam(defaultValue = "100") int size) {
        var acc = accounts.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Akaun tak wujud: " + id));

        // UNION 3 sumber -> tertib menaik (ts, kind, seq):
        //   kind 0 = baris invois, 1 = knock resit, 2 = advance (selepas knock)
        List<Object[]> rows = em.createNativeQuery("""
                SELECT ts, kind, seq, doc_no, doc_label, item, period, debit, credit FROM (
                  SELECT COALESCE(d.created_at, d.doc_date) AS ts, 0 AS kind, l.id AS seq,
                         d.doc_no AS doc_no, 'Invoice' AS doc_label,
                         l.description AS item,
                         COALESCE(fp.name_, DATE_FORMAT(l.period_start, '%b %Y')) AS period,
                         (l.amount + l.tax_amount) AS debit, 0 AS credit
                  FROM financial_document_line l
                  JOIN financial_document d ON d.id = l.document_id
                  LEFT JOIN fi_period fp ON fp.period_id = l.period_id
                  WHERE d.account_id = :id AND d.sp_code = :sp
                    AND d.doc_type = 'INVOICE' AND d.status <> 'CANCELLED' AND l.active = 1
                  UNION ALL
                  SELECT COALESCE(dn.created_at, dn.doc_date) AS ts, 0 AS kind, dn.id AS seq,
                         dn.doc_no AS doc_no, 'Debit Note' AS doc_label,
                         dn.title AS item, NULL AS period,
                         (dn.amount + dn.tax_amount) AS debit, 0 AS credit
                  FROM financial_document dn
                  WHERE dn.account_id = :id AND dn.sp_code = :sp
                    AND dn.doc_type = 'DEBIT_NOTE' AND dn.status <> 'CANCELLED'
                  UNION ALL
                  SELECT COALESCE(rc.created_at, rc.doc_date) AS ts, 1 AS kind, a.id AS seq,
                         rc.doc_no AS doc_no,
                         CASE WHEN rc.doc_type = 'CREDIT_NOTE' THEN 'Credit Note' ELSE 'Receipt' END AS doc_label,
                         CASE WHEN rc.doc_type = 'CREDIT_NOTE'
                              THEN CONCAT('Kredit Nota \u2192 ', inv.doc_no,
                                     CASE WHEN inv.doc_type IN ('DEBIT_NOTE','CREDIT_NOTE')
                                          THEN CONCAT(' (', TRIM(SUBSTRING_INDEX(inv.title, '\u2014', -1)), ')')
                                          ELSE '' END)
                              ELSE CONCAT('Bayaran \u2192 ', inv.doc_no,
                                     CASE WHEN inv.doc_type IN ('DEBIT_NOTE','CREDIT_NOTE')
                                          THEN CONCAT(' (', TRIM(SUBSTRING_INDEX(inv.title, '\u2014', -1)), ')')
                                          ELSE '' END) END AS item, NULL AS period,
                         0 AS debit, a.amount AS credit
                  FROM fi_allocation a
                  JOIN financial_document rc ON rc.id = a.credit_document_id
                  JOIN financial_document inv ON inv.id = a.debit_document_id
                  WHERE a.account_id = :id AND a.status = 'ACTIVE'
                    AND rc.sp_code = :sp AND rc.status <> 'CANCELLED'
                  UNION ALL
                  SELECT COALESCE(rc.created_at, rc.doc_date) AS ts, 2 AS kind, rc.id AS seq,
                         rc.doc_no AS doc_no, 'Receipt' AS doc_label,
                         'Bayaran pendahuluan (advance)' AS item, NULL AS period,
                         0 AS debit,
                         (rc.amount - COALESCE(
                            (SELECT SUM(a2.amount) FROM fi_allocation a2
                             WHERE a2.credit_document_id = rc.id AND a2.status = 'ACTIVE'), 0)) AS credit
                  FROM financial_document rc
                  WHERE rc.account_id = :id AND rc.sp_code = :sp
                    AND rc.doc_type = 'RECEIPT' AND rc.status <> 'CANCELLED'
                    AND (rc.amount - COALESCE(
                            (SELECT SUM(a2.amount) FROM fi_allocation a2
                             WHERE a2.credit_document_id = rc.id AND a2.status = 'ACTIVE'), 0)) > 0
                ) u
                ORDER BY ts, kind, seq
                """)
                .setParameter("id", id)
                .setParameter("sp", acc.getSpCode())
                .getResultList();

        // Baki berjalan (menaik) -> reverse ke descending -> paginate.
        List<StatementLine> asc = new ArrayList<>();
        java.math.BigDecimal balance = java.math.BigDecimal.ZERO;
        for (Object[] r : rows) {
            String ts     = r[0] == null ? null : r[0].toString();
            String docNo  = (String) r[3];
            String label  = (String) r[4];
            String item   = (String) r[5];
            String period = (String) r[6];
            java.math.BigDecimal debit  = toBig(r[7]);
            java.math.BigDecimal credit = toBig(r[8]);
            balance = balance.add(debit).subtract(credit);
            asc.add(new StatementLine(ts, docNo, label, item, period, debit, credit, balance));
        }
        java.math.BigDecimal closing = balance;

        java.util.Collections.reverse(asc);
        int total = asc.size();
        int from = Math.max(0, Math.min(page * size, total));
        int to   = Math.max(from, Math.min(from + size, total));
        List<StatementLine> pageLines = new ArrayList<>(asc.subList(from, to));

        return new StatementResponse(
                acc.getId(), acc.getAccountNo(), acc.getAccountName(),
                java.math.BigDecimal.ZERO, closing, total, page, size, pageLines);
    }

    /** Native numeric -> BigDecimal selamat (elak float drift). */
    private static java.math.BigDecimal toBig(Object o) {
        if (o == null) return java.math.BigDecimal.ZERO;
        if (o instanceof java.math.BigDecimal b) return b;
        return new java.math.BigDecimal(o.toString());
    }

    @GetMapping("/search-user")
    @Transactional(readOnly = true)
    ResponseEntity<?> searchUser(@RequestParam String email) {
        sp();  // pastikan tenant
        String e = email == null ? "" : email.trim().toLowerCase();
        if (e.isEmpty()) return ResponseEntity.badRequest().body(java.util.Map.of("message", "Email diperlukan."));
        List<Object[]> rows = em.createNativeQuery(
                "SELECT id, full_name FROM app_user WHERE LOWER(email) = :e AND status = 'ACTIVE' AND email_verified_at IS NOT NULL")
                .setParameter("e", e).getResultList();
        if (rows.isEmpty()) {
            return ResponseEntity.ok(java.util.Map.of("found", false));
        }
        Object[] r = rows.get(0);
        return ResponseEntity.ok(java.util.Map.of(
                "found", true,
                "userId", ((Number) r[0]).longValue(),
                "fullName", r[1] == null ? "" : r[1]));
    }

    // ── Link / Invite akaun kepada pengguna ──
    record LinkRequest(@jakarta.validation.constraints.Email @NotBlank String email) {}

    @PostMapping("/{id}/link")
    @Transactional
    ResponseEntity<?> link(@PathVariable Long id, @Valid @RequestBody LinkRequest r) {
        String sp = sp();
        Account a = accounts.findById(id).orElse(null);
        if (a == null || !sp.equals(a.getSpCode())) return ResponseEntity.notFound().build();
        String email = r.email().trim().toLowerCase();

        // Cari pengguna aktif (SELECT satu kolum -> senarai Number)
        List<?> rows = em.createNativeQuery(
                "SELECT id FROM app_user WHERE LOWER(email) = :e AND status = 'ACTIVE' AND email_verified_at IS NOT NULL")
                .setParameter("e", email).getResultList();

        if (!rows.isEmpty()) {
            // Berdaftar & aktif -> link terus
            Long userId = ((Number) rows.get(0)).longValue();
            a.setPayerUserId(userId);
            a.setLinkDate(java.time.LocalDateTime.now());
            accounts.save(a);
            return ResponseEntity.ok(java.util.Map.of("linked", true,
                    "message", "Akaun dipautkan kepada " + email + "."));
        } else {
            // Belum berdaftar -> jemputan PENDING + (email dihantar di lapisan email)
            var inv = new AccountInvitation(sp, id, email, sp);
            invitations.save(inv);
            sendInvite(sp, email);
            return ResponseEntity.ok(java.util.Map.of("linked", false, "invited", true,
                    "message", "Jemputan dihantar ke " + email + ". Akaun akan dipautkan selepas pendaftaran."));
        }
    }

    @DeleteMapping("/{id}/link")
    @Transactional
    ResponseEntity<?> unlink(@PathVariable Long id) {
        String sp = sp();
        Account a = accounts.findById(id).orElse(null);
        if (a == null || !sp.equals(a.getSpCode())) return ResponseEntity.notFound().build();
        a.setPayerUserId(null);
        a.setLinkDate(null);
        accounts.save(a);
        return ResponseEntity.ok(java.util.Map.of("message", "Pautan akaun dibatalkan."));
    }

    // ── Tambah subscription ke akaun sedia ada (More > Add Subscription) ──
    record AddSubLine(Long productId, java.math.BigDecimal quantity,
                      LocalDate startDate, LocalDate endDate,
                      java.math.BigDecimal unitPrice) {}
    record AddSubscriptionsRequest(List<AddSubLine> subscriptions) {}

    @PostMapping("/{id}/subscriptions")
    @Transactional
    ResponseEntity<?> addSubscriptions(@PathVariable Long id, @RequestBody AddSubscriptionsRequest r) {
        String sp = sp();
        Account a = accounts.findById(id).orElse(null);
        if (a == null || !sp.equals(a.getSpCode())) return ResponseEntity.notFound().build();
        if (r.subscriptions() == null || r.subscriptions().isEmpty()) {
            return ResponseEntity.badRequest().body(java.util.Map.of("message", "Tiada produk dipilih."));
        }
        int added = 0;
        for (AddSubLine line : r.subscriptions()) {
            if (line.productId() == null) continue;
            var qty = line.quantity() == null ? java.math.BigDecimal.ONE : line.quantity();
            // start_date NULL = biar engine guna logik default (start_charging NULL
            // = jana untuk mana-mana period dalam ufuk). JANGAN auto-isi now() —
            // itu mengehadkan caj kepada tarikh kemasukan data. Rujuk billing-rules §5.
            var sub = new AccountSubscription(sp, id, line.productId(), qty, line.startDate());
            if (line.unitPrice() != null) sub.setUnitPrice(line.unitPrice());
            if (line.endDate() != null) sub.setEndDate(line.endDate());
            subscriptions.save(sub);
            added++;
        }
        return ResponseEntity.ok(java.util.Map.of("added", added,
                "message", added + " langganan ditambah ke akaun " + a.getAccountNo() + "."));
    }

    private void sendInvite(String sp, String toEmail) {
        // Nama SP untuk email
        String spName = sp;
        try {
            Object n = em.createNativeQuery("SELECT name FROM service_provider WHERE sp_code = :sp")
                    .setParameter("sp", sp).getSingleResult();
            if (n != null) spName = n.toString();
        } catch (Exception ignore) {}
        String registerUrl = "https://monthley.my/register?email=" + toEmail;
        try {
            email.sendInvitation(toEmail, spName, registerUrl);
        } catch (Exception ignore) {
            // email gagal — jemputan tetap PENDING, boleh hantar semula
        }
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

    // ── Akaun Saya (portal pelanggan) — RENTAS SP ──
    // Pelanggan boleh ada akaun dalam banyak organisasi. Filter ikut
    // payer_user_id (dari JWT subject), BUKAN TenantContext — merentas semua SP.
    // Baki diterbitkan SUM (invois - alokasi ACTIVE), bukan cached_balance (§9).
    record MyAccountRow(Long id, String spCode, String spName,
                        String accountNo, String accountName, java.math.BigDecimal balance,
                        java.math.BigDecimal latestInvoiceAmount, java.time.LocalDate dueDate) {}

    @GetMapping("/my")
    @SuppressWarnings("unchecked")
    List<MyAccountRow> myAccounts() {
        Long uid = currentUserId();

        List<Object[]> rows = em.createNativeQuery("""
                SELECT a.id, a.sp_code, sp.name, a.account_no, a.account_name,
                       COALESCE((
                         SELECT SUM((d.amount + d.tax_amount) - COALESCE((
                                   SELECT SUM(al.amount) FROM fi_allocation al
                                   WHERE al.debit_document_id = d.id AND al.status = 'ACTIVE'), 0))
                         FROM financial_document d
                         WHERE d.account_id = a.id AND d.doc_type IN ('INVOICE','DEBIT_NOTE')
                           AND d.status <> 'CANCELLED'), 0) AS balance,
                       (SELECT (d2.amount + d2.tax_amount) FROM financial_document d2
                         WHERE d2.account_id = a.id AND d2.doc_type = 'INVOICE'
                           AND d2.status <> 'CANCELLED'
                         ORDER BY d2.doc_date DESC, d2.id DESC LIMIT 1) AS latest_amt,
                       (SELECT d3.due_date FROM financial_document d3
                         WHERE d3.account_id = a.id AND d3.doc_type = 'INVOICE'
                           AND d3.status <> 'CANCELLED'
                         ORDER BY d3.doc_date DESC, d3.id DESC LIMIT 1) AS due_dt
                FROM account a
                JOIN service_provider sp ON sp.sp_code = a.sp_code
                WHERE a.payer_user_id = :uid AND a.status = 'ACTIVE'
                ORDER BY sp.name, a.account_no
                """)
                .setParameter("uid", uid)
                .getResultList();

        List<MyAccountRow> out = new ArrayList<>();
        for (Object[] r : rows) {
            out.add(new MyAccountRow(
                    ((Number) r[0]).longValue(), (String) r[1], (String) r[2],
                    (String) r[3], (String) r[4], (java.math.BigDecimal) r[5],
                    (java.math.BigDecimal) r[6],
                    (java.time.LocalDate) r[7]));
        }
        return out;
    }

    // ── Sejarah resit/invois pelanggan (rentas akaun + SP) ──
    // Toggle type (RECEIPT/INVOICE), filter tarikh (from/to), carian (doc_no / SP).
    // Descending by doc_date. Pagination.
    record HistoryRow(java.time.LocalDate date, String docType, String spName,
                      String accountNo, String docNo, java.math.BigDecimal amount) {}

    @GetMapping("/my/history")
    @SuppressWarnings("unchecked")
    PageResponse<HistoryRow> myHistory(
            @RequestParam(defaultValue = "RECEIPT") String type,
            @RequestParam(required = false) java.time.LocalDate from,
            @RequestParam(required = false) java.time.LocalDate to,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        Long uid = currentUserId();
        String docType = "INVOICE".equalsIgnoreCase(type) ? "INVOICE" : "RECEIPT";
        String qq = (q == null || q.isBlank()) ? null : "%" + q.toLowerCase() + "%";

        String base = """
            FROM financial_document d
            JOIN account a ON a.id = d.account_id
            JOIN service_provider sp ON sp.sp_code = d.sp_code
            WHERE a.payer_user_id = :uid
              AND d.doc_type = :dt
              AND d.status <> 'CANCELLED'
              AND (:from IS NULL OR d.doc_date >= :from)
              AND (:to IS NULL OR d.doc_date <= :to)
              AND (:q IS NULL OR LOWER(d.doc_no) LIKE :q OR LOWER(sp.name) LIKE :q)
            """;

        var countQ = em.createNativeQuery("SELECT COUNT(*) " + base);
        countQ.setParameter("uid", uid);
        countQ.setParameter("dt", docType);
        countQ.setParameter("from", from);
        countQ.setParameter("to", to);
        countQ.setParameter("q", qq);
        long total = ((Number) countQ.getSingleResult()).longValue();

        String sql = "SELECT d.doc_date, d.doc_type, sp.name, a.account_no, d.doc_no, "
                + "(d.amount + d.tax_amount) AS amt "
                + base
                + " ORDER BY d.doc_date DESC, d.id DESC LIMIT :lim OFFSET :off";
        var dataQ = em.createNativeQuery(sql);
        dataQ.setParameter("uid", uid);
        dataQ.setParameter("dt", docType);
        dataQ.setParameter("from", from);
        dataQ.setParameter("to", to);
        dataQ.setParameter("q", qq);
        dataQ.setParameter("lim", size);
        dataQ.setParameter("off", page * size);

        List<Object[]> rows = dataQ.getResultList();
        List<HistoryRow> items = new ArrayList<>();
        for (Object[] r : rows) {
            items.add(new HistoryRow(
                    (java.time.LocalDate) r[0], (String) r[1], (String) r[2],
                    (String) r[3], (String) r[4], (java.math.BigDecimal) r[5]));
        }
        return new PageResponse<>(items, total, page, size);
    }

    /** User id dari JWT subject (JwtAuthFilter set principal = subject). */
    private Long currentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new IllegalStateException("Tiada pengguna dalam konteks.");
        }
        return Long.valueOf(auth.getName());
    }

}
