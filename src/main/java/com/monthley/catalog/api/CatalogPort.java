package com.monthley.catalog.api;

import java.util.List;
import java.util.Optional;

/** Permukaan awam catalog — billing tanya produk melalui ni. */
public interface CatalogPort {

    Optional<ProductView> findById(Long productId);

    List<ProductView> activeProductsFor(String spCode);
}
