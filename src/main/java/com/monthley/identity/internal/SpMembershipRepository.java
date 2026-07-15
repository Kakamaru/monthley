package com.monthley.identity.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

interface SpMembershipRepository extends JpaRepository<SpMembership, Long> {
    List<SpMembership> findByUserIdAndStatus(Long userId, SpMembership.Status status);
    boolean existsBySpCodeAndUserId(String spCode, Long userId);
}
