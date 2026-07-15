package com.monthley.identity.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

interface PlatformAdminRepository extends JpaRepository<PlatformAdmin, Long> {
    Optional<PlatformAdmin> findByEmailIgnoreCase(String email);
    boolean existsByEmailIgnoreCase(String email);
}
