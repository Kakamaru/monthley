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
            String name, BigDecimal rate, String chargeFrequency,
            Integer anchorMonth, boolean prorated, boolean latePenalty,
            boolean mandatory, boolean active) {

        static ProductDto from(Product p) {
            return new ProductDto(p.getId(), p.getCode(), p.getSubscriptionCode(),
                    p.getCategoryId(), p.getName(), p.getUnitRate(),
                    p.getChargeFrequency().name(), p.getAnchorMonth(),
                    p.isProrated(), p.isLatePenalty(), p.isMandatory(),
                    p.getStatus() == Product.Status.ACTIVE);
        }
    }

    record SaveProductRequest(
            @NotBlank String code,
            String subscriptionCode,
            Long categoryId,
            @NotBlank String name,
            BigDecimal rate,
            @NotBlank String chargeFrequency,
            Integer anchorMonth,
            boolean prorated,
            boolean latePenalty,
            boolean mandatory) {}

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

    private void apply(Product p, SaveProductRequest r) {
        p.setSubscriptionCode(r.subscriptionCode());
        p.setCategoryId(r.categoryId());
        p.setAnchorMonth(r.anchorMonth());
        p.setProrated(r.prorated());
        p.setLatePenalty(r.latePenalty());
        p.setMandatory(r.mandatory());
    }

    private String sp() {
        String sp = TenantContext.get();
        if (sp == null || sp.isBlank()) {
            throw new IllegalStateException("Header X-SP-Id diperlukan");
        }
        return sp;
    }
}
