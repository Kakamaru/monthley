package com.monthley.catalog.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

interface ProductRepository extends JpaRepository<Product, Long> {
    List<Product> findBySpCodeAndStatus(String spCode, Product.Status status);
}
