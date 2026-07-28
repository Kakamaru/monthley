package com.monthley.payment.internal;

import com.monthley.payment.api.*;
import com.monthley.shared.Access;
import com.monthley.shared.PageResponse;
import com.monthley.shared.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Manual Payment — rekod bayaran tunai/cek/pindahan yang diterima di luar talian.
 *
 *   GET  /api/v1/payments/outstanding  — cari invois tertunggak
 *   POST /api/v1/payments/manual       — rekod bayaran
 *
 * PERANAN: CLERK (Cashier) sahaja. Admin TIDAK boleh terima bayaran —
 * ini pengasingan tugas yang disengajakan.
 */
@RestController
@RequestMapping("/api/v1/payments")
class ManualPaymentController {

    private final PaymentPort payments;

    @PersistenceContext
    private EntityManager em;

    ManualPaymentController(PaymentPort payments) {
        this.payments = payments;
    }

    // ---------- DTO ----------

    record OutstandingRow(
            Long documentId, String accountNo, String accountName, Long accountId,
            String invoiceNo, String period, LocalDate docDate, LocalDate dueDate,
            BigDecimal total, BigDecimal paid, BigDecimal outstanding,
            String itemDesc) {}   // keterangan bila dokumen ada TEPAT satu baris

    record ManualPaymentRequest(
            java.util.List<Long> documentIds,  // invois dipilih; kosong = auto FIFO semua
            @NotNull Long accountId,
            @NotBlank String paymentType,      // CASH | CHEQUE | TRANSFER | FPX | ADJUSTMENT
            String paymentRefNo,
            String paymentDate,                // 'YYYY-MM-DD'
            @NotNull @Positive BigDecimal amount,
            String remarks,
            String idempotencyKey) {}   // token elak double-entry (ADR 0004)

    record PaymentTypeDto(String code, String label) {}
    record MessageResponse(String message) {}

    // ---------- Endpoints ----------

    @GetMapping("/payment-types")
    List<PaymentTypeDto> paymentTypes() {
        return List.of(
                new PaymentTypeDto("CASH",     "Tunai"),
                new PaymentTypeDto("CHEQUE",   "Cek"),
                new PaymentTypeDto("TRANSFER", "Pindahan Bank"),
                new PaymentTypeDto("FPX",      "FPX / Online"),
                new PaymentTypeDto("ADJUSTMENT", "Penyelarasan"));
    }

    // Tab Account: satu baris per akaun, jumlah tertunggak (SUM baki semua invois akaun).
    record OutstandingAccountRow(Long accountId, String accountNo, String accountName, BigDecimal balance) {}

    @GetMapping("/outstanding-accounts")
    @SuppressWarnings("unchecked")
    PageResponse<OutstandingAccountRow> outstandingAccounts(
            @RequestParam(required = false) String account,
            @RequestParam(required = false) String name,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        Access.requireRole("CLERK", "melihat akaun untuk bayaran");

        String acc = blankToNull(account);
        String nm = blankToNull(name);

        // SEMUA akaun aktif — bukan hanya yang bertunggak.
        //
        // Sebelum ini ditapis kepada akaun yang ada invois terbuka. Kesannya:
        // pelanggan yang bayar lebih terus HILANG dari senarai, dan SP tidak
        // boleh merekod bayaran seterusnya sehingga invois baharu dijana.
        // Menyekat untuk operasi sebenar.
        //
        // Carian dan pagination sudah menangani senarai panjang.
        String base = """
            FROM account a
            LEFT JOIN account_balance ab ON ab.account_id = a.id
            WHERE a.sp_code = :sp
              AND a.status = 'ACTIVE'
              AND (:acc IS NULL OR LOWER(a.account_no) LIKE :acc)
              AND (:nm  IS NULL OR LOWER(a.account_name) LIKE :nm)
            """;

        var countQ = em.createNativeQuery(
                "SELECT COUNT(*) " + base);
        countQ.setParameter("sp", sp());
        countQ.setParameter("acc", acc == null ? null : "%" + acc.toLowerCase() + "%");
        countQ.setParameter("nm", nm == null ? null : "%" + nm.toLowerCase() + "%");
        long total = ((Number) countQ.getSingleResult()).longValue();

        // Baki dari view account_balance (ADR 0009) — satu takrifan dikongsi.
        // Sebelum ini query ini mengira sendiri: jumlah invois TERBUKA, yang
        // bukan sama dengan baki akaun (abaikan advance belum dialokasi).
        String sql = "SELECT a.id, a.account_no, a.account_name, "
                + "COALESCE(ab.balance, 0) AS balance "
                + base
                + " ORDER BY a.account_no LIMIT :lim OFFSET :off";

        var dataQ = em.createNativeQuery(sql);
        dataQ.setParameter("sp", sp());
        dataQ.setParameter("acc", acc == null ? null : "%" + acc.toLowerCase() + "%");
        dataQ.setParameter("nm", nm == null ? null : "%" + nm.toLowerCase() + "%");
        dataQ.setParameter("lim", size);
        dataQ.setParameter("off", page * size);

        List<Object[]> rows = dataQ.getResultList();
        List<OutstandingAccountRow> items = new ArrayList<>();
        for (Object[] r : rows) {
            items.add(new OutstandingAccountRow(
                    ((Number) r[0]).longValue(), (String) r[1], (String) r[2],
                    (BigDecimal) r[3]));
        }
        return new PageResponse<>(items, total, page, size);
    }

    // Baris (txn) satu dokumen — untuk expand di page bayaran.
    record DocumentLineRow(Long lineId, String description, java.math.BigDecimal amount,
                           LocalDate periodStart, LocalDate periodEnd) {}

    @GetMapping("/documents/{id}/lines")
    @SuppressWarnings("unchecked")
    List<DocumentLineRow> documentLines(@PathVariable Long id) {
        Access.requireRole("CLERK", "melihat pecahan invois");
        List<Object[]> rows = em.createNativeQuery("""
                SELECT l.id, l.description, l.amount, l.period_start, l.period_end
                FROM financial_document_line l
                JOIN financial_document d ON d.id = l.document_id
                WHERE l.document_id = :id AND d.sp_code = :sp AND l.active = 1
                ORDER BY l.period_start, l.id
                """)
                .setParameter("id", id)
                .setParameter("sp", sp())
                .getResultList();
        List<DocumentLineRow> out = new ArrayList<>();
        for (Object[] r : rows) {
            out.add(new DocumentLineRow(
                    ((Number) r[0]).longValue(), (String) r[1], (java.math.BigDecimal) r[2],
                    toLocalDate(r[3]), toLocalDate(r[4])));
        }
        return out;
    }

    @GetMapping("/outstanding")
    @SuppressWarnings("unchecked")
    PageResponse<OutstandingRow> outstanding(
            @RequestParam(required = false) String account,
            @RequestParam(required = false) String invoice,
            @RequestParam(required = false) Long category,
            @RequestParam(required = false) Long product,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        Access.requireRole("CLERK", "melihat invois untuk bayaran");

        String acc = blankToNull(account);
        String inv = blankToNull(invoice);

        String where = """
            WHERE d.sp_code = :sp
              AND d.doc_type IN ('INVOICE','DEBIT_NOTE')
              AND d.status <> 'CANCELLED'
              AND (:acc IS NULL OR LOWER(a.account_no) LIKE :acc OR LOWER(a.account_name) LIKE :acc)
              AND (:inv IS NULL OR LOWER(d.doc_no) LIKE :inv)
              AND (:product IS NULL OR EXISTS (
                    SELECT 1 FROM financial_document_line l
                    WHERE l.document_id = d.id AND l.product_id = :product AND l.active = 1))
              AND (:category IS NULL OR EXISTS (
                    SELECT 1 FROM financial_document_line l
                    JOIN product p ON p.id = l.product_id
                    WHERE l.document_id = d.id AND p.category_id = :category AND l.active = 1))
              AND (d.amount + d.tax_amount) - COALESCE((
                    SELECT SUM(al.amount) FROM fi_allocation al
                    WHERE al.debit_document_id = d.id AND al.status = 'ACTIVE'), 0) > 0.005
            """;

        var countQ = em.createNativeQuery("""
                SELECT COUNT(*) FROM financial_document d
                JOIN account a ON a.id = d.account_id
                """ + where);
        bind(countQ, acc, inv, category, product);
        long total = ((Number) countQ.getSingleResult()).longValue();

        String sql = """
            SELECT d.id, a.account_no, a.account_name, a.id, d.doc_no,
                   COALESCE(p.name_, d.title) AS descr,
                   d.doc_date, d.due_date,
                   (d.amount + d.tax_amount) AS total,
                   COALESCE((SELECT SUM(al.amount) FROM fi_allocation al
                             WHERE al.debit_document_id = d.id AND al.status = 'ACTIVE'), 0) AS paid,
                   (SELECT CASE WHEN COUNT(*) = 1 THEN MAX(l.description) END
                      FROM financial_document_line l
                     WHERE l.document_id = d.id AND l.active = 1) AS sole_desc
            FROM financial_document d
            JOIN account a ON a.id = d.account_id
            LEFT JOIN fi_period p ON p.period_id = d.period_id
            """ + where + " ORDER BY a.account_no, COALESCE(d.due_date, d.doc_date), d.doc_no LIMIT :lim OFFSET :off";

        var dataQ = em.createNativeQuery(sql);
        bind(dataQ, acc, inv, category, product);
        dataQ.setParameter("lim", size);
        dataQ.setParameter("off", page * size);

        List<Object[]> rows = dataQ.getResultList();
        List<OutstandingRow> items = new ArrayList<>();
        for (Object[] r : rows) {
            BigDecimal t = (BigDecimal) r[8];
            BigDecimal p = (BigDecimal) r[9];
            items.add(new OutstandingRow(
                    ((Number) r[0]).longValue(), (String) r[1], (String) r[2],
                    ((Number) r[3]).longValue(), (String) r[4], (String) r[5],
                    toLocalDate(r[6]), toLocalDate(r[7]), t, p, t.subtract(p),
                    (String) r[10]));
        }
        return new PageResponse<>(items, total, page, size);
    }

    /**
     * SP boleh mematikan bayaran manual — sesetengah hanya menerima
     * bayaran dalam talian dan tidak mahu kaunter tunai.
     *
     * Tetapan itu wujud dalam Tetapan Resit sejak V14 tetapi TIDAK PERNAH
     * disemak: ia hanya muncul dalam SettingsController, dibaca dan
     * ditulis. Kerani boleh merekod bayaran walaupun SP mematikannya
     * (CASE-008 kes 6).
     *
     * Pengecualian TIDAK ditelan. allowSelective menelannya dan
     * mengembalikan false, yang bermakna kegagalan query kelihatan seperti
     * tetapan dimatikan — bayaran ditolak tanpa sebab yang jelas
     * (soalan terbuka 15).
     */
    private boolean manualPaymentDibenarkan(String spCode) {
        Object v = em.createNativeQuery(
                "SELECT enable_manual_payment FROM sp_document_setting WHERE sp_code = :sp")
                .setParameter("sp", spCode)
                .getResultList().stream().findFirst().orElse(null);
        return v != null && ("1".equals(v.toString()) || "true".equalsIgnoreCase(v.toString()));
    }

    @PostMapping("/manual")
    ResponseEntity<?> recordPayment(@Valid @RequestBody ManualPaymentRequest r) {
        Access.requireRole("CLERK", "merekod bayaran");

        if (!manualPaymentDibenarkan(sp())) {
            throw new IllegalStateException(
                    "Bayaran manual dimatikan untuk SP ini. Hidupkan "
                    + "'Enable Manual Payment' dalam Tetapan \u2192 Resit.");
        }

        PaymentResult result = payments.receivePayment(new NewPayment(
                sp(), r.accountId(), r.amount(),
                PaymentMethod.valueOf(r.paymentType()),
                r.paymentRefNo(),
                r.documentIds() == null ? java.util.List.of() : r.documentIds(),   // invois dipilih (multi)
                r.idempotencyKey(),
                // Tarikh yang kerani masukkan. Sebelum ini diterima di sini
                // dan DIBUANG — resit, ledger dan rekod bayaran semuanya
                // menggunakan LocalDate.now(). Bayaran yang diterima dua hari
                // lepas muncul pada tarikh rekod, bukan tarikh terima.
                (r.paymentDate() == null || r.paymentDate().isBlank())
                        ? null
                        : java.time.LocalDate.parse(r.paymentDate()),
                r.remarks()));

        return ResponseEntity.ok(result);
    }

    // ---------- helper ----------

    private void bind(jakarta.persistence.Query q, String acc, String inv, Long category, Long product) {
        q.setParameter("sp", sp());
        q.setParameter("acc", acc == null ? null : "%" + acc.toLowerCase() + "%");
        q.setParameter("inv", inv == null ? null : "%" + inv.toLowerCase() + "%");
        q.setParameter("category", category);
        q.setParameter("product", product);
    }

    // ---------- Invoice Vs Receipt (report payment per akaun, tapis tahun period) ----------
    // Grain = per invois (doc-level). Tapis ikut TAHUN PERIOD BILLING (bukan doc_date):
    //   invois period 2025 dijana tahun 2026 -> muncul bila pilih 2025.
    // Kolum Receipt = senarai resit yang knock invois (fi_allocation ACTIVE),
    //   'RCP.. : amt / RCP.. : amt' bila >1; kosong bila belum bayar.
    // Advance (lebihan tanpa invois) SKIP — view ni invois-vs-resit sahaja.
    // Descending: period_id DESC (period terkini atas), doc_no DESC.
    record PaymentReportRow(String period, String invoice,
                            java.math.BigDecimal invAmount, String receipts) {}

    @GetMapping("/payment-report")
    @SuppressWarnings("unchecked")
    PageResponse<PaymentReportRow> paymentReport(
            @RequestParam Long accountId,
            @RequestParam String year,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        Access.requireRole("CLERK", "melihat laporan bayaran");

        String base = """
            FROM financial_document d
            LEFT JOIN fi_period fp ON fp.period_id = d.period_id
            WHERE d.sp_code = :sp
              AND d.account_id = :acc
              AND d.doc_type = 'INVOICE'
              AND d.status <> 'CANCELLED'
              AND LEFT(d.period_id, 4) = :yr
            """;

        var countQ = em.createNativeQuery("SELECT COUNT(*) " + base);
        countQ.setParameter("sp", sp());
        countQ.setParameter("acc", accountId);
        countQ.setParameter("yr", year);
        long total = ((Number) countQ.getSingleResult()).longValue();

        String sql = "SELECT fp.name_ AS period, d.doc_no AS invoice, "
                + "(d.amount + d.tax_amount) AS inv_amt, "
                + "(SELECT GROUP_CONCAT(CONCAT(rc.doc_no, ' : ', FORMAT(a.amount, 2)) "
                + "          ORDER BY rc.doc_no SEPARATOR ' / ') "
                + "   FROM fi_allocation a "
                + "   JOIN financial_document rc ON rc.id = a.credit_document_id "
                + "  WHERE a.debit_document_id = d.id AND a.status = 'ACTIVE') AS receipts "
                + base
                + " ORDER BY d.period_id DESC, d.doc_no DESC LIMIT :lim OFFSET :off";

        var q = em.createNativeQuery(sql);
        q.setParameter("sp", sp());
        q.setParameter("acc", accountId);
        q.setParameter("yr", year);
        q.setParameter("lim", size);
        q.setParameter("off", page * size);

        List<Object[]> rows = q.getResultList();
        List<PaymentReportRow> items = new ArrayList<>();
        for (Object[] r : rows) {
            items.add(new PaymentReportRow(
                    (String) r[0], (String) r[1],
                    (java.math.BigDecimal) r[2], (String) r[3]));
        }
        return new PageResponse<>(items, total, page, size);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private String sp() {
        String sp = TenantContext.get();
        if (sp == null || sp.isBlank()) {
            throw new IllegalStateException("Header X-SP-Id diperlukan");
        }
        return sp;
    }

    private static LocalDate toLocalDate(Object o) {
        if (o == null) return null;
        if (o instanceof LocalDate d) return d;
        if (o instanceof java.time.LocalDateTime dt) return dt.toLocalDate();
        if (o instanceof java.sql.Timestamp ts) return ts.toLocalDateTime().toLocalDate();
        if (o instanceof java.sql.Date d) return d.toLocalDate();
        return null;
    }
}
