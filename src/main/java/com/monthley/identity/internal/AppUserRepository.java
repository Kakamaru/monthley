package com.monthley.identity.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

interface AppUserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByEmailIgnoreCase(String email);
    boolean existsByEmailIgnoreCase(String email);
}
