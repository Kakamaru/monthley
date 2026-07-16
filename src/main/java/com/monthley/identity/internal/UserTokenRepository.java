package com.monthley.identity.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

interface UserTokenRepository extends JpaRepository<UserToken, Long> {

    Optional<UserToken> findByToken(String token);

    /** Batalkan token lama jenis sama — hanya yang terbaru sah. */
    @Modifying
    @Query("update UserToken t set t.usedAt = CURRENT_TIMESTAMP "
         + "where t.userId = :uid and t.type = :type and t.usedAt is null")
    void invalidatePrevious(@Param("uid") Long userId, @Param("type") UserToken.Type type);
}
