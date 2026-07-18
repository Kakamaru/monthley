package com.monthley.account.internal;

import com.monthley.account.api.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
class AccountService implements AccountPort {

    private final AccountRepository accounts;
    private final AccountSubscriptionRepository subscriptions;

    AccountService(AccountRepository accounts, AccountSubscriptionRepository subscriptions) {
        this.accounts = accounts;
        this.subscriptions = subscriptions;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AccountView> activeAccountsFor(String spCode) {
        return accounts.findBySpCodeAndStatus(spCode, Account.Status.ACTIVE)
                .stream().map(this::toView).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<SubscriptionView> activeSubscriptions(Long accountId) {
        return subscriptions.findByAccountIdAndStatus(accountId, AccountSubscription.Status.ACTIVE)
                .stream().map(this::toView).toList();
    }

    private AccountView toView(Account a) {
        return new AccountView(a.getId(), a.getSpCode(), a.getAccountNo(),
                a.getAccountName(), a.getChargeFrequency(), a.getStartDate(),
                a.getExpiryDate(), a.getStatus() == Account.Status.ACTIVE);
    }

    private SubscriptionView toView(AccountSubscription s) {
        return new SubscriptionView(s.getId(), s.getAccountId(), s.getProductId(),
                s.getQuantity(), s.getUnitPrice(), s.getStartDate(), s.getEndDate(),
                s.getParentSubscriptionId());
    }
}
