package com.monthley.account.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

interface AccountSubscriptionRepository extends JpaRepository<AccountSubscription, Long> {
    List<AccountSubscription> findByAccountIdAndStatus(Long accountId, AccountSubscription.Status status);

    /**
     * Satu akaun, satu produk, satu langganan HIDUP (CASE-007).
     *
     * ENDED tidak menyekat: pelanggan boleh berhenti dan melanggan semula,
     * dan setiap kitaran mendapat barisnya sendiri supaya sejarah kekal.
     * Corak sama seperti legacy (status D lawan A).
     *
     * Tarikh tidak terlibat. Tempoh MANA yang dicaj diputuskan oleh
     * effStart/effEnd dan PeriodResolver; sama ada ia sudah dicaj
     * diputuskan oleh idem_key. Guard ini hanya menjaga: adakah produk ini
     * sudah mempunyai langganan hidup.
     */
    boolean existsByAccountIdAndProductIdAndStatus(
            Long accountId, Long productId, AccountSubscription.Status status);
}
