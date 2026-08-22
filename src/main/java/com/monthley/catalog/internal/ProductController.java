package com.monthley.catalog.internal;

import com.monthley.shared.PageResponse;
import com.monthley.shared.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

/**
 * REST untuk skrin Products (rujuk handoff §5).
 *   GET  /api/v1/products?active=&category=&q=&page=&size=
 *   POST /api/v1/products
 *   PUT  /api/v1/products/{id}
 *
 * Tenant (sp_code) dari header X-SP-Id via TenantFilter.
 */
@RestController
@RequestMapping("/api/v1/products")
class ProductController {

    private final ProductRepository products;

    ProductController(ProductRepository products) {
        this.products = products;
    }

    // ---------- DTO ----------

    record ProductDto(
            Long id, String code, String subscriptionCode, Long categoryId,
            String name, String description, BigDecimal rate, String chargeFrequency,
            Integer anchorMonth, boolean prorated, boolean latePenalty,
            boolean mandatory, boolean mainProduct, boolean active) {

        static ProductDto from(Product p) {
            return new ProductDto(p.getId(), p.getCode(), p.getSubscriptionCode(),
                    p.getCategoryId(), p.getName(), p.getDescription(), p.getUnitRate(),
                    p.getChargeFrequency().name(), p.getAnchorMonth(),
                    p.isProrated(), p.isLatePenalty(), p.isMandatory(),
                    p.isMainProduct(), p.getStatus() == Product.Status.ACTIVE);
        }
    }

    record SaveProductRequest(
            @NotBlank String code,
            String subscriptionCode,
            Long categoryId,
            @NotBlank String name,
            String description,
            BigDecimal rate,
            @NotBlank String chargeFrequency,
            Integer anchorMonth,
            boolean prorated,
            boolean latePenalty,
            boolean mandatory,
            boolean mainProduct) {}

    // ---------- Endpoints ----------

    @GetMapping
    PageResponse<ProductDto> list(
            @RequestParam(defaultValue = "true") boolean active,
            @RequestParam(required = false) Long category,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        var status = active ? Product.Status.ACTIVE : Product.Status.INACTIVE;
        var pageable = PageRequest.of(page, size, Sort.by("code"));
        var result = products.search(sp(), status, category,
                (q == null || q.isBlank()) ? null : q.trim(), pageable);

        return PageResponse.of(result.map(ProductDto::from));
    }

    @GetMapping("/{id}")
    ResponseEntity<ProductDto> get(@PathVariable Long id) {
        return products.findById(id)
                .filter(p -> p.getSpCode().equals(sp()))
                .map(p -> ResponseEntity.ok(ProductDto.from(p)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    ProductDto create(@Valid @RequestBody SaveProductRequest r) {
        // Kod disemak SEBELUM menulis.
        //
        // uk_product (sp_code, code) melemparkan kekangan yang menjadi HTTP
        // 500 — skrin memaparkan 'Gagal menyimpan produk' dan pengguna
        // tidak tahu kod itu sudah digunakan. Semakan di sini memberi
        // mesej yang menyebut kod berkenaan.
        //
        // Kekangan DB kekal: ia yang menghalang perlumbaan antara dua
        // permintaan serentak. Semakan ini untuk MESEJ, bukan untuk
        // keselamatan.
        String kod = r.code() == null ? "" : r.code().trim();
        if (!kod.isEmpty() && products.existsBySpCodeAndCode(sp(), kod)) {
            throw new IllegalStateException(
                    "Kod produk '" + kod + "' sudah digunakan. Sila guna kod lain.");
        }

        Product p = new Product(sp(), r.code(), r.name(),
                com.monthley.shared.ChargeFrequency.valueOf(r.chargeFrequency()),
                r.rate() == null ? BigDecimal.ZERO : r.rate());
        apply(p, r);
        return ProductDto.from(products.save(p));
    }

    @PutMapping("/{id}")
    ResponseEntity<ProductDto> update(@PathVariable Long id,
                                      @Valid @RequestBody SaveProductRequest r) {
        return products.findById(id)
                .filter(p -> p.getSpCode().equals(sp()))
                .map(p -> {
                    p.rename(r.name());
                    p.setRate(r.rate() == null ? BigDecimal.ZERO : r.rate());
                    p.setChargeFrequency(
                            com.monthley.shared.ChargeFrequency.valueOf(r.chargeFrequency()));
                    apply(p, r);
                    return ResponseEntity.ok(ProductDto.from(products.save(p)));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Tukar status sahaja — endpoint berasingan, bukan update penuh.
     *
     * update() menuntut SaveProductRequest LENGKAP. Menukar status
     * melaluinya bermakna frontend menghantar semula setiap medan, dan
     * satu yang terlepas menjadi null secara SENYAP.
     *
     * TIADA PADAM. Produk yang pernah dilanggan atau dibil mempunyai
     * baris yang merujuknya; memadamnya meninggalkan rujukan yatim atau
     * melanggar FK. Nyahaktif menyembunyikannya daripada senarai aktif
     * dan daripada penjanaan bil, dan sejarah kekal boleh dibaca.
     *
     * Boleh diaktifkan semula — tab Tidak Aktif wujud untuk itu.
     */
    @PutMapping("/{id}/status")
    ResponseEntity<ProductDto> setStatus(@PathVariable Long id,
                                         @RequestBody StatusRequest r) {
        return products.findById(id)
                .filter(p -> p.getSpCode().equals(sp()))
                .map(p -> {
                    if (r.active()) p.activate(); else p.deactivate();
                    return ResponseEntity.ok(ProductDto.from(products.save(p)));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    record StatusRequest(boolean active) {}

    private void apply(Product p, SaveProductRequest r) {
        p.setSubscriptionCode(r.subscriptionCode());
        p.setCategoryId(r.categoryId());
        p.setDescription(r.description());
        p.setAnchorMonth(r.anchorMonth());
        p.setProrated(r.prorated());
        p.setLatePenalty(r.latePenalty());
        p.setMandatory(r.mandatory());
        p.setMainProduct(r.mainProduct());
    }

    private String sp() {
        String sp = TenantContext.get();
        if (sp == null || sp.isBlank()) {
            throw new IllegalStateException("Header X-SP-Id diperlukan");
        }
        return sp;
    }
}
