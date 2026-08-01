package com.monthley.account.internal;

import com.monthley.shared.ChargeFrequency;
import com.monthley.tenancy.api.BillingSettingsPort;
import com.monthley.notification.api.EmailPort;
import com.monthley.shared.PageResponse;
import com.monthley.shared.TenantContext;
import com.monthley.statement.api.StatementPort;
import com.monthley.statement.api.StatementRenderPort;
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
    private final StatementPort statements;
    private final StatementRenderPort statementRenderer;

    AccountController(AccountRepository accounts, AccountSubscriptionRepository subscriptions,
                      BillingSettingsPort settings, AccountInvitationRepository invitations,
                      EmailPort email,
                      StatementPort statements, StatementRenderPort statementRenderer) {
        this.statements = statements;
        this.statementRenderer = statementRenderer;
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
            @RequestParam(required = false) Long product,
            @RequestParam(required = false) Boolean linked,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        String status = active ? "ACTIVE" : "INACTIVE";
        String search = (q == null || q.isBlank()) ? null : "%" + q.trim().toLowerCase() + "%";

        String where = """
            WHERE a.sp_code = :sp
              AND a.status = :status
              -- ADHOC ialah akaun TEKNIKAL untuk memenuhi FK sub-ledger
              -- (V50), bukan pelanggan. Ia tidak sepatutnya muncul dalam
              -- senarai akaun mahupun kiraan.
              AND COALESCE(a.account_type,'') <> 'ADHOC'
              AND (:category IS NULL OR a.category_id = :category)
              -- Akaun yang TIE UP dengan produk: baris LANGGANAN wujud.
              --
              -- Bukan baris dokumen. Draf pertama menggunakan
              -- document_line_payment_status, iaitu akaun yang pernah
              -- DIBIL produk itu — dan produk yang baru ditambah kepada
              -- langganan tidak mempunyai invois lagi, jadi carian
              -- memulangkan sifar untuk akaun yang jelas melanggannya.
              --
              -- Tiada tapisan status. Langganan yang tamat tempoh masih
              -- baris dalam senarai langganan akaun itu; ia hanya hilang
              -- apabila kerani membuangnya. Yang dilihat kerani pada skrin
              -- akaun ialah yang menentukan sama ada akaun itu muncul.
              AND (:product IS NULL OR EXISTS (
                    SELECT 1 FROM account_subscription s
                    WHERE s.account_id = a.id AND s.product_id = :product))
              AND (:linkedFlag IS NULL
                   OR (:linkedFlag = 1 AND a.payer_user_id IS NOT NULL)
                   OR (:linkedFlag = 0 AND a.payer_user_id IS NULL))
              AND (:q IS NULL OR LOWER(a.account_no) LIKE :q OR LOWER(a.account_name) LIKE :q)
            """;

        // Jumlah rekod
        var countQ = em.createNativeQuery("SELECT COUNT(*) FROM account a " + where);
        bind(countQ, status, category, product, linked, search);
        long total = ((Number) countQ.getSingleResult()).longValue();

        // Data + baki diderive
        String sql = """
            SELECT a.id, a.account_no, a.account_name, a.payer_user_id, a.category_id,
                   a.charge_frequency, a.status,
                   COALESCE((SELECT ab.balance FROM account_balance ab
                             WHERE ab.account_id = a.id), 0) AS balance
            FROM account a
            """ + where + " ORDER BY a.account_no LIMIT :lim OFFSET :off";

        var dataQ = em.createNativeQuery(sql);
        bind(dataQ, status, category, product, linked, search);
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
            // Permintaan yang sama boleh membawa produk yang sama dua kali;
            // guard DB belum membantu kerana tiada baris lagi.
            var produkDilihat = new java.util.HashSet<Long>();
            for (SubLine line : r.subscriptions()) {
                if (line.productId() != null && !produkDilihat.add(line.productId())) {
                    throw new IllegalArgumentException(
                            "Produk yang sama disenaraikan lebih daripada sekali.");
                }
                if (line.productId() == null) continue;
                var qty = line.quantity() == null ? java.math.BigDecimal.ONE : line.quantity();
                var start = line.startDate();
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
                        sub.setStartDate(line.startDate());   // null = kosongkan (V27 nullable)
                        sub.setEndDate(line.endDate());
                        sub.setUnitPrice(line.unitPrice());
                    }
                    subscriptions.save(sub);
                } else if (!line.deleted() && line.productId() != null) {
                    // Satu akaun, satu produk, satu langganan HIDUP (CASE-007).
                    //
                    // Penapis 'produk belum dilanggan' wujud di frontend
                    // SAHAJA; backend menerima apa yang dihantar. Akaun 260
                    // berakhir dengan dua langganan produk 197 melalui laluan
                    // ini, dan invoisnya mengecaj Julai 2026 dua kali.
                    //
                    // Peraturan yang hidup hanya dalam UI bukan peraturan
                    // (cara-kerja guard 6).
                    if (subscriptions.existsByAccountIdAndProductIdAndStatus(
                            a.getId(), line.productId(), AccountSubscription.Status.ACTIVE)) {
                        throw new IllegalArgumentException(
                                "Produk ini sudah dilanggan oleh akaun " + a.getAccountNo()
                                + ". Tamatkan langganan sedia ada dahulu sebelum menambah "
                                + "yang baharu.");
                    }
                    var qty = line.quantity() == null ? java.math.BigDecimal.ONE : line.quantity();
                    var start = line.startDate();
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
    // SATU baris per DOKUMEN, daripada StatementService (ADR 0010).
    //
    // Versi terdahulu membina penyata sendiri: satu baris per baris-invois,
    // satu baris per alokasi, dan satu baris 'advance' yang DIKARANG daripada
    // (resit - SUM alokasi). Baris advance itu tidak wujud sebagai rekod; ia
    // jambatan antara susun-atur-ikut-alokasi dan baki-ikut-dokumen — corak
    // legacy yang CASE-004 bedah dan ADR 0010 tolak. Di bawah ADR 0009 jurang
    // itu tidak wujud, jadi jambatan tidak diperlukan.
    //
    // Skrin dan PDF kini dipetakan daripada StatementModel yang SAMA. Jika
    // dibiarkan berasingan, SP melihat satu bentuk di skrin dan bentuk lain
    // dalam fail yang dimuat turunnya.
    //
    // Alokasi turun menjadi 'matches' — sub-baris yang TIDAK menggerakkan
    // baki. Dokumen batal DIPAPAR dengan amaun sifar, tidak lagi ditapis
    // keluar: SP perlu melihat bahawa sesuatu telah dibatalkan.
    record StatementMatchDto(String docNo, String item, String period,
                             java.math.BigDecimal amount) {}
    record StatementLine(String date, String docNo, String docType, String item,
                         String remark, boolean cancelled,
                         // Diformat DI SINI, bukan di frontend — alasan sama
                         // seperti tempoh pada StatementMatchDto: peraturan
                         // format tidak boleh wujud di dua tempat (guard 6).
                         String cancelledAt, String cancelledBy,
                         java.math.BigDecimal originalAmount,
                         java.math.BigDecimal amount, java.math.BigDecimal balance,
                         List<StatementMatchDto> matches) {}
    record StatementResponse(Long accountId, String accountNo, String accountName,
                             Integer year,
                             java.math.BigDecimal openingBalance,
                             java.math.BigDecimal closingBalance,
                             java.math.BigDecimal arrears,
                             int total, int page, int size,
                             List<StatementLine> lines) {}

    @GetMapping("/{id}/statement")
    StatementResponse statement(@PathVariable Long id,
                                @RequestParam(required = false) Integer year,
                                @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "100") int size) {
        var acc = accounts.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Akaun tak wujud: " + id));

        // year null bermakna SEMUA REKOD. Ia kekal sebagai pilihan (kes JMB
        // yang memerlukan sejarah penuh) tetapi bukan lalai — lihat ADR 0010
        // keputusan 6.
        var model = (year == null)
                ? statements.forRange(acc.getSpCode(), id,
                        LocalDate.of(1900, 1, 1), LocalDate.of(2999, 12, 31))
                : statements.forYear(acc.getSpCode(), id, year);

        var fmt = statements.formatterFor(model);

        List<StatementLine> asc = new ArrayList<>();
        for (var r : model.rows()) {
            List<StatementMatchDto> m = new ArrayList<>();
            for (var x : r.matches()) {
                // Tempoh diformat DI SINI, bukan di frontend. Peraturannya
                // bukan remeh — bulan penuh dipendekkan kepada 'Julai 2026'
                // manakala sebahagian bulan menunjukkan '19-31 Julai 2026',
                // kerana satu akaun boleh mempunyai dua langganan produk
                // yang sama dengan start_date berbeza. Jika frontend
                // memformat sendiri, peraturan itu wujud di dua tempat dan
                // akan menyimpang (guard 6).
                m.add(new StatementMatchDto(
                        x.documentNo(), x.productName(),
                        fmt.period(x.periodStart(), x.periodEnd()),
                        x.amount()));
            }
            // Jejak audit pembatalan dibawa ke portal juga. PDF, XLSX dan
            // portal dibina daripada SATU StatementModel (ADR 0010);
            // menampal dua sahaja bermakna portal memaparkan 0.00 tanpa
            // nombor asal dan tanpa siapa membatalkannya.
            asc.add(new StatementLine(
                    r.docDate().toString(), r.docNo(), r.docType(),
                    r.description(), r.remark(), r.cancelled(),
                    r.cancelledAt() == null ? null : fmt.dateTime(r.cancelledAt()),
                    r.cancelledBy(), r.originalAmount(),
                    r.amount(), r.runningBalance(), m));
        }

        java.util.Collections.reverse(asc);   // terbaru di atas
        int total = asc.size();
        int from = Math.max(0, Math.min(page * size, total));
        int to   = Math.max(from, Math.min(from + size, total));

        return new StatementResponse(
                acc.getId(), acc.getAccountNo(), acc.getAccountName(), year,
                model.openingBalance(), model.closingBalance(), model.arrears(),
                total, page, size, new ArrayList<>(asc.subList(from, to)));
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
                      Long product, Boolean linked, String search) {
        query.setParameter("sp", sp());
        query.setParameter("status", status);
        query.setParameter("category", category);
        query.setParameter("product", product);
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
    //
    // BAKI daripada VIEW account_balance (ADR 0009) — satu takrifan dikongsi,
    // sama seperti baris 89 dan DashboardController. Formula terdahulu di sini
    // menjumlahkan invois tolak alokasi, yang BUTA kepada kredit yang belum
    // dipadankan: pada 26 Julai 2026 ia memberitahu M04 bahawa dia berhutang
    // RM700.59 sedangkan baki sebenar RM500.59 (RCP000005 mempunyai RM200
    // belum dialokasi), dan menunjukkan M06 sebagai RM0.00 sedangkan dia
    // mempunyai kredit RM38.41. Ia meminta wang yang bukan hak kita.
    //
    // TUNGGAKAN ialah nombor yang BERBEZA: invois belum berbayar. Ia tidak
    // boleh negatif; baki boleh. Portal memaparkan kedua-duanya, sama seperti
    // penyata (ADR 0010 keputusan 9). Kesilapan asalnya ialah memanggil
    // tunggakan sebagai 'balance'.
    record MyAccountRow(Long id, String spCode, String spName,
                        String accountNo, String accountName,
                        java.math.BigDecimal balance,
                        java.math.BigDecimal arrears,
                        java.math.BigDecimal latestInvoiceAmount,
                        java.time.LocalDate dueDate) {}

    @GetMapping("/my")
    @SuppressWarnings("unchecked")
    List<MyAccountRow> myAccounts() {
        Long uid = currentUserId();

        List<Object[]> rows = em.createNativeQuery("""
                SELECT a.id, a.sp_code, sp.name, a.account_no, a.account_name,
                       COALESCE(ab.balance, 0) AS balance,
                       COALESCE((
                         SELECT SUM((d.amount + d.tax_amount) - COALESCE((
                                   SELECT SUM(al.amount) FROM fi_allocation al
                                   WHERE al.debit_document_id = d.id AND al.status = 'ACTIVE'), 0))
                         FROM financial_document d
                         WHERE d.account_id = a.id AND d.doc_type IN ('INVOICE','DEBIT_NOTE')
                           AND d.status <> 'CANCELLED'), 0) AS arrears,
                       -- HUTANG TEKNIKAL (ADR 0010 P4): invois TERBARU, bukan
                       -- yang tertunggak terawal. Ini formula MAX(doc_id)
                       -- legacy: jika Januari belum dibayar, tarikh yang
                       -- bermakna ialah tarikh Januari, bukan tarikh Julai.
                       -- Pembetulan memerlukan VIEW tunggakan.
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
                LEFT JOIN account_balance ab ON ab.account_id = a.id
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
                    (java.math.BigDecimal) r[7],
                    (java.time.LocalDate) r[8]));
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

    // ── Penyata akaun (portal pelanggan) ──
    //
    // Perkhidmatan yang SAMA seperti skrin SP (ADR 0010 keputusan 1), tetapi
    // sempadan kebenarannya BERBEZA: pembayar boleh melihat akaun yang
    // dibayarnya MERENTAS SP, jadi pemilikan disemak melalui payer_user_id
    // dan bukan TenantContext. Semakan itu wujud di sini kerana peraturan
    // pemilikan pembayar sudah tinggal di sini; StatementService tidak tahu
    // siapa pemanggilnya dan tidak boleh menguatkuasakan apa-apa.
    @GetMapping(value = "/my/{accountId}/statement",
                produces = org.springframework.http.MediaType.APPLICATION_PDF_VALUE)
    ResponseEntity<byte[]> myStatement(
            @PathVariable Long accountId,
            @RequestParam(required = false) Integer year) {

        Long uid = currentUserId();
        List<?> owned = em.createNativeQuery(
                "SELECT a.sp_code FROM account a "
                + "WHERE a.id = :id AND a.payer_user_id = :uid AND a.status = 'ACTIVE'")
                .setParameter("id", accountId)
                .setParameter("uid", uid)
                .getResultList();
        if (owned.isEmpty()) {
            return ResponseEntity.notFound().build();   // jangan bocorkan kewujudan
        }
        String spCode = (String) owned.get(0);

        var model = statements.forYear(spCode, accountId,
                year != null ? year : java.time.Year.now().getValue());
        var f = statementRenderer.renderPdfFile(model);

        return ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.APPLICATION_PDF)
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        org.springframework.http.ContentDisposition.attachment()
                                .filename(f.filename(), java.nio.charset.StandardCharsets.UTF_8)
                                .build().toString())
                .body(f.content());
    }

    /**
     * Resit PDF untuk pelanggan.
     *
     * Pemilikan disemak melalui RESIT, bukan akaun: pembayar boleh melihat
     * resit bagi akaun yang dibayarnya. Satu query menyemak kedua-duanya
     * sekali gus.
     *
     * 404 dan bukan 403 — 403 membenarkan penyerang membilang ID resit.
     */
    @GetMapping(value = "/my/receipts/{receiptId}",
                produces = org.springframework.http.MediaType.APPLICATION_PDF_VALUE)
    ResponseEntity<byte[]> myReceipt(@PathVariable Long receiptId) {
        Long uid = currentUserId();
        List<?> owned = em.createNativeQuery(
                "SELECT d.sp_code FROM financial_document d "
                + "JOIN account a ON a.id = d.account_id "
                + "WHERE d.id = :id AND d.doc_type = 'RECEIPT' "
                + "  AND a.payer_user_id = :uid AND a.status = 'ACTIVE'")
                .setParameter("id", receiptId)
                .setParameter("uid", uid)
                .getResultList();
        if (owned.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        String spCode = (String) owned.get(0);

        var m = statements.receipt(spCode, receiptId);
        var f = statementRenderer.renderReceiptPdfFile(m);

        return ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.APPLICATION_PDF)
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        org.springframework.http.ContentDisposition.attachment()
                                .filename(f.filename(), java.nio.charset.StandardCharsets.UTF_8)
                                .build().toString())
                .body(f.content());
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
