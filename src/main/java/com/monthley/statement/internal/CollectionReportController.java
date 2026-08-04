package com.monthley.statement.internal;

import com.monthley.ledger.api.CollectionReportPort;
import com.monthley.shared.Access;
import com.monthley.shared.TenantContext;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/**
 * Senarai Kutipan — data untuk skrin, PDF untuk cetakan.
 *
 * Modul ledger memiliki soalan "apa yang kita kutip"; modul ini
 * merendernya. Query jurnal tidak ditulis semula di sini.
 */
@RestController
@RequestMapping("/api/v1/reports/collection")
class CollectionReportController {

    private final CollectionReportPort reports;
    private final StatementQuery query;
    private final CollectionPdfWriter pdf;

    CollectionReportController(CollectionReportPort reports, StatementQuery query,
                               CollectionPdfWriter pdf) {
        this.reports = reports;
        this.query = query;
        this.pdf = pdf;
    }

    @GetMapping
    CollectionReportPort.Result data(@RequestParam LocalDate from,
                                     @RequestParam LocalDate to,
                                     @RequestParam(defaultValue = "false") boolean byProduct,
                                     @RequestParam(defaultValue = "false") boolean monthlyBasis,
                                     @RequestParam(required = false) String status,
                                     @RequestParam(required = false) String paymentType,
                                     @RequestParam(required = false) Long productId) {
        Access.requireAnyRole("melihat senarai kutipan", "SP_ADMIN", "CLERK", "VIEWER");
        return reports.collection(new CollectionReportPort.Query(
                sp(), from, to, byProduct, monthlyBasis, status, paymentType, productId));
    }

    @GetMapping(value = "/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    ResponseEntity<byte[]> asPdf(@RequestParam LocalDate from,
                                 @RequestParam LocalDate to,
                                 @RequestParam(defaultValue = "false") boolean byProduct,
                                 @RequestParam(defaultValue = "false") boolean monthlyBasis,
                                 @RequestParam(required = false) String status,
                                 @RequestParam(required = false) String paymentType,
                                 @RequestParam(required = false) Long productId) {
        Access.requireAnyRole("mencetak senarai kutipan", "SP_ADMIN", "CLERK", "VIEWER");

        var hasil = reports.collection(new CollectionReportPort.Query(
                sp(), from, to, byProduct, monthlyBasis, status, paymentType, productId));
        byte[] bytes = pdf.render(hasil, query.headerSp(sp()));

        String nama = "kutipan-" + from + "-" + to + ".pdf";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline()
                                .filename(nama, StandardCharsets.UTF_8)
                                .build().toString())
                .body(bytes);
    }

    private String sp() {
        String sp = TenantContext.get();
        if (sp == null || sp.isBlank()) {
            throw new IllegalStateException("Header X-SP-Id diperlukan");
        }
        return sp;
    }
}
