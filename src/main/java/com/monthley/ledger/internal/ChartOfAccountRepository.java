package com.monthley.ledger.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

interface ChartOfAccountRepository extends JpaRepository<ChartOfAccount, Long> {

    Optional<ChartOfAccount> findBySpCodeAndCode(String spCode, String code);

    Optional<ChartOfAccount> findByIdAndSpCode(Long id, String spCode);

    boolean existsBySpCode(String spCode);
}
