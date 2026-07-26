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

    private final StatementPort statements;
    private final StatementRenderPort renderer;

    StatementController(StatementPort statements, StatementRenderPort renderer) {
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
