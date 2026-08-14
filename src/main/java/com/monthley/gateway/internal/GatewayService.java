package com.monthley.gateway.internal;

import com.monthley.gateway.api.GatewayPort;
import com.monthley.payment.api.NewPayment;
import com.monthley.payment.api.PaymentMethod;
import com.monthley.payment.api.PaymentPort;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Bayaran dalam talian — mula, dan proses callback.
 *
 * Reka bentuk mengikut ADR 0007, yang ditulis selepas menyiasat 33 anomali
 * merentas 20,885 resit online dalam sistem legacy. Terburuk: RM310,000
 * dicatat untuk bayaran RM310.
 *
 * Empat peraturan yang datang daripada siasatan itu:
 *
 *   1. Amaun resit DIAMBIL daripada gerbang, tidak pernah dikira semula
 *      daripada baki invois. Bayaran ialah fakta luaran — bank sudah
 *      debit. Baki invois ialah keadaan dalaman yang boleh berubah antara
 *      masa bil dicipta dan callback tiba.
 *
 *   2. Handler TIADA keadaan boleh-ubah. Semua konteks dalam parameter
 *      atau pemboleh ubah tempatan.
 *
 *   3. Idempotency pada rujukan. Gerbang mengulang callback sehingga
 *      menerima 200.
 *
 *   4. Setiap respons gerbang disimpan penuh. Bila nombor tidak sepadan
 *      enam bulan kemudian, itu satu-satunya rekod.
 */
@Service
class GatewayService {

    private static final Logger log = LoggerFactory.getLogger(GatewayService.class);

    private final GatewayPort gateway;
    private final PaymentPort payments;
    private final String appUrl;

    @PersistenceContext
    private EntityManager em;

    GatewayService(GatewayPort gateway, PaymentPort payments,
                   @Value("${monthley.app-url}") String appUrl) {
        this.gateway = gateway;
        this.payments = payments;
        this.appUrl = appUrl;
    }

    /**
     * @param amount amaun yang PELANGGAN pilih untuk bayar.
     *
     *               Skrin bayaran online mengikut Manual Payment: pelanggan
     *               menanda invois, dan amaun boleh kurang daripada jumlah
     *               baki — bayaran sebahagian dibenarkan, sama seperti
     *               kerani merekod bayaran separa di kaunter.
     *
     *               Jumlah baki dikira hanya sebagai HAD ATAS. Membenarkan
     *               lebih bermakna mencipta advance melalui gerbang, dan
     *               itu keputusan berasingan yang belum dibuat.
     */
    record StartRequest(Long accountId, List<Long> documentIds, BigDecimal amount) {}
    record StartResult(String ourRef, String billCode, String paymentUrl, BigDecimal amount) {}

    /**
     * Mulakan bayaran.
     *
     * Invois yang dipilih DISIMPAN pada transaksi. Baki boleh berubah
     * antara sekarang dan callback — bayaran manual direkod, penyelarasan
     * dibuat. Tanpa rekod pilihan, alokasi selepas callback terpaksa
     * meneka invois mana yang dimaksudkan.
     */
    @Transactional
    StartResult start(String spCode, StartRequest req, Long payerUserId) {
        if (req.documentIds() == null || req.documentIds().isEmpty()) {
            throw new IllegalStateException("Pilih sekurang-kurangnya satu bil.");
        }

        // Akaun mesti milik pembayar. Tanpa semakan, id akaun yang diteka
        // membolehkan seseorang membayar — dan melihat — bil orang lain.
        List<?> akaunRows = em.createNativeQuery("""
                SELECT a.id, a.account_no, a.account_name, u.full_name, u.email, u.mobile
                FROM   account a
                JOIN   app_user u ON u.id = a.payer_user_id
                WHERE  a.id = :acc AND a.payer_user_id = :uid
                  AND  a.sp_code = :sp AND a.status = 'ACTIVE'
                """)
                .setParameter("acc", req.accountId())
                .setParameter("uid", payerUserId)
                .setParameter("sp", spCode)
                .getResultList();

        if (akaunRows.isEmpty()) {
            throw new IllegalStateException("Akaun bukan milik anda.");
        }
        Object[] akaun = (Object[]) akaunRows.get(0);

        // Baki setiap invois dikira SEKARANG, dan hanya invois yang
        // benar-benar tertunggak diterima.
        BigDecimal jumlah = BigDecimal.ZERO;
        List<Object[]> baris = new ArrayList<>();

        for (Long docId : req.documentIds()) {
            List<?> r = em.createNativeQuery("""
                    SELECT d.id, d.doc_no,
                           (d.amount + d.tax_amount)
                             - COALESCE((SELECT SUM(al.amount) FROM fi_allocation al
                                         WHERE al.debit_document_id = d.id
                                           AND al.status = 'ACTIVE'), 0) AS baki
                    FROM   financial_document d
                    WHERE  d.id = :doc AND d.account_id = :acc
                      AND  d.sp_code = :sp AND d.status <> 'CANCELLED'
                      AND  d.doc_type IN ('INVOICE','DEBIT_NOTE')
                    """)
                    .setParameter("doc", docId)
                    .setParameter("acc", req.accountId())
                    .setParameter("sp", spCode)
                    .getResultList();

            if (r.isEmpty()) {
                throw new IllegalStateException("Bil tidak wujud atau bukan milik akaun ini.");
            }
            Object[] d = (Object[]) r.get(0);
            BigDecimal baki = (BigDecimal) d[2];

            if (baki.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalStateException("Bil " + d[1] + " sudah dijelaskan.");
            }
            jumlah = jumlah.add(baki);
            baris.add(new Object[]{ docId, baki });
        }

        if (jumlah.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("Tiada amaun untuk dibayar.");
        }

        // Amaun yang PELANGGAN pilih. Lalai kepada jumlah penuh bila tidak
        // dinyatakan.
        BigDecimal bayar = req.amount() == null ? jumlah
                : req.amount().setScale(2, java.math.RoundingMode.HALF_UP);

        if (bayar.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("Amaun bayaran mesti lebih daripada sifar.");
        }
        if (bayar.compareTo(jumlah) > 0) {
            throw new IllegalStateException(
                    "Amaun melebihi baki bil yang dipilih (RM" + jumlah + ").");
        }

        // Minimum SP, jika ditetapkan. Gerbang mengenakan yuran tetap pada
        // setiap transaksi, jadi bayaran yang terlalu kecil kos lebih
        // daripada nilainya.
        List<?> minRows = em.createNativeQuery(
                "SELECT min_pymt_amount FROM service_provider WHERE sp_code = :sp")
                .setParameter("sp", spCode).getResultList();
        if (!minRows.isEmpty() && minRows.get(0) != null) {
            BigDecimal min = (BigDecimal) minRows.get(0);
            if (min.compareTo(BigDecimal.ZERO) > 0 && bayar.compareTo(min) < 0) {
                throw new IllegalStateException(
                        "Bayaran minimum ialah RM" + min + ".");
            }
        }

        jumlah = bayar;

        // Rujukan kita — kunci idempotency bila callback diulang.
        String ourRef = "MT" + UUID.randomUUID().toString().replace("-", "")
                                  .substring(0, 18).toUpperCase();

        em.createNativeQuery("""
                INSERT INTO gateway_txn
                  (sp_code, account_id, our_ref, gateway, amount, status, created_at, created_by)
                VALUES (:sp, :acc, :ref, :gw, :amt, 'NEW', NOW(), :by)
                """)
                .setParameter("sp", spCode)
                .setParameter("acc", req.accountId())
                .setParameter("ref", ourRef)
                .setParameter("gw", gateway.code())
                .setParameter("amt", jumlah)
                .setParameter("by", String.valueOf(payerUserId))
                .executeUpdate();

        Long txnId = ((Number) em.createNativeQuery(
                "SELECT id FROM gateway_txn WHERE our_ref = :ref")
                .setParameter("ref", ourRef).getSingleResult()).longValue();

        for (Object[] b : baris) {
            em.createNativeQuery("""
                    INSERT INTO gateway_txn_line (txn_id, document_id, amount)
                    VALUES (:txn, :doc, :amt)
                    """)
                    .setParameter("txn", txnId)
                    .setParameter("doc", b[0])
                    .setParameter("amt", b[1])
                    .executeUpdate();
        }
        em.flush();

        String nama = (String) akaun[3];
        String emel = (String) akaun[4];
        String tel = (String) akaun[5];

        var bill = gateway.createBill(new GatewayPort.NewBill(
                spCode, ourRef, nama, emel, tel, jumlah,
                "Bayaran " + akaun[1],
                appUrl + "/portal/my-accounts?bayar=" + ourRef,
                appUrl.replaceAll("/$", "") + "/api/v1/payments/online/callback"));

        em.createNativeQuery("""
                UPDATE gateway_txn SET bill_code = :bc, status = 'PENDING', updated_at = NOW()
                WHERE  id = :id
                """)
                .setParameter("bc", bill.billCode())
                .setParameter("id", txnId)
                .executeUpdate();

        return new StartResult(ourRef, bill.billCode(), bill.paymentUrl(), jumlah);
    }

    /**
     * Proses callback.
     *
     * ToyyibPay TIDAK menandatangani callbacknya — tiada HMAC, tiada
     * rahsia dikongsi dalam muatan. Sesiapa yang tahu URL ini boleh
     * menghantar POST yang kelihatan seperti bayaran berjaya.
     *
     * Maka muatan callback DIABAIKAN kecuali untuk mencari transaksi.
     * Kebenaran datang daripada memanggil BALIK gerbang dan bertanya sama
     * ada bil ini benar-benar dibayar, dan berapa.
     *
     * REQUIRES_NEW: callback mesti dilog walaupun pemprosesan gagal.
     * Tanpa itu, kegagalan menggulung semula rekod bahawa gerbang pernah
     * memanggil — dan penyiasatan bermula tanpa apa-apa.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void handleCallback(String ourRef, String rawPayload) {
        List<?> rows = em.createNativeQuery("""
                SELECT id, sp_code, account_id, bill_code, status, amount
                FROM   gateway_txn WHERE our_ref = :ref
                """).setParameter("ref", ourRef).getResultList();

        if (rows.isEmpty()) {
            // Rujukan tidak dikenali — callback palsu, atau untuk
            // pemasangan lain yang berkongsi akaun gerbang.
            log.warn("Callback untuk rujukan tidak dikenali: {}", ourRef);
            return;
        }
        Object[] t = (Object[]) rows.get(0);
        Long txnId = ((Number) t[0]).longValue();
        String spCode = (String) t[1];
        Long accountId = ((Number) t[2]).longValue();
        String billCode = (String) t[3];
        String status = (String) t[4];

        // Idempotency: gerbang mengulang callback sehingga menerima 200,
        // dan pengguna boleh memuat semula halaman kembali.
        if ("SUCCESS".equals(status)) {
            log.info("Callback berulang untuk {} — sudah diproses.", ourRef);
            return;
        }

        // KEBENARAN datang dari sini, bukan dari muatan callback.
        var txn = gateway.fetchTransaction(spCode, billCode);

        em.createNativeQuery("""
                UPDATE gateway_txn
                SET    gateway_payload = :raw, gateway_status = :gs, updated_at = NOW()
                WHERE  id = :id
                """)
                .setParameter("raw", potong(rawPayload + " || VERIFY: " + txn.raw(), 60000))
                .setParameter("gs", txn.status())
                .setParameter("id", txnId)
                .executeUpdate();

        if (!txn.paid()) {
            em.createNativeQuery(
                    "UPDATE gateway_txn SET status = 'FAILED', updated_at = NOW() WHERE id = :id")
                    .setParameter("id", txnId).executeUpdate();
            log.info("Bayaran {} tidak berjaya: {}", ourRef, txn.status());
            return;
        }

        // Invois yang pelanggan PILIH, mengikut urutan asal.
        @SuppressWarnings("unchecked")
        List<Number> docIds = em.createNativeQuery(
                "SELECT document_id FROM gateway_txn_line WHERE txn_id = :t ORDER BY id")
                .setParameter("t", txnId).getResultList();

        // ADR 0007 #1: amaun daripada GERBANG, bukan daripada baki invois.
        BigDecimal dibayar = txn.paidAmount();

        var hasil = payments.receivePayment(new NewPayment(
                spCode, accountId, dibayar, PaymentMethod.FPX,
                txn.gatewayRef(),
                docIds.stream().map(Number::longValue).toList(),
                ourRef,                       // idempotency (ADR 0004)
                LocalDate.now(),
                "Bayaran dalam talian"));

        em.createNativeQuery("""
                UPDATE gateway_txn
                SET    status = 'SUCCESS', paid_amount = :amt, gateway_ref = :ref,
                       payment_id = :pid, paid_at = NOW(), updated_at = NOW()
                WHERE  id = :id
                """)
                .setParameter("amt", dibayar)
                .setParameter("ref", txn.gatewayRef())
                .setParameter("pid", hasil.paymentId())
                .setParameter("id", txnId)
                .executeUpdate();

        log.info("Bayaran {} berjaya: RM{} → payment {}", ourRef, dibayar, hasil.paymentId());
    }

    private static String potong(String v, int max) {
        if (v == null) return null;
        return v.length() <= max ? v : v.substring(0, max);
    }
}
