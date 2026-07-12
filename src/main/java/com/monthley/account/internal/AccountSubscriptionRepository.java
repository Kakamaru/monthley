package com.monthley.account.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

interface AccountSubscriptionRepository extends JpaRepository<AccountSubscription, Long> {
    List<AccountSubscription> findByAccountIdAndStatus(Long accountId, AccountSubscription.Status status);
}
