package com.monthley.catalog.internal;

import com.monthley.catalog.api.CatalogPort;
import com.monthley.catalog.api.ProductView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
class CatalogService implements CatalogPort {

    private final ProductRepository products;

    CatalogService(ProductRepository products) {
        this.products = products;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProductView> findById(Long productId) {
        return products.findById(productId).map(this::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductView> activeProductsFor(String spCode) {
        return products.findBySpCodeAndStatus(spCode, Product.Status.ACTIVE)
                .stream().map(this::toView).toList();
    }

    private ProductView toView(Product p) {
        return new ProductView(
                p.getId(), p.getSpCode(), p.getCode(), p.getName(),
                p.getChargeFrequency(), p.getAnchorMonth(), p.getUnitRate(),
                p.getIncomeGlAccountId(), p.isProrated(), p.isLatePenalty(), p.isMandatory());
    }
}
