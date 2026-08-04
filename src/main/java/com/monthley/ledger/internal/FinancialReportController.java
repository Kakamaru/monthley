package com.monthley.ledger.internal;

import com.monthley.shared.Access;
import com.monthley.shared.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Laporan kewangan daripada ledger — Imbangan Duga dan Untung Rugi.
 *
 * ENTRI DIBALIKKAN KEKAL DIKIRA.
 *
 * Membatalkan invois menandakan entri asal REVERSED dan mengepos entri
 * CANCELLATION yang membalikkannya. Kedua-duanya mesti dikira: ia
 * membatalkan satu sama lain secara semula jadi, dan itulah maksud
 * pembukuan berpasangan.
 *
 * Menapis status = 'POSTED' membuang entri asal sambil MENGEKALKAN
 * pembalikannya — kesan bersih tolak sekali, bukan sifar. Draf pertama
 * laporan ini melakukan tepat itu, dan AR terkurang RM391.50 daripada
 * sub-lejar.
 *
 * Hanya DRAFT dikecualikan: ia belum berlaku.
 *
 * BALANCE SHEET TIDAK ADA DI SINI. Ia memerlukan baki pembukaan dan
 * ekuiti; source_type OPENING wujud tetapi belum ada data. Laporan
 * kunci kira-kira tanpa baki pembukaan bukan kosong — ia SALAH, dan itu
 * lebih teruk.
 */
@RestController
@RequestMapping("/api/v1/reports")
class FinancialReportController {

    @PersistenceContext
    private EntityManager em;

    // ── Imbangan Duga ────────────────────────────────────────────────

    record TrialRow(String code, String name, String accountType,
                    BigDecimal debit, BigDecimal credit) {}

    record TrialBalance(LocalDate asAt, List<TrialRow> rows,
                        BigDecimal totalDebit, BigDecimal totalCredit,
                        boolean balanced) {}

    /**
     * Imbangan Duga pada satu tarikh.
     *
     * Bukan laporan untuk membuat keputusan — ia UJIAN. Kalau dua jumlah
     * tidak sama, ada sesuatu rosak dalam pembukuan dan setiap laporan
     * lain tidak boleh dipercayai sehingga ia dibetulkan.
     *
     * Terkumpul sejak permulaan hingga tarikh dipilih: itu maksud
     * 'imbangan duga pada 31 Disember'.
     *
     * Setiap akaun muncul dalam SATU lajur — sisi bakinya. Baki negatif
     * dalam satu lajur bukan konvensyen yang akauntan kenal.
     */
    @GetMapping("/trial-balance")
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    TrialBalance trialBalance(@RequestParam(required = false) LocalDate asAt) {
        Access.requireAnyRole("melihat imbangan duga", "SP_ADMIN", "CLERK", "VIEWER");

        LocalDate had = asAt == null ? LocalDate.now() : asAt;

        List<Object[]> rows = em.createNativeQuery("""
                SELECT c.code, c.name, c.account_type,
                       SUM(l.debit_amount - l.credit_amount) AS baki
                FROM   journal_line      l
                JOIN   journal_entry     e ON e.id = l.journal_entry_id
                JOIN   chart_of_accounts c ON c.id = l.gl_account_id
                WHERE  e.sp_code = :sp
                  AND  e.status <> 'DRAFT'
                  AND  e.entry_date <= :had
                GROUP  BY c.id, c.code, c.name, c.account_type
                HAVING ABS(SUM(l.debit_amount - l.credit_amount)) > 0.005
                ORDER  BY c.code
                """)
                .setParameter("sp", sp())
                .setParameter("had", had)
                .getResultList();

        List<TrialRow> items = new ArrayList<>();
        BigDecimal jumDr = BigDecimal.ZERO, jumCr = BigDecimal.ZERO;

        for (Object[] r : rows) {
            BigDecimal baki = (BigDecimal) r[3];
            BigDecimal dr = baki.signum() > 0 ? baki : BigDecimal.ZERO;
            BigDecimal cr = baki.signum() < 0 ? baki.negate() : BigDecimal.ZERO;
            jumDr = jumDr.add(dr);
            jumCr = jumCr.add(cr);
            items.add(new TrialRow((String) r[0], (String) r[1], (String) r[2], dr, cr));
        }

        return new TrialBalance(had, items, jumDr, jumCr,
                jumDr.subtract(jumCr).abs().compareTo(new BigDecimal("0.005")) < 0);
    }

    // ── Untung Rugi ──────────────────────────────────────────────────

    record PnlRow(String code, String name, BigDecimal amount) {}

    record ProfitLoss(LocalDate from, LocalDate to,
                      List<PnlRow> income, BigDecimal totalIncome,
                      List<PnlRow> expense, BigDecimal totalExpense,
                      BigDecimal net, boolean expenseModuleActive) {}

    /**
     * Untung Rugi bagi julat tarikh.
     *
     * INCOME dan EXPENSE sahaja — akaun kunci kira-kira tidak muncul.
     *
     * Hasil ialah baki KREDIT, jadi tandanya dibalikkan untuk paparan:
     * pendapatan RM8,831 muncul sebagai 8,831 positif, bukan -8,831.
     *
     * PERBELANJAAN MUNGKIN KOSONG. Monthley menjejaki hasil dan
     * penghutang; gaji, elektrik dan penyelenggaraan datang daripada
     * modul Expenses. Laporan menyatakannya secara eksplisit supaya SP
     * tidak membaca kosong sebagai 'tiada perbelanjaan'.
     */
    @GetMapping("/profit-loss")
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    ProfitLoss profitLoss(@RequestParam(required = false) LocalDate from,
                          @RequestParam(required = false) LocalDate to) {
        Access.requireAnyRole("melihat untung rugi", "SP_ADMIN", "CLERK", "VIEWER");

        LocalDate mula = from == null ? LocalDate.now().withDayOfYear(1) : from;
        LocalDate tamat = to == null ? LocalDate.now() : to;

        List<Object[]> rows = em.createNativeQuery("""
                SELECT c.code, c.name, c.account_type,
                       SUM(l.debit_amount - l.credit_amount) AS baki
                FROM   journal_line      l
                JOIN   journal_entry     e ON e.id = l.journal_entry_id
                JOIN   chart_of_accounts c ON c.id = l.gl_account_id
                WHERE  e.sp_code = :sp
                  AND  e.status <> 'DRAFT'
                  AND  e.entry_date BETWEEN :mula AND :tamat
                  AND  c.account_type IN ('INCOME', 'EXPENSE')
                GROUP  BY c.id, c.code, c.name, c.account_type
                HAVING ABS(SUM(l.debit_amount - l.credit_amount)) > 0.005
                ORDER  BY c.code
                """)
                .setParameter("sp", sp())
                .setParameter("mula", mula)
                .setParameter("tamat", tamat)
                .getResultList();

        List<PnlRow> hasil = new ArrayList<>(), belanja = new ArrayList<>();
        BigDecimal jumHasil = BigDecimal.ZERO, jumBelanja = BigDecimal.ZERO;

        for (Object[] r : rows) {
            BigDecimal baki = (BigDecimal) r[3];
            if ("INCOME".equals(r[2])) {
                BigDecimal v = baki.negate();   // kredit -> positif
                jumHasil = jumHasil.add(v);
                hasil.add(new PnlRow((String) r[0], (String) r[1], v));
            } else {
                jumBelanja = jumBelanja.add(baki);
                belanja.add(new PnlRow((String) r[0], (String) r[1], baki));
            }
        }

        return new ProfitLoss(mula, tamat, hasil, jumHasil, belanja, jumBelanja,
                jumHasil.subtract(jumBelanja), !belanja.isEmpty());
    }

    // ── Senarai Kutipan ──────────────────────────────────────────────

    record CollectionRow(String date, String receiptNo, String accountNo,
                         String issuedTo, String description, String status,
                         String paymentType, String productName,
                         BigDecimal amount) {}

    record CollectionSummary(String label, int count, BigDecimal amount) {}

    record Collection(LocalDate from, LocalDate to, boolean byProduct,
                      boolean monthlyBasis, List<CollectionRow> rows,
                      List<CollectionSummary> summary, BigDecimal total) {}

    /**
     * Senarai Kutipan — dua bentuk, dipilih oleh byProduct.
     *
     * BENTUK A (byProduct=false): satu baris per RESIT, diringkaskan
     * mengikut jenis bayaran. Soalannya "apa yang kita kutip".
     *
     * BENTUK B (byProduct=true): satu baris per ALOKASI produk,
     * diringkaskan mengikut produk. Resit RM101 yang melangsaikan tiga
     * baris invois muncul TIGA kali dengan bahagiannya masing-masing —
     * soalannya "kutipan itu untuk produk apa".
     *
     * MONTHLY BASIS
     *
     * Menapis mengikut tempoh INVOIS yang dilangsaikan, bukan tarikh
     * bayaran. Menjawab "daripada yang kita kutip bulan ini, berapa
     * untuk bil bulan ini dan berapa untuk tunggakan lama".
     *
     * Ia bekerja pada aras ALOKASI walaupun dalam bentuk A: resit
     * RM1,630 mungkin RM400 untuk Ogos dan RM1,230 untuk tunggakan, dan
     * baris menunjukkan BAHAGIAN. Jumlah laporan sengaja tidak sepadan
     * dengan jumlah resit — itu maksud tapisan ini.
     *
     * Resit yang belum dialokasikan (advance) tiada tempoh, jadi ia
     * gugur daripada Monthly Basis secara semula jadi.
     *
     * JENIS BAYARAN DARIPADA JADUAL payment
     *
     * financial_document.payment_type ialah lajur legacy yang TIDAK
     * PERNAH diisi — tiada laluan menulisnya. Kaedah sebenar hidup dalam
     * payment.method, dan laporan membacanya di situ. Menyalinnya ke
     * dokumen akan mencipta salinan kedua yang boleh menyimpang.
     */
    @GetMapping("/collection")
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    Collection collection(@RequestParam LocalDate from,
                          @RequestParam LocalDate to,
                          @RequestParam(defaultValue = "false") boolean byProduct,
                          @RequestParam(defaultValue = "false") boolean monthlyBasis,
                          @RequestParam(required = false) String status,
                          @RequestParam(required = false) String paymentType,
                          @RequestParam(required = false) Long productId) {

        Access.requireAnyRole("melihat senarai kutipan", "SP_ADMIN", "CLERK", "VIEWER");

        List<Object[]> rows = (byProduct || monthlyBasis)
                ? barisAlokasi(from, to, monthlyBasis, status, paymentType, productId, byProduct)
                : barisResit(from, to, status, paymentType);

        List<CollectionRow> items = new ArrayList<>();
        java.util.Map<String, int[]> kiraan = new java.util.LinkedHashMap<>();
        java.util.Map<String, BigDecimal> jumlahKumpulan = new java.util.LinkedHashMap<>();
        BigDecimal jumlah = BigDecimal.ZERO;

        for (Object[] r : rows) {
            BigDecimal amt = (BigDecimal) r[8];
            jumlah = jumlah.add(amt);
            items.add(new CollectionRow(
                    r[0] == null ? null : r[0].toString(),
                    (String) r[1], (String) r[2], (String) r[3], (String) r[4],
                    (String) r[5], (String) r[6], (String) r[7], amt));

            // Bentuk A meringkaskan ikut jenis bayaran; bentuk B ikut produk.
            String kunci = byProduct
                    ? (r[7] == null ? "(tiada produk)" : (String) r[7])
                    : (r[6] == null ? "(tidak dinyatakan)" : (String) r[6]);
            kiraan.computeIfAbsent(kunci, k -> new int[1])[0]++;
            jumlahKumpulan.merge(kunci, amt, BigDecimal::add);
        }

        List<CollectionSummary> ringkasan = new ArrayList<>();
        jumlahKumpulan.forEach((k, v) ->
                ringkasan.add(new CollectionSummary(k, kiraan.get(k)[0], v)));

        return new Collection(from, to, byProduct, monthlyBasis,
                items, ringkasan, jumlah);
    }

    /** Bentuk A — satu baris per resit, amaun penuh. */
    @SuppressWarnings("unchecked")
    private List<Object[]> barisResit(LocalDate from, LocalDate to,
                                      String status, String paymentType) {
        return em.createNativeQuery("""
                SELECT d.doc_date, d.doc_no,
                       COALESCE(a.account_no, ''),
                       COALESCE(NULLIF(d.issued_to_name,''), a.account_name, ''),
                       CONCAT(COALESCE(d.title,''),
                              IFNULL(CONCAT(' ( ', p.remarks, ' )'), '')),
                       d.status, p.method, NULL,
                       (d.amount + d.tax_amount)
                FROM   financial_document d
                LEFT   JOIN account a ON a.id = d.account_id
                LEFT   JOIN payment p ON p.receipt_document_id = d.id
                WHERE  d.sp_code = :sp
                  AND  d.doc_type = 'RECEIPT'
                  AND  d.doc_date BETWEEN :from AND :to
                  AND  (:status IS NULL OR d.status = :status)
                  AND  (:pt IS NULL OR p.method = :pt)
                ORDER  BY d.doc_date, d.id
                """)
                .setParameter("sp", sp())
                .setParameter("from", from).setParameter("to", to)
                .setParameter("status", kosongNull(status))
                .setParameter("pt", kosongNull(paymentType))
                .getResultList();
    }

    /** Bentuk B dan Monthly Basis — satu baris per alokasi. */
    @SuppressWarnings("unchecked")
    private List<Object[]> barisAlokasi(LocalDate from, LocalDate to,
                                        boolean monthlyBasis, String status,
                                        String paymentType, Long productId,
                                        boolean byProduct) {
        return em.createNativeQuery("""
                SELECT d.doc_date, m.credit_doc_no,
                       COALESCE(a.account_no, ''),
                       COALESCE(NULLIF(d.issued_to_name,''), a.account_name, ''),
                       CONCAT(COALESCE(d.title,''),
                              IFNULL(CONCAT(' ( ', p.remarks, ' )'), '')),
                       d.status, p.method,
                       COALESCE(m.product_name, m.line_description, m.debit_title),
                       m.amount
                FROM   account_allocation_match m
                JOIN   financial_document d ON d.id = m.credit_document_id
                LEFT   JOIN account a ON a.id = m.account_id
                LEFT   JOIN payment p ON p.receipt_document_id = d.id
                LEFT   JOIN financial_document_line fdl ON fdl.id = m.debit_document_line_id
                WHERE  m.sp_code = :sp
                  AND  d.doc_type = 'RECEIPT'
                  AND  d.doc_date BETWEEN :from AND :to
                  AND  (:status IS NULL OR d.status = :status)
                  AND  (:pt IS NULL OR p.method = :pt)
                  AND  (:prod IS NULL OR fdl.product_id = :prod)
                  -- Monthly Basis: tempoh INVOIS yang dilangsaikan mesti
                  -- jatuh dalam julat laporan. Bayaran kepada tunggakan
                  -- lama gugur.
                  AND  (:monthly = 0 OR m.debit_period_start BETWEEN :from AND :to)
                ORDER  BY d.doc_date, d.id, m.debit_document_line_id
                """)
                .setParameter("sp", sp())
                .setParameter("from", from).setParameter("to", to)
                .setParameter("status", kosongNull(status))
                .setParameter("pt", kosongNull(paymentType))
                .setParameter("prod", productId)
                .setParameter("monthly", monthlyBasis ? 1 : 0)
                .getResultList();
    }

    private static String kosongNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }

    private String sp() {
        String sp = TenantContext.get();
        if (sp == null || sp.isBlank()) {
            throw new IllegalStateException("Header X-SP-Id diperlukan");
        }
        return sp;
    }
}
