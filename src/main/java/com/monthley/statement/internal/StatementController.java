package com.monthley.statement.internal;

import com.monthley.shared.TenantContext;
import com.monthley.statement.api.StatementModel;
import com.monthley.statement.api.StatementPort;
import com.monthley.statement.api.StatementRenderPort;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.Year;

/**
 * Penyata untuk skrin SP — ikon muat turun pada akaun, dan tab Laporan.
 * Portal pelanggan mempunyai endpointnya sendiri dalam AccountController
 * kerana sempadan kebenarannya BERBEZA.
 *
 * KEBENARAN BUKAN MILIK StatementService. Perkhidmatan itu tidak tahu
 * siapa pemanggilnya; ia tidak boleh menguatkuasakan apa-apa. Skrin SP
 * disempadani oleh TenantContext (SP boleh melihat akaunnya sendiri);
 * portal disempadani oleh payer_user_id daripada JWT (pembayar boleh
 * melihat akaun yang dibayarnya, MERENTAS SP). Dua peraturan berbeza,
 * jadi dua pengawal — tetapi satu perkhidmatan di bawahnya (ADR 0010
 * keputusan 1).
 */
@RestController
@RequestMapping("/api/v1/statements")
class StatementController {

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager em;

    private final StatementPort statements;
    private final StatementRenderPort renderer;
    private final com.monthley.document.api.DocumentAccessPort access;
    private final com.monthley.notification.api.EmailPort email;
    private final String appUrl;

    StatementController(StatementPort statements, StatementRenderPort renderer,
                        com.monthley.document.api.DocumentAccessPort access,
                        com.monthley.notification.api.EmailPort email,
                        @org.springframework.beans.factory.annotation.Value(
                                "${monthley.app-url:http://localhost:4200}") String appUrl) {
        this.access = access;
        this.email = email;
        this.appUrl = appUrl;
        this.statements = statements;
        this.renderer = renderer;
    }

    private String sp() {
        String sp = TenantContext.get();
        if (sp == null || sp.isBlank()) {
            throw new IllegalStateException("Header X-SP-Id diperlukan");
        }
        return sp;
    }

    /**
     * Tahun yang MEMPUNYAI transaksi bagi satu akaun.
     *
     * Dropdown yang menyenaraikan sepuluh tahun ke belakang memaksa SP
     * mencuba satu-satu untuk mencari yang ada data. Ini menyenaraikan
     * hanya yang wujud.
     *
     * firstYear membolehkan pilihan 'Semua sejak mula': penyata bermula
     * pada transaksi PERTAMA, bukan tarikh sewenang-wenangnya.
     */
    record TahunTersedia(java.util.List<Integer> years, Integer firstYear) {}

    @GetMapping("/accounts/{accountId}/years")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    TahunTersedia years(@PathVariable long accountId) {
        java.util.List<Object> rows = em.createNativeQuery("""
                SELECT DISTINCT YEAR(e.doc_date) AS thn
                FROM   account_document_entry e
                WHERE  e.sp_code = :sp AND e.account_id = :acc
                ORDER  BY thn DESC
                """).setParameter("sp", sp()).setParameter("acc", accountId)
                .getResultList();

        java.util.List<Integer> tahun = new java.util.ArrayList<>();
        for (Object r : rows) tahun.add(((Number) r).intValue());

        return new TahunTersedia(tahun,
                tahun.isEmpty() ? null : tahun.get(tahun.size() - 1));
    }

    /**
     * Ikon akaun dan tab Laporan. Tahun tanpa nilai bermakna tahun semasa
     * — BUKAN semua rekod. Lalai legacy 'Sila Pilih' menjana dari transaksi
     * pertama kerana legacy tidak boleh menghasilkan penyata tahun demi tahun
     * dengan baki bawa hadapan yang betul. Kita boleh, jadi gejala itu hilang.
     */
    @GetMapping(value = "/accounts/{accountId}", produces = MediaType.APPLICATION_PDF_VALUE)
    ResponseEntity<byte[]> pdf(
            @PathVariable long accountId,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {

        StatementModel m = (from != null && to != null)
                ? statements.forRange(sp(), accountId, from, to)
                : statements.forYear(sp(), accountId,
                        year != null ? year : Year.now().getValue());

        return pdfResponse(m);
    }

    /**
     * XLSX untuk tab Laporan. Model yang SAMA seperti PDF; hanya penulis
     * berbeza (ADR 0010 keputusan 7).
     */
    @GetMapping(value = "/accounts/{accountId}/xlsx")
    ResponseEntity<byte[]> xlsx(
            @PathVariable long accountId,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {

        StatementModel m = (from != null && to != null)
                ? statements.forRange(sp(), accountId, from, to)
                : statements.forYear(sp(), accountId,
                        year != null ? year : Year.now().getValue());

        var f = renderer.renderXlsxFile(m);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(f.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(f.filename(), StandardCharsets.UTF_8)
                                .build().toString())
                .body(f.content());
    }

    /**
     * Resit PDF.
     *
     * @param receiptId id DOKUMEN resit (financial_document.id) — bukan
     *        payment.id yang PaymentResult.receiptId() kembalikan.
     */
    @GetMapping(value = "/receipts/{receiptId}", produces = MediaType.APPLICATION_PDF_VALUE)
    ResponseEntity<byte[]> receipt(@PathVariable long receiptId) {
        var m = statements.receipt(sp(), receiptId);
        var f = renderer.renderReceiptPdfFile(m);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(f.filename(), StandardCharsets.UTF_8)
                                .build().toString())
                .body(f.content());
    }

    /** Invois PDF. Nota debit turut diterima — ia invois dari sudut pelanggan. */
    @GetMapping(value = "/invoices/{invoiceId}", produces = MediaType.APPLICATION_PDF_VALUE)
    ResponseEntity<byte[]> invoice(@PathVariable long invoiceId) {
        var m = statements.invoice(sp(), invoiceId);
        var f = renderer.renderInvoicePdfFile(m);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline()
                                .filename(f.filename(), StandardCharsets.UTF_8)
                                .build().toString())
                .body(f.content());
    }

    record ResendRequest(java.util.List<String> emails) {}
    record ResendResult(int sent, java.util.List<String> recipients) {}

    /**
     * Hantar semula dokumen — resit atau invois — kepada satu atau lebih
     * alamat.
     *
     * DUDUK DI SINI, bukan dalam document. Modul document memiliki DATA
     * dokumen dan tidak tahu bagaimana ia dirender atau dihantar.
     * Percubaan pertama meletakkannya di sana dan ModularityTests
     * menolaknya: statement sudah bergantung pada document::api untuk
     * pautan awam, jadi document -> statement ialah kitaran.
     *
     * Alamat datang daripada PERMINTAAN, bukan akaun: alamat pada akaun
     * mungkin salah, atau pelanggan mahu salinan ke alamat kedua.
     * Alamat TIDAK disimpan — ini hantaran sekali; menukar alamat akaun
     * ialah tindakan berasingan pada skrin Akaun.
     *
     * Token pautan ialah token yang SAMA seperti penghantaran asal
     * (satu token per dokumen), jadi e-mel lama kekal berfungsi.
     */
    @PostMapping("/documents/{id}/resend")
    ResendResult resend(@PathVariable Long id, @RequestBody ResendRequest r) {
        com.monthley.shared.Access.requireAnyRole(
                "menghantar semula dokumen", "SP_ADMIN", "CLERK");

        java.util.List<String> alamat = (r == null || r.emails() == null)
                ? java.util.List.of()
                : r.emails().stream()
                        .filter(e -> e != null && !e.isBlank())
                        .map(String::trim)
                        .distinct()
                        .toList();
        if (alamat.isEmpty()) {
            throw new IllegalStateException(
                    "Sekurang-kurangnya satu alamat e-mel diperlukan.");
        }

        var d = access.describe(sp(), id).orElseThrow(() ->
                new IllegalStateException("Dokumen tidak dijumpai."));
        if (d.cancelled()) {
            // Pelanggan akan menerima dokumen yang tidak lagi sah dan
            // menganggapnya bukti bayaran.
            throw new IllegalStateException(
                    "Dokumen " + d.docNo() + " telah dibatalkan dan tidak boleh dihantar.");
        }

        String label, docNo, amount, tarikh, nama, spName, laluan, akaunNo, akaunNama;
        if (d.type() == com.monthley.document.api.DocumentType.RECEIPT) {
            var m = statements.receipt(sp(), id);
            label = "Resit";
            docNo = m.receiptNo();
            amount = m.header().currency() + " " + m.amountPaid().toPlainString();
            tarikh = m.receiptDate().toString();
            nama = pilihNama(m.header());
            spName = m.header().spName();
            laluan = "receipts";
            akaunNo = m.header().accountNo();
            akaunNama = m.header().accountName();
        } else {
            var m = statements.invoice(sp(), id);
            label = m.documentTitle();
            docNo = m.invoiceNo();
            amount = m.header().currency() + " " + m.totalDue().toPlainString();
            tarikh = m.invoiceDate().toString();
            nama = pilihNama(m.header());
            spName = m.header().spName();
            laluan = "invoices";
            akaunNo = m.header().accountNo();
            akaunNama = m.header().accountName();
        }

        String token = access.tokenFor(sp(), id, d.type());
        email.resendDocument(alamat, nama, spName, label, docNo,
                akaunNo, akaunNama, amount, tarikh,
                appUrl + "/api/v1/pub/" + laluan + "/" + token);

        return new ResendResult(alamat.size(), alamat);
    }

    private static String pilihNama(com.monthley.statement.api.StatementHeader h) {
        return (h.billtoName() == null || h.billtoName().isBlank())
                ? h.accountName() : h.billtoName();
    }

    private ResponseEntity<byte[]> pdfResponse(StatementModel m) {
        var f = renderer.renderPdfFile(m);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(f.filename(), StandardCharsets.UTF_8)
                                .build().toString())
                .body(f.content());
    }
}
