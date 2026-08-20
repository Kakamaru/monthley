package com.monthley.gateway.internal;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Reconciliation gerbang — ADR 0007 #4.
 *
 * Dua tujuan, satu sumber data:
 *
 *   PENGAGIHAN  Wang tiba di akaun Rapidevelop sebagai satu jumlah. SP mana
 *               berapa? Ringkasan per SP menjawabnya, dan lajur BERSIH
 *               ialah yang sepadan dengan penyata bank apabila SP menyerap
 *               yuran.
 *
 *   PERCANGGAHAN  Bayaran gerbang BERJAYA tanpa resit bermakna pelanggan
 *                 sudah membayar tetapi invois masih terbuka. Dalam legacy,
 *                 kes seperti ini hanya ditemui apabila pelanggan mengadu.
 *
 * Laporan ini merentas semua SP, dan SP tidak sepatutnya melihat kutipan SP
 * lain. Laluan /api/v1/platform/** sudah disekat kepada SUPERADMIN dalam
 * SecurityConfig, jadi tiada semakan tambahan di sini.
 */
@RestController
@RequestMapping("/api/v1/platform/reconciliation")
class ReconciliationController {

    @PersistenceContext
    private EntityManager em;

    /**
     * @param gross   jumlah terhadap invois — ini yang menjadi resit
     * @param fee     caj transaksi
     * @param net     gross - fee bila SP menyerap; gross bila tidak.
     *                Inilah yang tiba di akaun bank.
     * @param absorb  sama ada SP menyerap yuran
     */
    record SpSummary(String spCode, String spName, boolean absorb,
                     long bilangan, BigDecimal gross, BigDecimal fee,
                     BigDecimal net) {}

    record TxnRow(Long id, String spCode, String spName, String ourRef,
                  String gatewayRef, String gateway, BigDecimal amount,
                  BigDecimal fee, BigDecimal paidAmount, String status,
                  Long paymentId, String receiptNo, String paidAt,
                  String masalah) {}

    record ReconResult(List<SpSummary> ringkasan, List<TxnRow> transaksi,
                       long bilMasalah) {}

    /**
     * Julat lalai: tujuh hari lepas.
     *
     * Reconciliation dijalankan harian, tetapi hujung minggu bermakna
     * seseorang membuka laporan pada Isnin untuk tiga hari sekaligus.
     */
    @GetMapping
    @Transactional(readOnly = true)
    ReconResult recon(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dari,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hingga,
            @RequestParam(required = false) String spCode) {

        LocalDate d1 = dari   != null ? dari   : LocalDate.now().minusDays(7);
        LocalDate d2 = hingga != null ? hingga : LocalDate.now();

        return new ReconResult(ringkasan(d1, d2, spCode),
                               transaksi(d1, d2, spCode),
                               bilMasalah(d1, d2, spCode));
    }

    /**
     * Ringkasan per SP — bayaran BERJAYA sahaja.
     *
     * Transaksi gagal dan tertunggak tidak membawa wang, jadi
     * memasukkannya dalam jumlah pengagihan bermakna angka tidak sepadan
     * dengan penyata bank.
     */
    private List<SpSummary> ringkasan(LocalDate d1, LocalDate d2, String sp) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT g.sp_code, s.name, COALESCE(p.absorb, 0),
                       COUNT(*),
                       COALESCE(SUM(g.amount), 0),
                       COALESCE(SUM(g.fee_amount), 0)
                FROM   gateway_txn g
                JOIN   service_provider s ON s.sp_code = g.sp_code
                LEFT   JOIN sp_payment_setting p ON p.sp_code = g.sp_code
                WHERE  g.status = 'SUCCESS'
                  AND  DATE(g.paid_at) BETWEEN :d1 AND :d2
                  AND  (:sp IS NULL OR g.sp_code = :sp)
                GROUP  BY g.sp_code, s.name, p.absorb
                ORDER  BY g.sp_code
                """)
                .setParameter("d1", d1)
                .setParameter("d2", d2)
                .setParameter("sp", sp)
                .getResultList();

        return rows.stream().map(r -> {
            boolean absorb = ((Number) r[2]).intValue() != 0;
            BigDecimal gross = (BigDecimal) r[4];
            BigDecimal fee   = (BigDecimal) r[5];

            // Bila SP menyerap, yuran dipotong di pihak gerbang dan yang
            // tiba di bank ialah gross - fee. Bila tidak, pelanggan sudah
            // membayar yuran secara berasingan dan SP menerima gross penuh.
            BigDecimal net = absorb ? gross.subtract(fee) : gross;

            return new SpSummary((String) r[0], (String) r[1], absorb,
                                 ((Number) r[3]).longValue(), gross, fee, net);
        }).toList();
    }

    /**
     * Senarai transaksi dengan masalah dikenal pasti.
     *
     * `masalah` dikira di SQL dan bukan di klien: laporan yang dieksport
     * mesti membawa penilaian yang sama seperti skrin.
     */
    private List<TxnRow> transaksi(LocalDate d1, LocalDate d2, String sp) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT g.id, g.sp_code, s.name, g.our_ref, g.gateway_ref, g.gateway,
                       g.amount, g.fee_amount, g.paid_amount, g.status,
                       g.payment_id, r.doc_no,
                       DATE_FORMAT(g.paid_at, '%Y-%m-%d %H:%i'),
                       CASE
                         WHEN g.status = 'SUCCESS' AND g.payment_id IS NULL
                           THEN 'BAYARAN TANPA RESIT'
                         WHEN g.status = 'SUCCESS' AND g.paid_amount IS NULL
                           THEN 'AMAUN TIDAK DIREKOD'
                         WHEN g.status = 'SUCCESS' AND g.paid_amount <> g.amount + COALESCE(g.fee_amount, 0)
                              AND g.paid_amount <> g.amount
                           THEN 'AMAUN TIDAK PADAN'
                         ELSE NULL
                       END
                FROM   gateway_txn g
                JOIN   service_provider s ON s.sp_code = g.sp_code
                LEFT   JOIN financial_document r ON r.id = (
                           SELECT p.receipt_document_id FROM payment p
                           WHERE  p.id = g.payment_id)
                WHERE  DATE(COALESCE(g.paid_at, g.created_at)) BETWEEN :d1 AND :d2
                  AND  (:sp IS NULL OR g.sp_code = :sp)
                ORDER  BY g.id DESC
                """)
                .setParameter("d1", d1)
                .setParameter("d2", d2)
                .setParameter("sp", sp)
                .getResultList();

        return rows.stream().map(r -> new TxnRow(
                ((Number) r[0]).longValue(), (String) r[1], (String) r[2],
                (String) r[3], (String) r[4], (String) r[5],
                (BigDecimal) r[6], (BigDecimal) r[7], (BigDecimal) r[8],
                (String) r[9],
                r[10] == null ? null : ((Number) r[10]).longValue(),
                (String) r[11], (String) r[12], (String) r[13])).toList();
    }

    private long bilMasalah(LocalDate d1, LocalDate d2, String sp) {
        Object n = em.createNativeQuery("""
                SELECT COUNT(*) FROM gateway_txn g
                WHERE  g.status = 'SUCCESS'
                  AND  DATE(g.paid_at) BETWEEN :d1 AND :d2
                  AND  (:sp IS NULL OR g.sp_code = :sp)
                  AND  (g.payment_id IS NULL OR g.paid_amount IS NULL)
                """)
                .setParameter("d1", d1)
                .setParameter("d2", d2)
                .setParameter("sp", sp)
                .getSingleResult();
        return ((Number) n).longValue();
    }
}
