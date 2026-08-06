package com.monthley.billing.internal;

import com.monthley.document.api.DocumentPort;
import com.monthley.document.api.NewDocumentLine;
import com.monthley.document.api.NewInvoice;
import com.monthley.ledger.api.GlAccounts;
import com.monthley.ledger.api.LedgerPort;
import com.monthley.ledger.api.PostingLine;
import com.monthley.ledger.api.PostingRequest;
import com.monthley.ledger.api.SourceType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Invois kepada orang yang BUKAN pelanggan berdaftar.
 *
 * Caj clamp kepada pemandu luar; jualan buku pada pameran sekolah.
 * Penerima menerima e-mel dengan pautan bayaran dan tidak akan kembali.
 *
 * BERASINGAN daripada createAndPost dan bukan cabang di dalamnya. Tiga
 * perbezaan menjadikan cabang itu sukar dibaca: butiran penerima duduk
 * pada dokumen, tiada advance digunakan, dan baris datang daripada
 * produk yang dipilih kerani bukan daripada langganan.
 *
 * ADVANCE TIDAK DIGUNAKAN, dan itu bukan kelalaian. Semua invois adhoc
 * berkongsi satu akaun ADHOC-SALES (V50), jadi advance di situ ialah
 * duit orang lain. Memanggil applyAdvance akan menutup invois pembeli A
 * dengan lebihan pembeli B.
 */
@Service
public class AdhocInvoiceService {

    private final DocumentPort documents;
    private final LedgerPort ledger;
    private final com.monthley.tenancy.api.BillingSettingsPort settings;

    @PersistenceContext
    private EntityManager em;

    AdhocInvoiceService(DocumentPort documents, LedgerPort ledger,
                        com.monthley.tenancy.api.BillingSettingsPort settings) {
        this.documents = documents;
        this.ledger = ledger;
        this.settings = settings;
    }

    public record AdhocLine(long productId, BigDecimal quantity) {}

    public record Request(
            Long accountId,          // null = orang luar
            String issuedToName,
            String issuedToEmail,
            String issuedToPhone,
            long periodId,
            LocalDate dueDate,
            String remarks,
            List<AdhocLine> lines) {}

    public record Result(Long documentId, String docNo, BigDecimal total) {}

    @Transactional
    public Result create(String spCode, Request req) {
        if (req.lines() == null || req.lines().isEmpty()) {
            throw new IllegalStateException("Sekurang-kurangnya satu produk diperlukan.");
        }
        if (req.issuedToName() == null || req.issuedToName().isBlank()) {
            throw new IllegalStateException("Nama penerima diperlukan.");
        }

        // Akaun: yang dipilih kerani, atau akaun ADHOC-SALES SP.
        Long accountId = req.accountId() != null
                ? sahkanAkaun(spCode, req.accountId())
                : akaunAdhoc(spCode);

        LocalDate docDate = LocalDate.now();
        List<NewDocumentLine> docLines = new ArrayList<>();
        BigDecimal jumlah = BigDecimal.ZERO;
        BigDecimal cukai = BigDecimal.ZERO;

        // Amaun dikira SEKALI dan digunakan oleh dokumen DAN ledger.
        // Percubaan pertama mengambil produk dua kali dan mengira amaun
        // dua kali secara berasingan — kalau formula menyimpang, dokumen
        // dan ledger tidak sepadan, iaitu asimetri yang
        // accounting-invariants.md beri amaran tentangnya.
        record Dikira(long productId, String nama, BigDecimal harga,
                      BigDecimal kuantiti, BigDecimal amaun, Long incomeGl) {}
        List<Dikira> dikira = new ArrayList<>();

        for (AdhocLine l : req.lines()) {
            Object[] p = produk(spCode, l.productId());
            String nama = (String) p[0];
            BigDecimal harga = (BigDecimal) p[1];
            Long incomeGl = p[2] == null ? null : ((Number) p[2]).longValue();

            BigDecimal kuantiti = l.quantity() == null
                    ? BigDecimal.ONE : l.quantity();
            if (kuantiti.signum() <= 0) {
                throw new IllegalStateException("Kuantiti mesti lebih daripada sifar.");
            }
            BigDecimal amaun = harga.multiply(kuantiti).setScale(2, RoundingMode.HALF_UP);
            dikira.add(new Dikira(l.productId(), nama, harga, kuantiti, amaun, incomeGl));

            docLines.add(new NewDocumentLine(
                    l.productId(), accountId, req.periodId(), nama, null,
                    kuantiti, harga, BigDecimal.ONE, amaun, BigDecimal.ZERO,
                    null, null,
                    // onceOnly=false: idem_key untuk baris adhoc menjadi
                    // NULL kerana period_start NULL, dan UNIQUE membenarkan
                    // berbilang NULL — dua caj clamp yang sama tidak
                    // berlanggar.
                    false));
            jumlah = jumlah.add(amaun);
        }

        var docId = documents.createInvoice(new NewInvoice(
                spCode, accountId, req.periodId(), docDate, req.dueDate(),
                "Invois " + req.issuedToName(), docLines,
                // Semua invois adhoc berkongsi satu akaun, jadi semakan
                // pendua (akaun, produk, tempoh) melihat dua pembeli buku
                // yang sama sebagai pendua dan menggugurkan yang kedua.
                true));
        if (docId.isEmpty()) {
            throw new IllegalStateException(
                    "Invois tidak dapat dijana — baris serupa sudah wujud.");
        }

        // Butiran penerima pada DOKUMEN. Akaun ADHOC-SALES dikongsi, jadi
        // ia tidak boleh membawa nama sesiapa.
        em.createNativeQuery("""
                UPDATE financial_document
                SET    issued_to_name = :nama, issued_to_email = :emel,
                       issued_to_phone = :fon, remarks = :catatan
                WHERE  id = :id
                """)
                .setParameter("nama", req.issuedToName().trim().toUpperCase())
                .setParameter("emel", kosongJadiNull(req.issuedToEmail()))
                .setParameter("fon", digitSahaja(req.issuedToPhone()))
                .setParameter("catatan", kosongJadiNull(req.remarks()))
                .setParameter("id", docId.get())
                .executeUpdate();

        // Ledger: Dr AR / Cr Hasil, sub-ledger = akaun ADHOC-SALES.
        // Sub-ledger NULL akan memecahkan rekonsiliasi — kawalan bergerak
        // sementara subsidiari tidak (accounting-invariants.md Family 3).
        var cfg = settings.forSp(spCode);
        // GL daripada TETAPAN SP, bukan pemalar. SP yang menetapkan AR
        // sendiri akan mempunyai invois berpecah antara dua GL kalau
        // pemalar digunakan — dan itu hanya muncul apabila akauntan
        // menutup buku.
        String arGl = cfg.arGlAccountId() == null
                ? GlAccounts.ACCOUNTS_RECEIVABLE
                : ledger.glCodeFor(spCode, cfg.arGlAccountId());
        String incomeGlLalai = cfg.incomeGlAccountId() == null
                ? GlAccounts.SERVICE_INCOME
                : ledger.glCodeFor(spCode, cfg.incomeGlAccountId());

        List<PostingLine> pl = new ArrayList<>();
        pl.add(PostingLine.debit(arGl, jumlah.add(cukai), accountId));
        for (Dikira d : dikira) {
            if (d.amaun().signum() == 0) continue;
            String gl = d.incomeGl() == null
                    ? incomeGlLalai
                    : ledger.glCodeFor(spCode, d.incomeGl());
            pl.add(PostingLine.credit(gl, d.amaun(), null));
        }

        ledger.post(new PostingRequest(
                spCode, docDate, SourceType.INVOICE, docId.get(),
                "Invois adhoc " + req.issuedToName(), pl, null));

        // TIADA applyAdvance — lihat komen kelas.

        String docNo = (String) em.createNativeQuery(
                "SELECT doc_no FROM financial_document WHERE id = :id")
                .setParameter("id", docId.get()).getSingleResult();

        return new Result(docId.get(), docNo, jumlah.add(cukai));
    }

    // ── bantuan ──────────────────────────────────────────────────────

    private Long sahkanAkaun(String spCode, Long accountId) {
        var r = em.createNativeQuery(
                "SELECT id FROM account WHERE id = :id AND sp_code = :sp "
                + "  AND status = 'ACTIVE'")
                .setParameter("id", accountId).setParameter("sp", spCode)
                .getResultList();
        if (r.isEmpty()) {
            throw new IllegalStateException("Akaun tidak dijumpai.");
        }
        return accountId;
    }

    /**
     * Akaun ADHOC-SALES SP — satu dikongsi (V50).
     *
     * Dicipta jika SP didaftarkan selepas V50 dan tiada seorang pun
     * pernah menjana invois adhoc.
     */
    private Long akaunAdhoc(String spCode) {
        var r = em.createNativeQuery(
                "SELECT id FROM account WHERE sp_code = :sp AND account_type = 'ADHOC'")
                .setParameter("sp", spCode).getResultList();
        if (!r.isEmpty()) {
            return ((Number) r.get(0)).longValue();
        }
        em.createNativeQuery("""
                INSERT INTO account (sp_code, account_no, account_name, account_type,
                                     status, created_at, updated_at, version)
                VALUES (:sp, 'ADHOC-SALES', 'Jualan Adhoc', 'ADHOC',
                        'ACTIVE', NOW(), NOW(), 0)
                """).setParameter("sp", spCode).executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM account WHERE sp_code = :sp AND account_type = 'ADHOC'")
                .setParameter("sp", spCode).getSingleResult()).longValue();
    }

    private Object[] produk(String spCode, long productId) {
        var r = em.createNativeQuery(
                "SELECT name, unit_rate, income_gl_account_id FROM product "
                + "WHERE id = :id AND sp_code = :sp AND status = 'ACTIVE'")
                .setParameter("id", productId).setParameter("sp", spCode)
                .getResultList();
        if (r.isEmpty()) {
            throw new IllegalStateException("Produk tidak dijumpai: " + productId);
        }
        return (Object[]) r.get(0);
    }

    private static String kosongJadiNull(String v) {
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    /**
     * Telefon sebagai DIGIT sahaja.
     *
     * Borang menyekat input kepada digit, tetapi borang bukan guard:
     * panggilan API terus boleh menghantar '012-345 6789'. Nombor yang
     * sama dalam empat bentuk berbeza bermakna setiap tempat yang
     * mencari atau membandingkannya perlu menormalkan semula.
     *
     * Normalisasi SEKALI, di sini, sebagai satu-satunya penulis.
     */
    private static String digitSahaja(String v) {
        if (v == null) return null;
        String d = v.replaceAll("\\D", "");
        return d.isEmpty() ? null : d;
    }
}
