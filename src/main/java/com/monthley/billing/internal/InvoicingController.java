package com.monthley.billing.internal;

import com.monthley.ledger.api.GlAccounts;
import com.monthley.ledger.api.LedgerPort;
import com.monthley.shared.Access;
import com.monthley.tenancy.api.BillingSettingsPort;
import com.monthley.tenancy.api.BillingSettingsPort.BillingSettings;
import com.monthley.shared.TenantContext;
import com.monthley.shared.GenMode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Set;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import org.springframework.web.bind.annotation.*;

import com.monthley.document.api.DocumentAccessPort;
import com.monthley.document.api.DocumentType;
import com.monthley.notification.api.EmailOutboxPort;
import com.monthley.notification.api.EmailPort;
import com.monthley.statement.api.StatementPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import java.math.BigDecimal;
import java.time.YearMonth;

/**
 * REST untuk skrin "Penjanaan Bil (Invois)" — rujuk handoff §5 (tools/generate-invoices).
 *   POST /api/v1/tools/generate-invoices
 *
 * Ini titik di mana billing engine dipanggil oleh manusia.
 */
@RestController
@RequestMapping("/api/v1/tools")
class InvoicingController {

    private final InvoiceGenerationService billing;
    private final BillingSettingsPort settings;
    private final LedgerPort ledger;
    private final AdhocInvoiceService adhoc;
    private final StatementPort statements;
    private final DocumentAccessPort access;
    private final EmailPort email;
    private final EmailOutboxPort outbox;
    private final String appUrl;

    private static final Logger log = LoggerFactory.getLogger(InvoicingController.class);

    @PersistenceContext private EntityManager em;

    InvoicingController(InvoiceGenerationService billing,
                        BillingSettingsPort settings,
                        LedgerPort ledger,
                        AdhocInvoiceService adhoc,
                        StatementPort statements,
                        DocumentAccessPort access,
                        EmailPort email,
                        EmailOutboxPort outbox,
                        @Value("${monthley.app-url:http://localhost:4200}") String appUrl) {
        this.billing = billing;
        this.settings = settings;
        this.ledger = ledger;
        this.adhoc = adhoc;
        this.statements = statements;
        this.access = access;
        this.email = email;
        this.outbox = outbox;
        this.appUrl = appUrl;
    }

    record GenerateRequest(
            String period,      // 'YYYY-MM' — null = bulan semasa
            String mode         // CURRENT | PREPAID | POSTPAID — null = ikut setting SP
    ) {}

    record GenerateResult(String spCode, String period, String mode, int invoicesPosted,
                         int accountsScanned, int skippedNoSubscription,
                         int skippedNothingToCharge, int skippedAlreadyGenerated,
                         java.util.List<String> billedPeriods) {}

    @PostMapping("/generate-invoices")
    GenerateResult generate(@RequestBody(required = false) GenerateRequest req) {
        String sp = sp();

        YearMonth runMonth = (req == null || req.period() == null || req.period().isBlank())
                ? YearMonth.now()
                : YearMonth.parse(req.period());

        BillingSettings cfg = settings.forSp(sp);

        // Mode: request boleh mengatasi setting SP (untuk jana manual ad-hoc).
        GenMode mode = (req == null || req.mode() == null || req.mode().isBlank())
                ? GenMode.valueOf(cfg.genMode())
                : GenMode.valueOf(req.mode());

        // GL: setting simpan id (bigint); ledger terjemah ke kod. null -> default.
        String arGl = cfg.arGlAccountId() == null
                ? GlAccounts.ACCOUNTS_RECEIVABLE
                : ledger.glCodeFor(sp, cfg.arGlAccountId());
        String incomeGl = cfg.incomeGlAccountId() == null
                ? GlAccounts.SERVICE_INCOME
                : ledger.glCodeFor(sp, cfg.incomeGlAccountId());

        BillingContext ctx = new BillingContext(
                sp,
                cfg.taxRate(),
                cfg.smallestDenomination().signum() == 0 ? null : cfg.smallestDenomination(),
                cfg.allowPriceOverride(),
                cfg.termDays(),
                excludedPeriodIds(sp),
                arGl,
                GlAccounts.TAX_PAYABLE,
                incomeGl,
                cfg.splitInvoiceByProduct());

        var out = billing.generateDetailed(sp, runMonth, mode, ctx);
        // Tempoh yang BENAR-BENAR dibilkan — bukan bulan larian. POSTPAID pada
        // Julai membilkan Jun; melaporkan runMonth adalah menipu.
        java.util.List<String> billed = out.billedPeriodIds().isEmpty()
                ? java.util.List.of()
                : em.createNativeQuery(
                        "SELECT name_ FROM fi_period WHERE period_id IN (:ids) ORDER BY period_id")
                    .setParameter("ids", out.billedPeriodIds())
                    .getResultList();

        beraturLaporan(sp, out, billed);

        return new GenerateResult(sp, runMonth.toString(), mode.name(), out.invoicesPosted(),
                out.accountsScanned(), out.skippedNoSubscription(),
                out.skippedNothingToCharge(), out.skippedAlreadyGenerated(), billed);
    }

    /**
     * Laporan penjanaan kepada admin SP (ADR 0014 P2).
     *
     * Legacy menghantarnya dan ia berguna: tanpa laporan, larian tengah
     * malam yang gagal separuh jalan tidak diketahui sehingga seseorang
     * perasan invois hilang.
     *
     * SATU BARIS SETIAP ADMIN, bukan satu baris dengan senarai alamat.
     * Kegagalan kepada satu alamat tidak menjejaskan yang lain — itu
     * sebab utama outbox wujud.
     *
     * SP_ADMIN sahaja. Kerani mengendalikan bayaran, bukan mengawasi
     * larian bil.
     *
     * Penerima dibaca semasa BERATUR, bukan semasa menghantar: kalau
     * admin dibuang antara larian dan penghantaran, laporan tetap pergi
     * kepada orang yang berhak pada masa larian itu.
     *
     * Ringkasan disimpan sebagai SNAPSHOT. Menyoal semula semasa
     * menghantar memberi nombor yang BERBEZA kalau kerani menjana
     * sekali lagi — dan laporan melaporkan larian itu, bukan keadaan
     * sekarang.
     */
    @SuppressWarnings("unchecked")
    private void beraturLaporan(String spCode,
                                InvoiceGenerationService.GenerationOutcome out,
                                java.util.List<String> tempoh) {
        try {
            // Tetapan DISEMAK, bukan diandaikan. sp_notification_setting
            // wujud sejak awal dan ini penggunaan pertamanya — corak
            // CASE-008 ialah tetapan yang disimpan, dibaca, dan tidak
            // pernah dikuatkuasakan.
            // tinyint(1) dipulangkan sebagai Boolean oleh Connector/J,
            // bukan Number — cast kepada Number melontar ClassCastException
            // semasa larian, bukan semasa kompil.
            Object hidup = em.createNativeQuery(
                    "SELECT email_on_invoice FROM sp_notification_setting WHERE sp_code = :sp")
                    .setParameter("sp", spCode)
                    .getResultList().stream().findFirst().orElse(null);
            if (hidup != null && !benar(hidup)) {
                return;
            }

            // Nama SP dan mata wang dalam satu query — currency tiada
            // dalam BillingSettings, dan dua query untuk dua medan pada
            // baris yang berkaitan ialah kerja tanpa faedah.
            Object[] sp1 = (Object[]) em.createNativeQuery("""
                    SELECT p.name, COALESCE(b.currency, 'MYR')
                    FROM   service_provider p
                    LEFT   JOIN sp_billing_setting b ON b.sp_code = p.sp_code
                    WHERE  p.sp_code = :sp
                    """).setParameter("sp", spCode).getSingleResult();
            String spName = (String) sp1[0];
            String currency = (String) sp1[1];

            // DISTINCT: seorang pengguna boleh memegang SP_ADMIN dan
            // CLERK pada SP yang sama, dan tidak sepatutnya menerima
            // laporan dua kali.
            List<String> alamat = em.createNativeQuery("""
                    SELECT DISTINCT u.email
                    FROM   sp_membership m
                    JOIN   app_user u ON u.id = m.user_id
                    WHERE  m.sp_code = :sp
                      AND  m.role = 'SP_ADMIN'
                      AND  m.status = 'ACTIVE'
                      AND  u.status = 'ACTIVE'
                      AND  u.email IS NOT NULL
                    """).setParameter("sp", spCode).getResultList();

            String hariIni = java.time.LocalDate.now().toString();
            String ringkasan = String.join("|",
                    hariIni,
                    String.valueOf(out.accountsScanned()),
                    String.valueOf(out.invoicesPosted()),
                    currency + " " + jumlahDibil(spCode, out.billedPeriodIds()),
                    String.join(",", tempoh));

            for (String to : alamat) {
                // ref_key termasuk TARIKH: kerani yang menjana dua kali
                // dalam bulan yang sama mendapat dua laporan, kerana
                // setiap larian menghasilkan invois baharu yang dia
                // patut tahu. Alamat disertakan supaya tiga admin
                // menghasilkan tiga baris tanpa berlanggar pada UNIQUE.
                outbox.queue(spCode, EmailOutboxPort.Kind.GENERATION_REPORT,
                        spCode + ":" + hariIni + ":" + to,
                        to, null,
                        params("p_sp_name", spName, "p_summary", ringkasan));
            }

        } catch (RuntimeException e) {
            // Invois sudah dijana dan dipos. Laporan yang gagal beratur
            // tidak boleh menggulungnya.
            log.error("Gagal beratur laporan penjanaan untuk {}: {}", spCode, e.getMessage());
        }
    }

    /**
     * LinkedHashMap dengan susunan penulisan yang DIJAMIN.
     *
     * new LinkedHashMap<>(Map.of(...)) TIDAK berbuat demikian: Map.of
     * menyusun ikut hash, dan membalutnya mengekalkan susunan rawak itu.
     * Laporan pertama yang beratur menyimpan ringkasan dalam param1 dan
     * nama SP dalam param2 — terbalik, dan renderer menolaknya.
     */
    private static java.util.Map<String, String> params(String k1, String v1,
                                                        String k2, String v2) {
        var m = new java.util.LinkedHashMap<String, String>();
        m.put(k1, v1);
        m.put(k2, v2);
        return m;
    }

    /**
     * tinyint(1) daripada native query.
     *
     * Connector/J memulangkannya sebagai Boolean, bukan Number. Menerima
     * kedua-duanya supaya perubahan pemacu atau taip lajur tidak
     * memecahkan semakan tetapan secara senyap.
     */
    private static boolean benar(Object v) {
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() != 0;
        return Boolean.parseBoolean(String.valueOf(v));
    }

    /** Jumlah invois yang dikeluarkan dalam larian ini. */
    private String jumlahDibil(String spCode, java.util.Set<Long> periodIds) {
        if (periodIds.isEmpty()) return "0.00";
        Object v = em.createNativeQuery("""
                SELECT COALESCE(SUM(l.amount + l.tax_amount), 0)
                FROM   financial_document_line l
                JOIN   financial_document d ON d.id = l.document_id
                WHERE  d.sp_code = :sp AND d.doc_type = 'INVOICE'
                  AND  d.status <> 'CANCELLED' AND l.active = 1
                  AND  l.period_id IN (:ids)
                """).setParameter("sp", spCode)
                .setParameter("ids", periodIds)
                .getSingleResult();
        return new java.math.BigDecimal(v.toString()).setScale(2,
                java.math.RoundingMode.HALF_UP).toPlainString();
    }

    record GenerateSingleRequest(Long accountId, String period, String mode) {}
    record GenerateSingleResult(Long accountId, String period, String mode,
                               int invoicesPosted,
                               /**
                                * Tempoh LIPUTAN yang dibilkan, tersusun.
                                *
                                * 'period' ialah bulan LARIAN yang kerani pilih.
                                * Untuk postpaid ia satu bulan ke hadapan
                                * daripada liputan, dan untuk akaun YEAR satu
                                * larian menghasilkan dua belas tempoh.
                                * Melaporkan bulan larian sahaja memberitahu
                                * kerani sesuatu yang tidak muncul pada
                                * mana-mana invois.
                                */
                               java.util.List<String> billedPeriods) {}

    @PostMapping("/generate-single")
    GenerateSingleResult generateSingle(@RequestBody GenerateSingleRequest req) {
        String sp = sp();
        if (req == null || req.accountId() == null) {
            throw new IllegalArgumentException("accountId diperlukan.");
        }

        YearMonth runMonth = (req.period() == null || req.period().isBlank())
                ? YearMonth.now()
                : YearMonth.parse(req.period());

        BillingSettings cfg = settings.forSp(sp);
        GenMode mode = (req.mode() == null || req.mode().isBlank())
                ? GenMode.valueOf(cfg.genMode())
                : GenMode.valueOf(req.mode());

        String arGl = cfg.arGlAccountId() == null
                ? GlAccounts.ACCOUNTS_RECEIVABLE
                : ledger.glCodeFor(sp, cfg.arGlAccountId());
        String incomeGl = cfg.incomeGlAccountId() == null
                ? GlAccounts.SERVICE_INCOME
                : ledger.glCodeFor(sp, cfg.incomeGlAccountId());

        BillingContext ctx = new BillingContext(
                sp,
                cfg.taxRate(),
                cfg.smallestDenomination().signum() == 0 ? null : cfg.smallestDenomination(),
                cfg.allowPriceOverride(),
                cfg.termDays(),
                excludedPeriodIds(sp),
                arGl,
                GlAccounts.TAX_PAYABLE,
                incomeGl,
                cfg.splitInvoiceByProduct());

        var hasil = billing.generateForAccountDetailed(
                sp, req.accountId(), runMonth, mode, ctx);

        return new GenerateSingleResult(req.accountId(), runMonth.toString(),
                mode.name(), hasil.invoicesPosted(),
                namaTempoh(hasil.billedPeriodIds()));
    }

    /**
     * Nama tempoh daripada fi_period, tersusun ikut masa.
     *
     * Kerani melihat 'Julai 2026', bukan 2026230700.
     */
    @SuppressWarnings("unchecked")
    private java.util.List<String> namaTempoh(java.util.Set<Long> periodIds) {
        if (periodIds == null || periodIds.isEmpty()) return java.util.List.of();
        var rows = em.createNativeQuery(
                "SELECT name_ FROM fi_period WHERE period_id IN (:ids) "
                + "ORDER BY start_dt")
                .setParameter("ids", periodIds)
                .getResultList();
        java.util.List<String> out = new java.util.ArrayList<>();
        for (Object r : rows) {
            if (r != null) out.add(r.toString());
        }
        return out;
    }

    record PeriodOption(Long periodId, String name, java.time.LocalDate startDt) {}

    /**
     * Tempoh BULANAN untuk dropdown.
     *
     * Julat sengaja sempit: enam bulan ke belakang, dua belas ke hadapan.
     * fi_period mengandungi tempoh untuk bertahun-tahun, dan dropdown
     * dengan dua ratus pilihan menjadikan pemilihan lebih sukar bukan
     * lebih mudah.
     *
     * Ke belakang dibenarkan kerana invois adhoc kadang direkod lewat —
     * caj clamp minggu lepas dikeluarkan hari ini.
     */
    @GetMapping("/periods")
    @SuppressWarnings("unchecked")
    List<PeriodOption> periods() {
        Access.requireAnyRole("melihat tempoh", "SP_ADMIN", "CLERK");

        List<Object[]> rows = em.createNativeQuery("""
                SELECT period_id, name_, start_dt
                FROM   fi_period
                WHERE  DATEDIFF(start_dt, CURDATE()) BETWEEN -190 AND 400
                  AND  DATEDIFF(end_dt, start_dt) BETWEEN 27 AND 31
                ORDER  BY start_dt
                """).getResultList();

        List<PeriodOption> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            out.add(new PeriodOption(
                    ((Number) r[0]).longValue(),
                    (String) r[1],
                    r[2] instanceof java.time.LocalDate d ? d
                            : ((java.sql.Date) r[2]).toLocalDate()));
        }
        return out;
    }

    /**
     * Invois adhoc — kepada orang yang BUKAN pelanggan berdaftar.
     *
     * Caj clamp kepada pemandu luar; jualan buku pada pameran sekolah.
     * Semua berkongsi satu akaun ADHOC-SALES (V50) dan butiran penerima
     * duduk pada dokumen.
     */
    @PostMapping("/adhoc-invoice")
    AdhocInvoiceService.Result adhocInvoice(
            @RequestBody AdhocInvoiceService.Request req) {
        Access.requireAnyRole("menjana invois adhoc", "SP_ADMIN", "CLERK");
        var hasil = adhoc.create(sp(), req);
        hantarInvoisAdhoc(hasil.documentId());
        return hasil;
    }

    /**
     * E-mel invois adhoc — PAUTAN, bukan lampiran (EmailPort).
     *
     * Penerima adhoc BUKAN pelanggan berdaftar: alamat duduk pada
     * dokumen (issued_to_email), bukan pada akaun. Akaun ADHOC-SALES
     * dikongsi dan tidak membawa e-mel sesiapa, jadi membaca
     * header().billtoEmail() seperti laluan resit akan sentiasa kosong.
     *
     * Senyap jika tiada alamat — borang menyatakan "tanpa e-mel, invois
     * mesti dicetak dan diserahkan sendiri". Itu pilihan yang sah, bukan
     * ralat.
     *
     * Kegagalan e-mel TIDAK menggagalkan invois. Dokumen sudah wujud dan
     * ledger sudah dipos; membiarkan penyedia e-mel menggulung transaksi
     * bermakna kerani kehilangan invois kerana Resend sedang tunggang.
     * Corak sama seperti hantarResit dalam ManualPaymentController.
     *
     * Invois BERULANG tidak dihantar dari sini — lihat package-info.
     */
    private void hantarInvoisAdhoc(long documentId) {
        try {
            var m = statements.invoice(sp(), documentId);
            String to = m.issuedToEmail();
            if (to == null || to.isBlank()) {
                return;
            }

            String token = access.tokenFor(sp(), documentId, DocumentType.INVOICE);

            email.resendDocument(
                    List.of(to),
                    m.issuedToName(),
                    m.header().spName(),
                    m.documentTitle(),
                    m.invoiceNo(),
                    m.header().currency() + " " + m.totalDue().toPlainString(),
                    m.invoiceDate().toString(),
                    appUrl + "/api/v1/pub/invoices/" + token);

        } catch (RuntimeException e) {
            log.error("Gagal hantar e-mel invois adhoc untuk dokumen {}: {}",
                    documentId, e.getMessage());
        }
    }

    /** period_id BULAN yang dikecualikan untuk SP ini (invoice_exclude_period). */
    @SuppressWarnings("unchecked")
    private Set<Long> excludedPeriodIds(String spCode) {
        var rows = em.createNativeQuery(
                "SELECT period_id FROM invoice_exclude_period WHERE sp_code = :sp")
                .setParameter("sp", spCode).getResultList();
        Set<Long> out = new HashSet<>();
        for (Object r : rows) {
            if (r != null) out.add(((Number) r).longValue());
        }
        return out;
    }

    private String sp() {
        String sp = TenantContext.get();
        if (sp == null || sp.isBlank()) {
            throw new IllegalStateException("Header X-SP-Id diperlukan");
        }
        return sp;
    }
}
