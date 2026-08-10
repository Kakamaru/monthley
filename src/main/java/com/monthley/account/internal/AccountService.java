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

    @Override
    @Transactional
    public Long createSpBillingAccount(String platformSpCode, String accountNo,
                                       String accountName, java.time.LocalDate startDate,
                                       List<Long> productIds) {
        String no = accountNo == null ? null : accountNo.trim();
        if (no == null || no.isBlank()) {
            throw new IllegalStateException("No. akaun diperlukan untuk akaun bil SP.");
        }
        if (accounts.existsBySpCodeAndAccountNo(platformSpCode, no)) {
            throw new IllegalStateException("No. akaun " + no + " sudah wujud.");
        }

        Account a = new Account(platformSpCode, no, accountName.trim());
        a.setChargeFrequency(com.monthley.shared.ChargeFrequency.MONTHLY);
        a.setStartDate(startDate);
        Account saved = accounts.save(a);

        if (productIds != null) {
            // Permintaan boleh membawa produk yang sama dua kali; guard DB
            // belum membantu kerana tiada baris lagi.
            var dilihat = new java.util.HashSet<Long>();
            for (Long pid : productIds) {
                if (pid == null || !dilihat.add(pid)) continue;
                subscriptions.save(new AccountSubscription(
                        platformSpCode, saved.getId(), pid,
                        java.math.BigDecimal.ONE, startDate));
            }
        }
        return saved.getId();
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
