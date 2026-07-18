package com.monthley.tenancy.internal;

import com.monthley.shared.Access;
import com.monthley.shared.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
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
import java.util.Map;

/**
 * Tetapan SP — semua tab dalam menu Tetapan.
 *
 * Setiap SP buat setup sekali semasa onboarding; jana invois, resit,
 * penyata & denda lewat semua bergantung pada tetapan ini.
 *
 * PERANAN: SP_ADMIN sahaja (Cashier tak boleh ubah tetapan).
 */
@RestController
@RequestMapping("/api/v1/settings")
class SettingsController {

    @PersistenceContext
    private EntityManager em;

    record MessageResponse(String message) {}

    // =====================================================================
    // PROFILE
    // =====================================================================

    /** Medan tepat dari design: Tetapan ▸ Profile */
    record ProfileDto(
            String spCode, String logoUrl, String businessType, String businessDesc,
            String name, String registrationNo,
            String address, String postcode, String state, String country,
            String website, String officeNo, String mobileNo,
            String helpdeskEmail, String helpdeskPhone, String contactEmail,
            String bankCode, String bankAccountNo, String bankAccountName,
            String status, LocalDate orgRegisteredDate) {}

    @GetMapping("/profile")
    ProfileDto profile() {
        Object[] r = (Object[]) em.createNativeQuery("""
                SELECT sp_code, logo_url, business_type, business_desc, name, registration_no,
                       addr_line1, postcode, state, country, website,
                       office_no, mobile_no, helpdesk_email, helpdesk_phone, contact_email,
                       bank_code, bank_account_no, bank_account_name,
                       status, org_registered_date
                FROM service_provider WHERE sp_code = :sp
                """).setParameter("sp", sp()).getSingleResult();

        return new ProfileDto(
                (String) r[0], (String) r[1], (String) r[2], (String) r[3], (String) r[4],
                (String) r[5], (String) r[6], (String) r[7], (String) r[8], (String) r[9],
                (String) r[10], (String) r[11], (String) r[12], (String) r[13],
                (String) r[14], (String) r[15], (String) r[16], (String) r[17],
                (String) r[18], (String) r[19], toLocalDate(r[20]));
    }

    @PutMapping("/profile")
    @Transactional
    ResponseEntity<?> saveProfile(@Valid @RequestBody ProfileDto p) {
        Access.requireRole("SP_ADMIN", "mengubah profil organisasi");

        em.createNativeQuery("""
                UPDATE service_provider SET
                  logo_url = :logo, business_type = :bizType, business_desc = :desc,
                  name = :name, registration_no = :reg,
                  addr_line1 = :addr, postcode = :post, state = :state, country = :country,
                  website = :web, office_no = :office, mobile_no = :mobile,
                  helpdesk_email = :hEmail, helpdesk_phone = :hPhone, contact_email = :email,
                  bank_code = :bank, bank_account_no = :accNo, bank_account_name = :accName,
                  updated_at = NOW()
                WHERE sp_code = :sp
                """)
                .setParameter("logo", p.logoUrl())
                .setParameter("bizType", blankToNull(p.businessType()))
                .setParameter("desc", p.businessDesc())
                .setParameter("name", p.name()).setParameter("reg", p.registrationNo())
                .setParameter("addr", p.address()).setParameter("post", p.postcode())
                .setParameter("state", p.state()).setParameter("country", p.country())
                .setParameter("web", p.website()).setParameter("office", p.officeNo())
                .setParameter("mobile", p.mobileNo())
                .setParameter("hEmail", p.helpdeskEmail()).setParameter("hPhone", p.helpdeskPhone())
                .setParameter("email", p.contactEmail())
                .setParameter("bank", p.bankCode())
                .setParameter("accNo", p.bankAccountNo()).setParameter("accName", p.bankAccountName())
                .setParameter("sp", sp())
                .executeUpdate();

        return ok("Profil organisasi dikemas kini.");
    }

    record BusinessTypeDto(String code, String name, String description) {}

    /** Jenis perniagaan — dibuka kepada admin SP (versi platform superadmin sahaja). */
    @GetMapping("/business-types")
    @SuppressWarnings("unchecked")
    List<BusinessTypeDto> businessTypes() {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT code, name, description FROM ref_business_type
                WHERE status = 'ACTIVE' ORDER BY sort_order, name
                """).getResultList();
        List<BusinessTypeDto> out = new ArrayList<>();
        for (Object[] r : rows) {
            out.add(new BusinessTypeDto((String) r[0], (String) r[1], (String) r[2]));
        }
        return out;
    }

    // =====================================================================
    // BILLING — Sales Tax + Localization
    // =====================================================================

    /** Rekod cukai + e-Invois (LHDN) + penyetempatan. */
    record BillingDto(
            // Localization
            String currency, String language, String dateFormat, String timeFormat,
            // Payment term — dipapar dalam tab Invoice
            Integer paymentTermDays,
            // Rekod Cukai
            String taxName, BigDecimal taxRate, String taxId,
            // Tax Invoice (e-Invois) — berkuatkuasa Jan 2027
            Boolean enableTaxInvoice, String tin, String sstRegistrationNo,
            LocalDate taxEffectiveDate, String msicCode,
            String einvoiceType, String einvoiceClassification) {}

    @GetMapping("/billing")
    BillingDto billing() {
        ensureBillingRow();
        Object[] r = (Object[]) em.createNativeQuery("""
                SELECT currency, language, date_format, time_format, payment_term_days,
                       tax_name, tax_rate, tax_id,
                       enable_tax_invoice, tin, sst_registration_no, tax_effective_date,
                       msic_code, einvoice_type, einvoice_classification
                FROM sp_billing_setting WHERE sp_code = :sp
                """).setParameter("sp", sp()).getSingleResult();

        return new BillingDto(
                (String) r[0], (String) r[1], (String) r[2], (String) r[3],
                r[4] == null ? null : ((Number) r[4]).intValue(),
                (String) r[5], (BigDecimal) r[6], (String) r[7],
                toBool(r[8]), (String) r[9], (String) r[10], toLocalDate(r[11]),
                (String) r[12], (String) r[13], (String) r[14]);
    }

    @PutMapping("/billing")
    @Transactional
    ResponseEntity<?> saveBilling(@RequestBody BillingDto b) {
        Access.requireRole("SP_ADMIN", "mengubah tetapan billing");
        ensureBillingRow();

        em.createNativeQuery("""
                UPDATE sp_billing_setting SET
                  currency = :cur, language = :lang, date_format = :df, time_format = :tf,
                  payment_term_days = :term,
                  tax_name = :tn, tax_rate = :tr, tax_id = :ti,
                  enable_tax_invoice = :eti, tin = :tin, sst_registration_no = :sst,
                  tax_effective_date = :eff, msic_code = :msic,
                  einvoice_type = :etype, einvoice_classification = :eclass
                WHERE sp_code = :sp
                """)
                .setParameter("cur", b.currency() == null ? "MYR" : b.currency())
                .setParameter("lang", b.language() == null ? "ms" : b.language())
                .setParameter("df", b.dateFormat()).setParameter("tf", b.timeFormat())
                .setParameter("term", b.paymentTermDays() == null ? 15 : b.paymentTermDays())
                .setParameter("tn", b.taxName()).setParameter("tr", b.taxRate())
                .setParameter("ti", b.taxId())
                .setParameter("eti", bit(b.enableTaxInvoice()))
                .setParameter("tin", b.tin()).setParameter("sst", b.sstRegistrationNo())
                .setParameter("eff", b.taxEffectiveDate()).setParameter("msic", b.msicCode())
                .setParameter("etype", b.einvoiceType() == null ? "INVOICE" : b.einvoiceType())
                .setParameter("eclass", b.einvoiceClassification() == null ? "GENERAL" : b.einvoiceClassification())
                .setParameter("sp", sp())
                .executeUpdate();

        return ok("Tetapan billing dikemas kini.");
    }

    // =====================================================================
    // DOCUMENT — Invoice + Receipt + Statement
    // =====================================================================

    /** Invoice + Receipt + Statement + notifikasi — medan tepat dari design. */
    record DocumentDto(
            // Invoice Setting
            String invoiceTitle, String invoicePrefix,
            Integer invoiceNoSize, Long invoiceNoStart,
            String invoiceGenMode, String invoiceGenFreq, Integer invoiceGenDay,
            String invoiceTemplateId, Boolean splitInvoiceByProduct,
            // Notifikasi — dipapar dalam tab Invoice
            Boolean smsOnInvoice, Boolean smsOnReminder,
            Boolean whatsappOnInvoice, Boolean whatsappOnReminder,
            // Receipt Setting
            String receiptTitle, String receiptPrefix,
            Integer receiptNoSize, Long receiptNoStart, String receiptTemplateId,
            Boolean enableManualPayment,
            // Statement
            String statementTitle, String statementTemplateId) {}

    @GetMapping("/document")
    DocumentDto document() {
        ensureDocumentRow();
        ensureNotificationRow();
        Object[] r = (Object[]) em.createNativeQuery("""
                SELECT d.invoice_title, d.invoice_prefix, d.invoice_no_size, d.invoice_no_start,
                       d.invoice_gen_mode, d.invoice_gen_freq, d.invoice_gen_day,
                       d.invoice_template_id, d.split_invoice_by_product,
                       n.sms_on_invoice, n.sms_on_reminder,
                       n.whatsapp_on_invoice, n.whatsapp_on_reminder,
                       d.receipt_title, d.receipt_prefix, d.receipt_no_size, d.receipt_no_start,
                       d.receipt_template_id, d.enable_manual_payment,
                       d.statement_title, d.statement_template_id
                FROM sp_document_setting d
                JOIN sp_notification_setting n ON n.sp_code = d.sp_code
                WHERE d.sp_code = :sp
                """).setParameter("sp", sp()).getSingleResult();

        return new DocumentDto(
                (String) r[0], (String) r[1],
                r[2] == null ? null : ((Number) r[2]).intValue(),
                r[3] == null ? null : ((Number) r[3]).longValue(),
                (String) r[4], (String) r[5],
                r[6] == null ? null : ((Number) r[6]).intValue(),
                (String) r[7], toBool(r[8]),
                toBool(r[9]), toBool(r[10]), toBool(r[11]), toBool(r[12]),
                (String) r[13], (String) r[14],
                r[15] == null ? null : ((Number) r[15]).intValue(),
                r[16] == null ? null : ((Number) r[16]).longValue(),
                (String) r[17], toBool(r[18]),
                (String) r[19], (String) r[20]);
    }

    @PutMapping("/document")
    @Transactional
    ResponseEntity<?> saveDocument(@RequestBody DocumentDto d) {
        Access.requireRole("SP_ADMIN", "mengubah tetapan dokumen");
        ensureDocumentRow();

        ensureNotificationRow();

        em.createNativeQuery("""
                UPDATE sp_document_setting SET
                  invoice_title = :it, invoice_prefix = :ip,
                  invoice_no_size = :inz, invoice_no_start = :ins,
                  invoice_gen_mode = :igm, invoice_gen_freq = :igf, invoice_gen_day = :igd,
                  invoice_template_id = :itp, split_invoice_by_product = :split,
                  receipt_title = :rt, receipt_prefix = :rp,
                  receipt_no_size = :rnz, receipt_no_start = :rns,
                  receipt_template_id = :rtp, enable_manual_payment = :emp,
                  statement_title = :st, statement_template_id = :stp
                WHERE sp_code = :sp
                """)
                .setParameter("it", d.invoiceTitle()).setParameter("ip", d.invoicePrefix())
                .setParameter("inz", d.invoiceNoSize() == null ? 6 : d.invoiceNoSize())
                .setParameter("ins", d.invoiceNoStart() == null ? 1L : d.invoiceNoStart())
                .setParameter("igm", d.invoiceGenMode() == null ? "CURRENT" : d.invoiceGenMode())
                .setParameter("igf", d.invoiceGenFreq() == null ? "MONTHLY" : d.invoiceGenFreq())
                .setParameter("igd", d.invoiceGenDay() == null ? 1 : d.invoiceGenDay())
                .setParameter("itp", d.invoiceTemplateId())
                .setParameter("split", bit(d.splitInvoiceByProduct()))
                .setParameter("rt", d.receiptTitle()).setParameter("rp", d.receiptPrefix())
                .setParameter("rnz", d.receiptNoSize() == null ? 6 : d.receiptNoSize())
                .setParameter("rns", d.receiptNoStart() == null ? 1L : d.receiptNoStart())
                .setParameter("rtp", d.receiptTemplateId())
                .setParameter("emp", bit(d.enableManualPayment()))
                .setParameter("st", d.statementTitle()).setParameter("stp", d.statementTemplateId())
                .setParameter("sp", sp())
                .executeUpdate();

        // Notifikasi disimpan bersama — dipapar dalam tab Invoice
        em.createNativeQuery("""
                UPDATE sp_notification_setting SET
                  sms_on_invoice = :si, sms_on_reminder = :sr,
                  whatsapp_on_invoice = :wi, whatsapp_on_reminder = :wr
                WHERE sp_code = :sp
                """)
                .setParameter("si", bit(d.smsOnInvoice())).setParameter("sr", bit(d.smsOnReminder()))
                .setParameter("wi", bit(d.whatsappOnInvoice())).setParameter("wr", bit(d.whatsappOnReminder()))
                .setParameter("sp", sp())
                .executeUpdate();

        return ok("Tetapan dokumen dikemas kini.");
    }

    // =====================================================================
    // LATE PENALTY
    // =====================================================================

    record PenaltyDto(
            Boolean enabled, String penaltyCode, String penaltyTitle, String penaltyDesc,
            BigDecimal penaltyAmount, Integer penaltyAfterDay, Boolean taxable,
            String penaltyType, Boolean compounded) {}

    @GetMapping("/penalty")
    PenaltyDto penalty() {
        ensurePenaltyRow();
        Object[] r = (Object[]) em.createNativeQuery("""
                SELECT enabled, penalty_code, penalty_title, penalty_desc,
                       penalty_amount, penalty_after_day, taxable, penalty_type, compounded
                FROM sp_penalty_setting WHERE sp_code = :sp
                """).setParameter("sp", sp()).getSingleResult();

        return new PenaltyDto(
                toBool(r[0]), (String) r[1], (String) r[2], (String) r[3],
                (BigDecimal) r[4], r[5] == null ? null : ((Number) r[5]).intValue(),
                toBool(r[6]), (String) r[7], toBool(r[8]));
    }

    @PutMapping("/penalty")
    @Transactional
    ResponseEntity<?> savePenalty(@RequestBody PenaltyDto p) {
        Access.requireRole("SP_ADMIN", "mengubah tetapan denda lewat");
        ensurePenaltyRow();

        em.createNativeQuery("""
                UPDATE sp_penalty_setting SET
                  enabled = :en, penalty_code = :code, penalty_title = :title,
                  penalty_desc = :desc, penalty_amount = :amt, penalty_after_day = :day,
                  taxable = :tax, penalty_type = :type, compounded = :comp
                WHERE sp_code = :sp
                """)
                .setParameter("en", bit(p.enabled())).setParameter("code", p.penaltyCode())
                .setParameter("title", p.penaltyTitle()).setParameter("desc", p.penaltyDesc())
                .setParameter("amt", p.penaltyAmount()).setParameter("day", p.penaltyAfterDay())
                .setParameter("tax", bit(p.taxable()))
                .setParameter("type", p.penaltyType() == null ? "FIXED" : p.penaltyType())
                .setParameter("comp", bit(p.compounded()))
                .setParameter("sp", sp())
                .executeUpdate();

        return ok("Tetapan denda lewat dikemas kini.");
    }

    // =====================================================================
    // MANAGE PLAN — baca sahaja (superadmin yang tetapkan)
    // =====================================================================

    record PlanDto(String planName, Integer accountLimit, long accountUsed,
                   String billingPlan, BigDecimal price, Integer estInvoicesMonth) {}

    @GetMapping("/plan")
    PlanDto plan() {
        Object[] r = (Object[]) em.createNativeQuery("""
                SELECT pl.name, pl.account_limit,
                       COALESCE((SELECT COUNT(*) FROM account a
                                 WHERE a.sp_code = sp.sp_code AND a.status = 'ACTIVE'), 0),
                       sp.billing_plan,
                       CASE WHEN sp.billing_plan = 'YEARLY' THEN pl.price_yearly ELSE pl.price_monthly END,
                       sp.est_invoices_month
                FROM service_provider sp
                LEFT JOIN service_plan pl ON pl.id = sp.service_plan_id
                WHERE sp.sp_code = :sp
                """).setParameter("sp", sp()).getSingleResult();

        return new PlanDto(
                (String) r[0], r[1] == null ? null : ((Number) r[1]).intValue(),
                ((Number) r[2]).longValue(), (String) r[3], (BigDecimal) r[4],
                r[5] == null ? null : ((Number) r[5]).intValue());
    }

    // =====================================================================
    // SENARAI RINGKAS — kategori akaun / produk / cawangan
    // =====================================================================

    record LookupDto(Long id, String code, String name) {}
    record BranchDto(Long id, String code, String name, String address) {}
    record SaveLookupRequest(@NotBlank String code, @NotBlank String name) {}
    record SaveBranchRequest(@NotBlank String code, @NotBlank String name, String address) {}

    @GetMapping("/account-categories")
    List<LookupDto> accountCategories() { return lookupList("account_category"); }

    @PostMapping("/account-categories")
    @Transactional
    ResponseEntity<?> addAccountCategory(@Valid @RequestBody SaveLookupRequest r) {
        return lookupAdd("account_category", r, "Kategori akaun");
    }

    @DeleteMapping("/account-categories/{id}")
    @Transactional
    ResponseEntity<?> delAccountCategory(@PathVariable Long id) {
        return lookupDelete("account_category", id, "account", "category_id", "Kategori akaun");
    }

    @GetMapping("/product-categories")
    List<LookupDto> productCategories() { return lookupList("product_category"); }

    @PostMapping("/product-categories")
    @Transactional
    ResponseEntity<?> addProductCategory(@Valid @RequestBody SaveLookupRequest r) {
        return lookupAdd("product_category", r, "Kategori produk");
    }

    @DeleteMapping("/product-categories/{id}")
    @Transactional
    ResponseEntity<?> delProductCategory(@PathVariable Long id) {
        return lookupDelete("product_category", id, "product", "category_id", "Kategori produk");
    }

    @GetMapping("/branches")
    @SuppressWarnings("unchecked")
    List<BranchDto> branches() {
        List<Object[]> rows = em.createNativeQuery(
                        "SELECT id, code, name, address FROM account_branch "
                        + "WHERE sp_code = :sp ORDER BY name")
                .setParameter("sp", sp()).getResultList();
        List<BranchDto> out = new ArrayList<>();
        for (Object[] r : rows) {
            out.add(new BranchDto(((Number) r[0]).longValue(),
                    (String) r[1], (String) r[2], (String) r[3]));
        }
        return out;
    }

    @PostMapping("/branches")
    @Transactional
    ResponseEntity<?> addBranch(@Valid @RequestBody SaveBranchRequest r) {
        Access.requireRole("SP_ADMIN", "menambah cawangan");
        try {
            em.createNativeQuery(
                            "INSERT INTO account_branch (sp_code, code, name, address, version) "
                            + "VALUES (:sp, :c, :n, :a, 0)")
                    .setParameter("sp", sp()).setParameter("c", r.code().trim())
                    .setParameter("n", r.name().trim()).setParameter("a", r.address())
                    .executeUpdate();
        } catch (RuntimeException e) {
            return bad("Kod '" + r.code() + "' sudah wujud.");
        }
        return ok("Cawangan ditambah.");
    }

    @DeleteMapping("/branches/{id}")
    @Transactional
    ResponseEntity<?> delBranch(@PathVariable Long id) {
        return lookupDelete("account_branch", id, "account", "branch_id", "Cawangan");
    }

    // =====================================================================
    // INVOICE PERIOD EXCLUDE
    // =====================================================================

    record ExcludeDto(Long id, String period, String remarks) {}
    record SaveExcludeRequest(@NotBlank String period, String remarks) {}

    @GetMapping("/exclude-periods")
    @SuppressWarnings("unchecked")
    List<ExcludeDto> excludePeriods() {
        List<Object[]> rows = em.createNativeQuery(
                        "SELECT id, period, remarks FROM invoice_exclude_period "
                        + "WHERE sp_code = :sp ORDER BY period DESC")
                .setParameter("sp", sp()).getResultList();
        List<ExcludeDto> out = new ArrayList<>();
        for (Object[] r : rows) {
            out.add(new ExcludeDto(((Number) r[0]).longValue(), (String) r[1], (String) r[2]));
        }
        return out;
    }

    @PostMapping("/exclude-periods")
    @Transactional
    ResponseEntity<?> addExclude(@Valid @RequestBody SaveExcludeRequest r) {
        Access.requireRole("SP_ADMIN", "menambah pengecualian tempoh");
        try {
            em.createNativeQuery(
                            "INSERT INTO invoice_exclude_period (sp_code, period, remarks) "
                            + "VALUES (:sp, :p, :rm)")
                    .setParameter("sp", sp()).setParameter("p", r.period())
                    .setParameter("rm", r.remarks()).executeUpdate();
        } catch (RuntimeException e) {
            return bad("Tempoh " + r.period() + " sudah dikecualikan.");
        }
        return ok("Tempoh " + r.period() + " dikecualikan dari penjanaan invois.");
    }

    @DeleteMapping("/exclude-periods/{id}")
    @Transactional
    ResponseEntity<?> delExclude(@PathVariable Long id) {
        Access.requireRole("SP_ADMIN", "membuang pengecualian tempoh");
        em.createNativeQuery("DELETE FROM invoice_exclude_period WHERE id = :id AND sp_code = :sp")
                .setParameter("id", id).setParameter("sp", sp()).executeUpdate();
        return ok("Pengecualian dibuang.");
    }

    // =====================================================================
    // helper
    // =====================================================================

    @SuppressWarnings("unchecked")
    private List<LookupDto> lookupList(String table) {
        List<Object[]> rows = em.createNativeQuery(
                        "SELECT id, code, name FROM " + table + " WHERE sp_code = :sp ORDER BY name")
                .setParameter("sp", sp()).getResultList();
        List<LookupDto> out = new ArrayList<>();
        for (Object[] r : rows) {
            out.add(new LookupDto(((Number) r[0]).longValue(), (String) r[1], (String) r[2]));
        }
        return out;
    }

    private ResponseEntity<?> lookupAdd(String table, SaveLookupRequest r, String label) {
        Access.requireRole("SP_ADMIN", "menambah " + label.toLowerCase());
        try {
            em.createNativeQuery("INSERT INTO " + table + " (sp_code, code, name, version) "
                                 + "VALUES (:sp, :c, :n, 0)")
                    .setParameter("sp", sp()).setParameter("c", r.code().trim())
                    .setParameter("n", r.name().trim()).executeUpdate();
        } catch (RuntimeException e) {
            return bad("Kod '" + r.code() + "' sudah wujud.");
        }
        return ok(label + " ditambah.");
    }

    /** Jangan padam kalau masih diguna — elak rekod yatim. */
    private ResponseEntity<?> lookupDelete(String table, Long id, String usedBy,
                                           String fkColumn, String label) {
        Access.requireRole("SP_ADMIN", "membuang " + label.toLowerCase());

        Number used = (Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM " + usedBy + " WHERE " + fkColumn + " = :id")
                .setParameter("id", id).getSingleResult();
        if (used.intValue() > 0) {
            return bad(label + " ini masih diguna oleh " + used + " rekod. "
                       + "Alihkan rekod tersebut dahulu.");
        }

        em.createNativeQuery("DELETE FROM " + table + " WHERE id = :id AND sp_code = :sp")
                .setParameter("id", id).setParameter("sp", sp()).executeUpdate();
        return ok(label + " dibuang.");
    }

    /** Cipta baris tetapan jika belum ada — SP baharu tiada baris lagi. */
    private void ensureRow(String table) {
        try {
            em.createNativeQuery("SELECT sp_code FROM " + table + " WHERE sp_code = :sp")
                    .setParameter("sp", sp()).getSingleResult();
        } catch (NoResultException e) {
            em.createNativeQuery("INSERT IGNORE INTO " + table + " (sp_code) VALUES (:sp)")
                    .setParameter("sp", sp()).executeUpdate();
            em.flush();
        }
    }

    @Transactional private void ensureBillingRow()      { ensureRow("sp_billing_setting"); }
    @Transactional private void ensureDocumentRow()     { ensureRow("sp_document_setting"); }
    @Transactional private void ensurePenaltyRow()      { ensureRow("sp_penalty_setting"); }
    @Transactional private void ensureNotificationRow() { ensureRow("sp_notification_setting"); }

    private ResponseEntity<?> ok(String msg)  { return ResponseEntity.ok(new MessageResponse(msg)); }
    private ResponseEntity<?> bad(String msg) { return ResponseEntity.badRequest().body(new MessageResponse(msg)); }

    private String sp() {
        String sp = TenantContext.get();
        if (sp == null || sp.isBlank()) {
            throw new IllegalStateException("Header X-SP-Id diperlukan");
        }
        return sp;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static Boolean toBool(Object o) {
        if (o == null) return null;
        if (o instanceof Boolean b) return b;
        if (o instanceof Number n) return n.intValue() != 0;
        return Boolean.parseBoolean(o.toString());
    }

    private static int bit(Boolean b) { return Boolean.TRUE.equals(b) ? 1 : 0; }

    private static LocalDate toLocalDate(Object o) {
        if (o == null) return null;
        if (o instanceof LocalDate d) return d;
        if (o instanceof java.time.LocalDateTime dt) return dt.toLocalDate();
        if (o instanceof java.sql.Timestamp ts) return ts.toLocalDateTime().toLocalDate();
        if (o instanceof java.sql.Date d) return d.toLocalDate();
        return null;
    }
}
