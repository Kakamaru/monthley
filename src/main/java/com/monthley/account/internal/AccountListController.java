package com.monthley.account.internal;

import com.monthley.account.api.AccountListPort;
import com.monthley.shared.Access;
import com.monthley.shared.TenantContext;
import com.monthley.statement.api.StatementRenderPort;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Senarai Akaun — data untuk skrin, PDF untuk cetakan.
 *
 * DUDUK DALAM MODUL ACCOUNT, bukan statement.
 *
 * Percubaan pertama meletakkannya dalam statement bersama laporan
 * kutipan. Itu memerlukan statement -> account::api, dan AccountController
 * sudah menggunakan StatementPort untuk endpoint /{id}/statement:
 * kitaran, dan ModularityTests menolaknya.
 *
 * Ia hanya gagal SELEPAS controller ditulis — mengisytiharkan
 * allowedDependencies tidak mencukupi, ia memerlukan penggunaan sebenar.
 * Kali KEDUA corak ini menggigit (V57 yang pertama).
 *
 * Enjin PDF datang melalui StatementRenderPort.renderTemplatePdf:
 * templat hidup dalam modul yang memilikinya, enjin dikongsi.
 */
@RestController
@RequestMapping("/api/v1/reports/account-list")
class AccountListController {

    private final AccountListPort accounts;
    private final StatementRenderPort render;

    AccountListController(AccountListPort accounts, StatementRenderPort render) {
        this.accounts = accounts;
        this.render = render;
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
        var h = render.headerForSp(sp());

        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("r", hasil);
        vars.put("h", h);
        vars.put("fmt", render.formatterFor(h));

        byte[] bytes = render.renderTemplatePdf("account/account-list", vars);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline()
                                .filename("senarai-akaun.pdf", StandardCharsets.UTF_8)
                                .build().toString())
                .body(bytes);
    }

    // ── Senarai Langganan ────────────────────────────────────────────

    @GetMapping("/subscriptions")
    AccountListPort.SubResult subs(@RequestParam(required = false) Long productCategoryId,
                                   @RequestParam(required = false) Long productId,
                                   @RequestParam(required = false) Boolean status) {
        Access.requireAnyRole("melihat senarai langganan", "SP_ADMIN", "CLERK", "VIEWER");
        return accounts.subscriptionList(new AccountListPort.SubQuery(
                sp(), productCategoryId, productId, status));
    }

    @GetMapping(value = "/subscriptions/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    ResponseEntity<byte[]> subsPdf(@RequestParam(required = false) Long productCategoryId,
                                   @RequestParam(required = false) Long productId,
                                   @RequestParam(required = false) Boolean status) {
        Access.requireAnyRole("mencetak senarai langganan", "SP_ADMIN", "CLERK", "VIEWER");

        var hasil = accounts.subscriptionList(new AccountListPort.SubQuery(
                sp(), productCategoryId, productId, status));
        var h = render.headerForSp(sp());

        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("r", hasil);
        vars.put("h", h);
        vars.put("fmt", render.formatterFor(h));

        byte[] bytes = render.renderTemplatePdf("account/subscription-list", vars);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline()
                                .filename("senarai-langganan.pdf", StandardCharsets.UTF_8)
                                .build().toString())
                .body(bytes);
    }

    // ── Senarai Tunggakan ────────────────────────────────────────────

    @GetMapping("/arrears")
    AccountListPort.ArrearResult arrears(
            @RequestParam java.time.LocalDate asAt,
            @RequestParam(defaultValue = "true") boolean arrearsOnly) {
        Access.requireAnyRole("melihat senarai tunggakan", "SP_ADMIN", "CLERK", "VIEWER");
        return accounts.arrears(
                new AccountListPort.ArrearQuery(sp(), asAt, arrearsOnly));
    }

    @GetMapping(value = "/arrears/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    ResponseEntity<byte[]> arrearsPdf(
            @RequestParam java.time.LocalDate asAt,
            @RequestParam(defaultValue = "true") boolean arrearsOnly) {
        Access.requireAnyRole("mencetak senarai tunggakan", "SP_ADMIN", "CLERK", "VIEWER");

        var hasil = accounts.arrears(
                new AccountListPort.ArrearQuery(sp(), asAt, arrearsOnly));
        var h = render.headerForSp(sp());

        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("r", hasil);
        vars.put("h", h);
        vars.put("fmt", render.formatterFor(h));

        byte[] bytes = render.renderTemplatePdf("account/arrears-list", vars);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline()
                                .filename("tunggakan-" + asAt + ".pdf", StandardCharsets.UTF_8)
                                .build().toString())
                .body(bytes);
    }

    // ── Ageing ───────────────────────────────────────────────────────

    @GetMapping("/ageing")
    AccountListPort.AgeResult ageing(@RequestParam java.time.LocalDate asAt,
                                     @RequestParam(required = false) Long categoryId,
                                     @RequestParam(required = false) String sortBy,
                                     @RequestParam(defaultValue = "true") boolean asc) {
        Access.requireAnyRole("melihat laporan ageing", "SP_ADMIN", "CLERK", "VIEWER");
        return accounts.ageing(
                new AccountListPort.AgeQuery(sp(), asAt, categoryId, sortBy, asc));
    }

    @GetMapping(value = "/ageing/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    ResponseEntity<byte[]> ageingPdf(@RequestParam java.time.LocalDate asAt,
                                     @RequestParam(required = false) Long categoryId,
                                     @RequestParam(required = false) String sortBy,
                                     @RequestParam(defaultValue = "true") boolean asc) {
        Access.requireAnyRole("mencetak laporan ageing", "SP_ADMIN", "CLERK", "VIEWER");

        var hasil = accounts.ageing(
                new AccountListPort.AgeQuery(sp(), asAt, categoryId, sortBy, asc));
        var h = render.headerForSp(sp());

        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("r", hasil);
        vars.put("h", h);
        vars.put("fmt", render.formatterFor(h));

        byte[] bytes = render.renderTemplatePdf("account/ageing", vars);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline()
                                .filename("ageing-" + asAt + ".pdf", StandardCharsets.UTF_8)
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
