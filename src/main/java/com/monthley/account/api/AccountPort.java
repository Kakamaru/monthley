package com.monthley.account.api;

import java.util.List;

/** Permukaan awam account — billing tanya akaun & langganan melalui ni. */
public interface AccountPort {

    /** Akaun aktif untuk SP (calon jana invois). */
    List<AccountView> activeAccountsFor(String spCode);

    /** Langganan aktif untuk satu akaun. */
    List<SubscriptionView> activeSubscriptions(Long accountId);

    /**
     * Cipta akaun bil untuk SP yang baru di-onboard, bawah SP platform.
     *
     * Wujud supaya platform tidak perlu INSERT terus ke jadual account.
     * SQL mentah dari modul lain TIDAK ditangkap oleh Spring Modulith —
     * ia rentetan, bukan import — jadi ia memintas setiap invarian modul
     * account secara senyap.
     *
     * @param productIds produk yang dilanggan (pelan + item sekali sahaja).
     *                   Harga diambil dari produk; tiada unit_price ditetapkan.
     * @return id akaun yang dicipta
     * @throws IllegalStateException jika accountNo sudah wujud dalam SP itu
     */
    Long createSpBillingAccount(String platformSpCode, String accountNo,
                                String accountName, java.time.LocalDate startDate,
                                List<Long> productIds);
}
