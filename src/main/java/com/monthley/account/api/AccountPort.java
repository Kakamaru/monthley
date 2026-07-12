package com.monthley.account.api;

import java.util.List;

/** Permukaan awam account — billing tanya akaun & langganan melalui ni. */
public interface AccountPort {

    /** Akaun aktif untuk SP (calon jana invois). */
    List<AccountView> activeAccountsFor(String spCode);

    /** Langganan aktif untuk satu akaun. */
    List<SubscriptionView> activeSubscriptions(Long accountId);
}
