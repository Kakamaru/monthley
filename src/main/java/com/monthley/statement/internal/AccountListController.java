package com.monthley.statement.internal;

import com.monthley.account.api.AccountListPort;
import com.monthley.shared.Access;
import com.monthley.shared.TenantContext;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;

/**
 * Senarai Akaun — data untuk skrin, PDF untuk cetakan.
 *
 * Modul account memiliki akaun; modul ini merendernya. Corak sama
 * seperti laporan kutipan.
 */
@RestController
@RequestMapping("/api/v1/reports/account-list")
class AccountListController {

    private final AccountListPort accounts;
    private final StatementQuery query;
    private final AccountListPdfWriter pdf;

    AccountListController(AccountListPort accounts, StatementQuery query,
                          AccountListPdfWriter pdf) {
        this.accounts = accounts;
        this.query = query;
        this.pdf = pdf;
    }

    @GetMapping
    AccountListPort.Result data(@RequestParam(required = false) Boolean active,
                                @RequestParam(required = false) Long categoryId,
                                @RequestParam(required = false) String search) {
        Access.requireAnyRole("melihat senarai akaun", "SP_ADMIN", "CLERK", "VIEWER");
        return accounts.accountList(
                new AccountListPort.Query(sp(), active, categoryId, search));
    }

    @GetMapping(value = "/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    ResponseEntity<byte[]> asPdf(@RequestParam(required = false) Boolean active,
                                 @RequestParam(required = false) Long categoryId,
                                 @RequestParam(required = false) String search) {
        Access.requireAnyRole("mencetak senarai akaun", "SP_ADMIN", "CLERK", "VIEWER");

        var hasil = accounts.accountList(
                new AccountListPort.Query(sp(), active, categoryId, search));
        byte[] bytes = pdf.render(hasil, query.headerSp(sp()));

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline()
                                .filename("senarai-akaun.pdf", StandardCharsets.UTF_8)
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
