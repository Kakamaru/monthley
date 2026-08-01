package com.monthley.notification.internal;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface EmailOutboxRepository extends JpaRepository<EmailOutbox, Long> {

    /** PENDING tertua dahulu — baris gilir, bukan timbunan. */
    List<EmailOutbox> findByStatusOrderByCreatedAtAsc(EmailOutbox.Status status, Limit limit);

    boolean existsBySpCodeAndKindAndRefKey(String spCode, String kind, String refKey);
}
