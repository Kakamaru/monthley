package com.monthley.catalog.internal;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

interface ProductRepository extends JpaRepository<Product, Long> {

    List<Product> findBySpCodeAndStatus(String spCode, Product.Status status);

    /** Carian skrin Products: tab (status) + kategori + q (nama/kod). */
    @Query("""
        select p from Product p
        where p.spCode = :sp
          and p.status = :status
          and (:categoryId is null or p.categoryId = :categoryId)
          and (:q is null or lower(p.name) like lower(concat('%', :q, '%'))
                          or lower(p.code) like lower(concat('%', :q, '%')))
        """)
    Page<Product> search(@Param("sp") String spCode,
                         @Param("status") Product.Status status,
                         @Param("categoryId") Long categoryId,
                         @Param("q") String q,
                         Pageable pageable);
}
