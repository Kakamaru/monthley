package com.monthley.donation.internal;

import com.monthley.document.api.DocumentPort;
import com.monthley.document.api.NewReceipt;
import com.monthley.gateway.api.GatewayPort;
import com.monthley.ledger.api.*;
import com.monthley.notification.api.EmailPort;
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

/**
 * Derma: mulakan bayaran, proses callback, cipta resit.
 *
 * Berbeza daripada bayaran biasa dalam tiga cara (ADR 0020):
 *
 *   TIADA INVOIS. Derma bukan hutang, jadi tiada apa untuk dijelaskan.
 *   Resit dicipta terus, dan ledger kredit ke hasil derma dan bukan ke
 *   akaun belum terima.
 *
 *   TIADA PENGGUNA. Penderma ialah orang luar tanpa akaun. Nama dan e-mel
 *   disimpan pada rekod derma kerana tiada akaun untuk membawanya.
 *
 *   YURAN IKUT KEMPEN. absorb pada kempen mengatasi tetapan SP.
 */
@Service
class DonationService {

    private static final Logger log = LoggerFactory.getLogger(DonationService.class);

    @PersistenceContext
    private EntityManager em;

    private final GatewayPort gateway;
    private final DocumentPort documents;
    private final LedgerPort ledger;
    private final EmailPort email;
    private final com.monthley.document.api.DocumentNumberPort numbers;
    private final com.monthley.document.api.DocumentAccessPort access;
    private final String appUrl;

    DonationService(GatewayPort gateway, DocumentPort documents, LedgerPort ledger,
                    EmailPort email,
                    com.monthley.document.api.DocumentNumberPort numbers,
                    com.monthley.document.api.DocumentAccessPort access,
                    @Value("${monthley.app-url}") String appUrl) {
        this.gateway = gateway;
        this.documents = documents;
        this.ledger = ledger;
        this.email = email;
        this.numbers = numbers;
        this.access = access;
        this.appUrl = appUrl;
    }

    record FeePreview(BigDecimal amount, BigDecimal fee,
                      BigDecimal charged, boolean absorb) {}

    record StartResult(String ourRef, String paymentUrl,
                       BigDecimal amount, BigDecimal fee, BigDecimal charged) {}

    /** Kempen aktif, atau pengecualian. */
    private Object[] kempenAktif(String slug) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT c.id, c.sp_code, c.title, c.min_amount, c.absorb_fee,
                       c.auto_receipt, c.allow_anonymous, c.allow_custom
                FROM   donation_campaign c
                WHERE  c.slug = :slug AND c.status = 'ACTIVE'
                  AND  (c.start_date IS NULL OR c.start_date <= CURDATE())
                  AND  (c.end_date IS NULL OR c.end_date >= CURDATE())
                """)
                .setParameter("slug", slug)
                .getResultList();

        if (rows.isEmpty()) {
            throw new IllegalStateException("Kempen tidak dijumpai atau sudah ditutup.");
        }
        return rows.get(0);
    }

    @Transactional(readOnly = true)
    FeePreview pratontonYuran(String slug, BigDecimal amaun) {
        Object[] k = kempenAktif(slug);
        String spCode = (String) k[1];

        BigDecimal yuran = kadarYuran(spCode);
        boolean serap = serapYuran(spCode, k[4]);

        return new FeePreview(amaun, yuran,
                serap ? amaun : amaun.add(yuran), serap);
    }

    /**
     * Mulakan derma.
     *
     * Amaun disemak terhadap minimum kempen SEBELUM bil dicipta —
     * menolaknya semasa callback bermakna wang sudah diterima.
     */
    @Transactional
    StartResult mulakan(String slug, PublicDonationController.DonateBody b) {
        Object[] k = kempenAktif(slug);
        Long kempenId = ((Number) k[0]).longValue();
        String spCode = (String) k[1];
        String tajuk = (String) k[2];
        BigDecimal min = (BigDecimal) k[3];
        boolean anonBenar = bool(k[6]);

        BigDecimal amaun = b.amount();
        if (amaun == null || amaun.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("Masukkan jumlah derma.");
        }
        if (min != null && min.signum() > 0 && amaun.compareTo(min) < 0) {
            throw new IllegalStateException(
                    "Jumlah minimum ialah RM" + min.toPlainString() + ".");
        }

        boolean anon = Boolean.TRUE.equals(b.anonymous());
        if (anon && !anonBenar) {
            throw new IllegalStateException(
                    "Kempen ini memerlukan nama penderma.");
        }

        BigDecimal yuran = kadarYuran(spCode);
        boolean serap = serapYuran(spCode, k[4]);
        BigDecimal dicaj = serap ? amaun : amaun.add(yuran);

        // Rujukan sama bentuk seperti bayaran lain: sp_code + base36,
        // supaya penyata bank boleh dipadankan tanpa menyoal DB.
        String ourRef = spCode + base36(numbers.nextValue(spCode, "GATEWAY_REF"));

        em.createNativeQuery("""
                INSERT INTO donation
                  (sp_code, campaign_id, donor_name, donor_email, donor_phone,
                   donor_account, anonymous, amount, fee_amount, status,
                   our_ref, created_at, updated_at, version)
                VALUES (:sp, :kempen, :nama, :emel, :tel, :akaun, :anon,
                        :amt, :fee, 'NEW', :ref, NOW(), NOW(), 0)
                """)
                .setParameter("sp", spCode)
                .setParameter("kempen", kempenId)
                .setParameter("nama", anon ? null : kosongJadiNull(b.donorName()))
                .setParameter("emel", kosongJadiNull(b.donorEmail()))
                .setParameter("tel", kosongJadiNull(b.donorPhone()))
                .setParameter("akaun", kosongJadiNull(b.donorAccount()))
                .setParameter("anon", anon ? 1 : 0)
                .setParameter("amt", amaun)
                .setParameter("fee", yuran)
                .setParameter("ref", ourRef)
                .executeUpdate();
        em.flush();

        String namaPenderma = anon ? "Penderma" :
                (kosongJadiNull(b.donorName()) == null ? "Penderma" : b.donorName().trim());

        var bill = gateway.createBill(new GatewayPort.NewBill(
                spCode, ourRef, namaPenderma,
                kosongJadiNull(b.donorEmail()),
                kosongJadiNull(b.donorPhone()),
                dicaj,
                tajuk,
                appUrl + "/derma/" + slug + "?ref=" + ourRef,
                appUrl.replaceAll("/$", "") + "/api/v1/pub/donations/callback"));

        em.createNativeQuery("""
                UPDATE donation SET bill_code = :bc, status = 'PENDING',
                       updated_at = NOW()
                WHERE  our_ref = :ref
                """)
                .setParameter("bc", bill.billCode())
                .setParameter("ref", ourRef)
                .executeUpdate();

        return new StartResult(ourRef, bill.paymentUrl(), amaun, yuran, dicaj);
    }

    /**
     * Callback gerbang.
     *
     * REQUIRES_NEW supaya callback dilog walaupun pemprosesan gagal — sama
     * seperti bayaran biasa. Muatan TIDAK dipercayai; kebenaran datang
     * daripada memanggil balik gerbang.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void handleCallback(String ourRef, String rawPayload) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT d.id, d.sp_code, d.campaign_id, d.bill_code, d.status,
                       d.amount, d.fee_amount, d.donor_name, d.donor_email,
                       c.title, c.auto_receipt, c.absorb_fee
                FROM   donation d
                JOIN   donation_campaign c ON c.id = d.campaign_id
                WHERE  d.our_ref = :ref
                """)
                .setParameter("ref", ourRef)
                .getResultList();

        if (rows.isEmpty()) {
            log.warn("Callback derma untuk rujukan tidak dikenali: {}", ourRef);
            return;
        }

        Object[] d = rows.get(0);
        Long id = ((Number) d[0]).longValue();
        String spCode = (String) d[1];
        String billCode = (String) d[3];
        String status = (String) d[4];
        BigDecimal amaun = (BigDecimal) d[5];
        BigDecimal yuran = (BigDecimal) d[6] == null ? BigDecimal.ZERO : (BigDecimal) d[6];
        String namaPenderma = (String) d[7];
        String emel = (String) d[8];
        String tajuk = (String) d[9];
        boolean autoResit = bool(d[10]);

        if ("SUCCESS".equals(status)) {
            log.info("Callback derma {} sudah diproses.", ourRef);
            return;
        }

        var txn = gateway.fetchTransaction(spCode, billCode);

        em.createNativeQuery("""
                UPDATE donation SET gateway_payload = :raw, updated_at = NOW()
                WHERE  id = :id
                """)
                .setParameter("raw", rawPayload)
                .setParameter("id", id)
                .executeUpdate();

        if (!txn.paid()) {
            em.createNativeQuery(
                    "UPDATE donation SET status = 'FAILED', updated_at = NOW() WHERE id = :id")
                    .setParameter("id", id)
                    .executeUpdate();
            log.info("Derma {} tidak berjaya.", ourRef);
            return;
        }

        // Amaun daripada GERBANG (ADR 0007 #1). Yuran ditolak apabila
        // penderma yang membayarnya — kalau tidak resit menunjukkan lebih
        // daripada yang sampai kepada tujuan.
        BigDecimal diterima = txn.paidAmount();
        boolean serap = serapYuran(spCode, d[11]);
        BigDecimal untukResit = serap ? diterima : diterima.subtract(yuran);

        if (untukResit.compareTo(BigDecimal.ZERO) <= 0) {
            log.error("Derma {} : amaun selepas yuran bukan positif "
                      + "(diterima {}, yuran {}).", ourRef, diterima, yuran);
            return;
        }

        Long akaunDerma = akaunDerma(spCode);

        // RESIT SAHAJA — tiada invois (ADR 0020 #2).
        Long resitId = documents.createReceipt(new NewReceipt(
                spCode, akaunDerma, LocalDate.now(), tajuk, untukResit));

        // Ledger: debit bank, kredit HASIL DERMA. Tiada akaun belum terima
        // kerana tiada hutang yang diselesaikan.
        List<PostingLine> pl = new ArrayList<>();
        pl.add(PostingLine.debit(GlAccounts.BANK, untukResit, null));
        pl.add(PostingLine.credit(GlAccounts.DONATION_INCOME, untukResit, null));

        Long journalId = ledger.post(new PostingRequest(
                spCode, LocalDate.now(), SourceType.DONATION, resitId,
                "Derma: " + tajuk, pl, null));

        em.createNativeQuery("""
                UPDATE donation
                SET    status = 'SUCCESS', gateway_ref = :gref,
                       receipt_document_id = :resit, paid_at = NOW(),
                       updated_at = NOW()
                WHERE  id = :id
                """)
                .setParameter("gref", txn.gatewayRef())
                .setParameter("resit", resitId)
                .setParameter("id", id)
                .executeUpdate();

        log.info("Derma {} berjaya: diterima RM{}, yuran RM{}, resit RM{} (jurnal {})",
                 ourRef, diterima, yuran, untukResit, journalId);

        if (autoResit && emel != null && !emel.isBlank()) {
            hantarResit(spCode, resitId, namaPenderma, tajuk, untukResit, emel);
        }
    }

    /**
     * Resit kepada penderma.
     *
     * Gagal secara senyap: wang sudah diterima dan resit sudah wujud.
     * E-mel yang gagal TIDAK boleh menggagalkan callback — gerbang akan
     * mengulanginya.
     */
    private void hantarResit(String spCode, Long resitId, String nama,
                             String tajuk, BigDecimal amaun, String emel) {
        try {
            String token = access.tokenFor(spCode, resitId,
                    com.monthley.document.api.DocumentType.RECEIPT);

            @SuppressWarnings("unchecked")
            List<Object[]> rows = em.createNativeQuery("""
                    SELECT s.name, d.doc_no
                    FROM   financial_document d
                    JOIN   service_provider s ON s.sp_code = d.sp_code
                    WHERE  d.id = :id
                    """).setParameter("id", resitId).getResultList();
            if (rows.isEmpty()) return;

            Object[] r = rows.get(0);
            email.sendReceipt(emel,
                    nama == null || nama.isBlank() ? "Penderma" : nama,
                    (String) r[0],
                    (String) r[1],
                    // Akaun kosong: derma bukan milik akaun pelanggan.
                    null, tajuk,
                    "MYR " + amaun.toPlainString(),
                    LocalDate.now().toString(),
                    appUrl + "/api/v1/pub/receipts/" + token);

        } catch (RuntimeException e) {
            log.error("Gagal hantar resit derma {}: {}", resitId, e.getMessage());
        }
    }

    /** Akaun DONATION SP — dicipta oleh V80. */
    private Long akaunDerma(String spCode) {
        @SuppressWarnings("unchecked")
        List<Object> r = em.createNativeQuery("""
                SELECT id FROM account
                WHERE  sp_code = :sp AND account_type = 'DONATION'
                LIMIT  1
                """).setParameter("sp", spCode).getResultList();

        if (r.isEmpty()) {
            throw new IllegalStateException(
                    "Akaun kutipan derma tiada untuk " + spCode + ".");
        }
        return ((Number) r.get(0)).longValue();
    }

    /**
     * Kadar yuran derma.
     *
     * Satu derma = satu transaksi = rate_single. Tiada konsep 'beberapa
     * invois' di sini.
     */
    private BigDecimal kadarYuran(String spCode) {
        @SuppressWarnings("unchecked")
        List<Object> r = em.createNativeQuery(
                "SELECT rate_single FROM sp_payment_setting WHERE sp_code = :sp")
                .setParameter("sp", spCode).getResultList();
        if (r.isEmpty() || r.get(0) == null) return BigDecimal.ZERO;
        return (BigDecimal) r.get(0);
    }

    /**
     * Siapa menanggung yuran.
     *
     * Kempen MENGATASI tetapan SP (ADR 0020 #3): SP boleh menyerap yuran
     * untuk bil bulanan tetapi meminta penderma menanggungnya untuk derma.
     * NULL pada kempen bermakna warisi SP.
     */
    private boolean serapYuran(String spCode, Object absorbKempen) {
        if (absorbKempen != null) return bool(absorbKempen);

        @SuppressWarnings("unchecked")
        List<Object> r = em.createNativeQuery(
                "SELECT absorb FROM sp_payment_setting WHERE sp_code = :sp")
                .setParameter("sp", spCode).getResultList();
        if (r.isEmpty() || r.get(0) == null) return false;
        return bool(r.get(0));
    }

    private static String base36(long v) {
        return Long.toString(v, 36).toUpperCase();
    }

    private static String kosongJadiNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    private static boolean bool(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean b) return b;
        return ((Number) o).intValue() != 0;
    }
}
