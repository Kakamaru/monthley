package com.monthley.gateway.internal;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bayaran dalam talian — sisi pelanggan dan callback gerbang.
 *
 *   GET  /api/v1/payments/online/outstanding?accountId=
 *   POST /api/v1/payments/online/start
 *   POST /api/v1/payments/online/callback     (gerbang; TIADA pengesahan)
 *   GET  /api/v1/payments/online/status/{ourRef}
 *
 * Laluan /online kerana ManualPaymentController sudah memegang
 * /api/v1/payments/outstanding — dan itu benda berbeza: kerani melihat
 * bil merentas akaun, pelanggan melihat akaunnya sendiri sahaja.
 */
@RestController
@RequestMapping("/api/v1/payments/online")
class PaymentGatewayController {

    private static final Logger log = LoggerFactory.getLogger(PaymentGatewayController.class);

    private final GatewayService service;

    @PersistenceContext
    private EntityManager em;

    PaymentGatewayController(GatewayService service) {
        this.service = service;
    }

    record Outstanding(Long documentId, String docNo, String period,
                       LocalDate dueDate, BigDecimal total, BigDecimal balance,
                       boolean overdue) {}

    /** Bentuk sama seperti ManualPaymentRequest — invois dipilih + amaun. */
    record StartBody(Long accountId, List<Long> documentIds, BigDecimal amount) {}

    // ---------- Merentas akaun (ADR 0019) ----------

    record OutAcct(Long accountId, String accountNo, String accountName,
                   String spCode, String spName, BigDecimal jumlah,
                   List<Outstanding> bil) {}

    /**
     * Semua akaun pelanggan dengan bil tertunggak, dikumpulkan mengikut SP.
     *
     * Satu panggilan dan bukan satu setiap akaun: skrin perlu tahu SP mana
     * setiap akaun tergolong SEBELUM pelanggan menanda apa-apa, kerana
     * menanda akaun pertama mengunci pilihan kepada SP itu (ADR 0018).
     * Memuatkannya satu per satu bermakna skrin berkelip semasa senarai
     * terbina.
     */
    @GetMapping("/outstanding-all")
    @SuppressWarnings("unchecked")
    List<OutAcct> outstandingAll() {
        Long uid = uid();

        List<Object[]> rows = em.createNativeQuery("""
                SELECT x.account_id, x.account_no, x.account_name,
                       x.sp_code, x.sp_name,
                       x.id, x.doc_no, x.period, x.due_date, x.total, x.baki
                FROM (
                    SELECT a.id AS account_id, a.account_no, a.account_name,
                           a.sp_code, s.name AS sp_name,
                           d.id, d.doc_no, p.name_ AS period, d.due_date,
                           (d.amount + d.tax_amount) AS total,
                           (d.amount + d.tax_amount)
                             - COALESCE((SELECT SUM(al.amount) FROM fi_allocation al
                                         WHERE al.debit_document_id = d.id
                                           AND al.status = 'ACTIVE'), 0) AS baki
                    FROM   financial_document d
                    JOIN   account a ON a.id = d.account_id
                    JOIN   service_provider s ON s.sp_code = a.sp_code
                    LEFT   JOIN fi_period p ON p.period_id = d.period_id
                    WHERE  a.payer_user_id = :uid
                      AND  a.status = 'ACTIVE'
                      AND  d.status <> 'CANCELLED'
                      AND  d.doc_type IN ('INVOICE','DEBIT_NOTE')
                ) x
                WHERE  x.baki > 0
                ORDER  BY x.sp_code, x.account_no, x.due_date, x.id
                """)
                .setParameter("uid", uid)
                .getResultList();

        LocalDate hariIni = LocalDate.now();

        // LinkedHashMap mengekalkan susunan SQL — akaun kekal berkumpul
        // mengikut SP tanpa penyusunan kedua di klien.
        Map<Long, OutAcct> ikutAkaun = new LinkedHashMap<>();

        for (Object[] r : rows) {
            Long accId = ((Number) r[0]).longValue();
            LocalDate due = toDate(r[8]);

            Outstanding bil = new Outstanding(
                    ((Number) r[5]).longValue(), (String) r[6], (String) r[7],
                    due, (BigDecimal) r[9], (BigDecimal) r[10],
                    due != null && due.isBefore(hariIni));

            OutAcct akaun = ikutAkaun.get(accId);
            if (akaun == null) {
                List<Outstanding> senarai = new ArrayList<>();
                senarai.add(bil);
                ikutAkaun.put(accId, new OutAcct(
                        accId, (String) r[1], (String) r[2],
                        (String) r[3], (String) r[4], bil.balance(), senarai));
            } else {
                akaun.bil().add(bil);
                ikutAkaun.put(accId, new OutAcct(
                        akaun.accountId(), akaun.accountNo(), akaun.accountName(),
                        akaun.spCode(), akaun.spName(),
                        akaun.jumlah().add(bil.balance()), akaun.bil()));
            }
        }

        return List.copyOf(ikutAkaun.values());
    }

    /** Bil tertunggak bagi akaun pelanggan sendiri. */
    @GetMapping("/outstanding")
    @SuppressWarnings("unchecked")
    List<Outstanding> outstanding(@RequestParam Long accountId) {
        Long uid = uid();

        // Subquery, bukan HAVING: MySQL 8 berjalan dengan ONLY_FULL_GROUP_BY,
        // yang menolak HAVING tanpa GROUP BY. Ini juga lebih jelas —
        // penapis pada baki ialah penapis, bukan syarat kumpulan.
        List<Object[]> rows = em.createNativeQuery("""
                SELECT x.id, x.doc_no, x.period, x.due_date, x.total, x.baki
                FROM (
                    SELECT d.id, d.doc_no, p.name_ AS period, d.due_date,
                           (d.amount + d.tax_amount) AS total,
                           (d.amount + d.tax_amount)
                             - COALESCE((SELECT SUM(al.amount) FROM fi_allocation al
                                         WHERE al.debit_document_id = d.id
                                           AND al.status = 'ACTIVE'), 0) AS baki
                    FROM   financial_document d
                    JOIN   account a ON a.id = d.account_id
                    -- period_id ialah FK ke fi_period; name_ memberi label
                    -- yang boleh dibaca ('Ogos 2026'), bukan nombor id.
                    LEFT   JOIN fi_period p ON p.period_id = d.period_id
                    WHERE  a.id = :acc AND a.payer_user_id = :uid
                      AND  d.status <> 'CANCELLED'
                      AND  d.doc_type IN ('INVOICE','DEBIT_NOTE')
                ) x
                WHERE  x.baki > 0
                ORDER  BY x.due_date, x.id
                """)
                .setParameter("acc", accountId)
                .setParameter("uid", uid)
                .getResultList();

        LocalDate hariIni = LocalDate.now();
        List<Outstanding> out = new ArrayList<>();
        for (Object[] r : rows) {
            LocalDate due = toDate(r[3]);
            out.add(new Outstanding(
                    ((Number) r[0]).longValue(), (String) r[1], (String) r[2],
                    due, (BigDecimal) r[4], (BigDecimal) r[5],
                    due != null && due.isBefore(hariIni)));
        }
        return out;
    }

    /**
     * Pratonton caj transaksi.
     *
     * Pemilikan akaun disemak sama seperti /start — pratonton mendedahkan
     * tetapan yuran SP, dan itu bukan maklumat untuk sesiapa yang bukan
     * pelanggan SP tersebut.
     */
    @PostMapping("/preview")
    ResponseEntity<?> preview(@RequestBody StartBody body) {
        Long uid = uid();

        List<?> r = em.createNativeQuery(
                "SELECT sp_code FROM account WHERE id = :a AND payer_user_id = :uid")
                .setParameter("a", body.accountId()).setParameter("uid", uid)
                .getResultList();
        if (r.isEmpty()) {
            throw new IllegalStateException("Akaun bukan milik anda.");
        }
        String spCode = (String) r.get(0);

        BigDecimal amaun = body.amount() == null ? BigDecimal.ZERO : body.amount();

        var p = service.previewFee(spCode, body.accountId(),
                                   body.documentIds(), amaun);
        return ResponseEntity.ok(Map.of(
                "amount", p.amount(),
                "fee", p.fee(),
                "charged", p.charged(),
                "absorb", p.absorb()));
    }

    record StartMultiBody(List<Long> documentIds, BigDecimal amount) {}

    /**
     * Mulakan bayaran merentas beberapa akaun (ADR 0019).
     *
     * Tiada accountId: invois menentukan akaun, dan pemilikan disahkan
     * terhadap pengguna. Menghantar senarai akaun BERSAMA senarai invois
     * bermakna dua sumber kebenaran yang boleh bercanggah.
     */
    @PostMapping("/start-multi")
    ResponseEntity<?> startMulti(@RequestBody StartMultiBody body) {
        var hasil = service.startMulti(uid(), body.documentIds(), body.amount());
        return ResponseEntity.ok(Map.of(
                "ourRef", hasil.ourRef(),
                "billCode", hasil.billCode(),
                "paymentUrl", hasil.paymentUrl(),
                "amount", hasil.amount(),
                "fee", hasil.fee(),
                "charged", hasil.charged()));
    }

    /** Pratonton caj bagi bayaran merentas akaun. */
    @PostMapping("/preview-multi")
    ResponseEntity<?> previewMulti(@RequestBody StartMultiBody body) {
        var p = service.previewMulti(uid(), body.documentIds(), body.amount());
        return ResponseEntity.ok(Map.of(
                "amount", p.amount(),
                "fee", p.fee(),
                "charged", p.charged(),
                "absorb", p.absorb()));
    }

    @PostMapping("/start")
    ResponseEntity<?> start(@RequestBody StartBody body) {
        Long uid = uid();

        // SP ditentukan daripada AKAUN, bukan header: pelanggan boleh
        // membayar beberapa SP dan tidak mempunyai SP 'semasa'.
        List<?> r = em.createNativeQuery(
                "SELECT sp_code FROM account WHERE id = :a AND payer_user_id = :uid")
                .setParameter("a", body.accountId()).setParameter("uid", uid)
                .getResultList();
        if (r.isEmpty()) {
            throw new IllegalStateException("Akaun bukan milik anda.");
        }
        String spCode = (String) r.get(0);

        var hasil = service.start(spCode,
                new GatewayService.StartRequest(
                        body.accountId(), body.documentIds(), body.amount()), uid);

        // amount, fee, dan charged dipulangkan BERASINGAN supaya skrin
        // boleh menunjukkan pecahan kepada pelanggan sebelum mereka
        // meneruskan — 'RM100.00 + RM1.50 yuran = RM101.50'.
        return ResponseEntity.ok(Map.of(
                "ourRef", hasil.ourRef(),
                "billCode", hasil.billCode(),
                "paymentUrl", hasil.paymentUrl(),
                "amount", hasil.amount(),
                "fee", hasil.fee(),
                "charged", hasil.charged()));
    }

    /**
     * Callback gerbang — server ke server.
     *
     * TIADA pengesahan pada endpoint ini kerana gerbang tidak boleh log
     * masuk. Muatan juga tidak dipercayai: ToyyibPay tidak menandatangani
     * callbacknya, jadi sesiapa yang tahu URL ini boleh menghantar POST
     * yang kelihatan seperti bayaran berjaya.
     *
     * Muatan hanya digunakan untuk MENCARI transaksi. Sama ada bayaran
     * benar-benar berlaku ditentukan dengan memanggil balik gerbang.
     *
     * SENTIASA memulangkan 200. Gerbang mengulang callback sehingga
     * menerima 200, dan mengulang sesuatu yang kita sudah proses hanya
     * menambah beban — kegagalan dilog dan disiasat, bukan diulang
     * selama-lamanya.
     */
    @PostMapping("/callback")
    ResponseEntity<String> callback(@RequestParam Map<String, String> form,
                                    @RequestBody(required = false) String raw) {
        // ToyyibPay menghantar order_id = billExternalReferenceNo kita.
        String ourRef = form.get("order_id");
        if (ourRef == null || ourRef.isBlank()) {
            log.warn("Callback tanpa order_id: {}", form);
            return ResponseEntity.ok("OK");
        }

        try {
            service.handleCallback(ourRef, form.toString() + (raw == null ? "" : " | " + raw));
        } catch (Exception e) {
            // Dilog, bukan dilempar. Gerbang tidak boleh berbuat apa-apa
            // dengan ralat kita, dan mengulang callback tidak akan
            // membetulkan pepijat.
            log.error("Gagal memproses callback {}: {}", ourRef, e.getMessage(), e);
        }
        return ResponseEntity.ok("OK");
    }

    /** Status bayaran — pelayar menyemak selepas kembali dari gerbang. */
    @GetMapping("/status/{ourRef}")
    Map<String, Object> status(@PathVariable String ourRef) {
        Long uid = uid();

        List<?> rows = em.createNativeQuery("""
                SELECT t.status, t.amount, t.paid_amount, t.gateway_ref, t.payment_id
                FROM   gateway_txn t
                JOIN   account a ON a.id = t.account_id
                WHERE  t.our_ref = :ref AND a.payer_user_id = :uid
                """).setParameter("ref", ourRef).setParameter("uid", uid)
                .getResultList();

        if (rows.isEmpty()) {
            throw new IllegalStateException("Transaksi tidak dijumpai.");
        }
        Object[] t = (Object[]) rows.get(0);

        Map<String, Object> out = new java.util.HashMap<>();
        out.put("status", t[0]);
        out.put("amount", t[1]);
        out.put("paidAmount", t[2]);
        out.put("gatewayRef", t[3]);
        out.put("paymentId", t[4] == null ? null : ((Number) t[4]).longValue());
        return out;
    }

    private static LocalDate toDate(Object v) {
        if (v == null) return null;
        if (v instanceof LocalDate d) return d;
        if (v instanceof java.sql.Date d) return d.toLocalDate();
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
