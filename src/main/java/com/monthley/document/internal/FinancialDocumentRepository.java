package com.monthley.document.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

interface FinancialDocumentRepository extends JpaRepository<FinancialDocument, Long> {

    Optional<FinancialDocument> findBySpCodeAndSourceRef(String spCode, String sourceRef);
}
