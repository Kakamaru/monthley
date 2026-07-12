package com.monthley.document.internal;

import org.springframework.data.jpa.repository.JpaRepository;

interface DocumentLineRepository extends JpaRepository<FinancialDocumentLine, Long> {

    boolean existsByAccountIdAndProductIdAndPeriodStartAndActiveTrue(
            Long accountId, Long productId, java.time.LocalDate periodStart);
}
