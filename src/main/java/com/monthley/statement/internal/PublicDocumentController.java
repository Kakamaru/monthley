package com.monthley.statement.internal;

import com.monthley.document.api.StatementAccessPort;
import com.monthley.document.api.DocumentAccessPort;
import com.monthley.document.api.DocumentType;
import com.monthley.statement.api.StatementPort;
import com.monthley.statement.api.StatementRenderPort;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;

/**
 * Pautan awam dokumen — TIADA log masuk.
 *
 * Pelanggan yang menerima e-mel resit mungkin tiada akaun portal.
 * SecurityConfig membenarkan /api/v1/pub/** dan TenantFilter
 * melangkaunya; spCode datang daripada token, bukan daripada header.
 *
 * KESELAMATAN BERGANTUNG SEPENUHNYA PADA TOKEN. 32 bait rawak selamat
 * kripto (256 bit) — meneka tidak boleh dilakukan. Token yang bocor
 * mendedahkan SATU dokumen, bukan akaun.
 *
 * 404 untuk token tidak wujud DAN token dibatalkan. Membezakan kedua-dua
 * membenarkan penyerang mengesahkan token mana pernah wujud.
 */
@RestController
@RequestMapping("/api/v1/pub")
class PublicDocumentController {

    private final DocumentAccessPort access;
    private final StatementAccessPort stmtAccess;
    private final StatementPort statements;
    private final StatementRenderPort renderer;

    PublicDocumentController(DocumentAccessPort access,
                             StatementAccessPort stmtAccess,
                             StatementPort statements,
                             StatementRenderPort renderer) {
        this.access = access;
        this.stmtAccess = stmtAccess;
        this.statements = statements;
        this.renderer = renderer;
    }

    /**
     * Penyata awam — token BERASINGAN daripada dokumen.
     *
     * Penyata bukan dokumen: ia unjuran atas julat tarikh dan tiada
     * baris dalam financial_document. Token memegang (akaun, tahun),
     * dan pautan yang sama berfungsi sepanjang tahun — penyata yang
     * dibukanya menunjukkan keadaan SEMASA, bukan snapshot pada masa
     * e-mel dihantar.
     *
     * Itu sifat: pelanggan yang membayar selepas menerima e-mel membuka
     * pautan lama dan melihat baki yang sudah dikemas kini.
     */
    @GetMapping(value = "/stmt/{token}", produces = MediaType.APPLICATION_PDF_VALUE)
    ResponseEntity<byte[]> statement(@PathVariable String token) {
        var s = stmtAccess.resolve(token).orElse(null);
        if (s == null) {
            return ResponseEntity.notFound().build();
        }

        var m = statements.forYear(s.spCode(), s.accountId(), s.year());
        var f = renderer.renderPdfFile(m);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline()
                                .filename(f.filename(), StandardCharsets.UTF_8)
                                .build().toString())
                .body(f.content());
    }

    /**
     * Invois awam. Nota debit turut menggunakan laluan ini — ia invois
     * dari sudut pelanggan.
     */
    @GetMapping(value = "/invoices/{token}", produces = MediaType.APPLICATION_PDF_VALUE)
    ResponseEntity<byte[]> invoice(@PathVariable String token) {
        var d = access.resolve(token).orElse(null);
        if (d == null
                || (d.type() != DocumentType.INVOICE && d.type() != DocumentType.DEBIT_NOTE)) {
            return ResponseEntity.notFound().build();
        }

        var m = statements.invoice(d.spCode(), d.documentId());
        var f = renderer.renderInvoicePdfFile(m);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline()
                                .filename(f.filename(), StandardCharsets.UTF_8)
                                .build().toString())
                .body(f.content());
    }

    @GetMapping(value = "/receipts/{token}", produces = MediaType.APPLICATION_PDF_VALUE)
    ResponseEntity<byte[]> receipt(@PathVariable String token) {
        var d = access.resolve(token).orElse(null);
        if (d == null || d.type() != DocumentType.RECEIPT) {
            return ResponseEntity.notFound().build();
        }

        var m = statements.receipt(d.spCode(), d.documentId());
        var f = renderer.renderReceiptPdfFile(m);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                // inline: pelanggan mengklik pautan dalam e-mel dan
                // menjangka melihat resit, bukan memuat turun fail.
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline()
                                .filename(f.filename(), StandardCharsets.UTF_8)
                                .build().toString())
                .body(f.content());
    }
}
