package com.monthley.account.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

interface AccountRepository extends JpaRepository<Account, Long> {
    List<Account> findBySpCodeAndStatus(String spCode, Account.Status status);
}
