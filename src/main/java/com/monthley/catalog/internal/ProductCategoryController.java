package com.monthley.catalog.internal;

import com.monthley.shared.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/** GET /api/v1/product-categories — untuk dropdown filter. */
@RestController
@RequestMapping("/api/v1/product-categories")
class ProductCategoryController {

    @PersistenceContext
    private EntityManager em;

    record CategoryDto(Long id, String code, String name) {}

    @GetMapping
    @SuppressWarnings("unchecked")
    List<CategoryDto> list() {
        String sp = TenantContext.get();
        if (sp == null || sp.isBlank()) {
            throw new IllegalStateException("Header X-SP-Id diperlukan");
        }
        List<Object[]> rows = em.createNativeQuery(
                "SELECT id, code, name FROM product_category WHERE sp_code = :sp ORDER BY name")
                .setParameter("sp", sp)
                .getResultList();

        List<CategoryDto> out = new ArrayList<>();
        for (Object[] r : rows) {
            out.add(new CategoryDto(((Number) r[0]).longValue(), (String) r[1], (String) r[2]));
        }
        return out;
    }
}
