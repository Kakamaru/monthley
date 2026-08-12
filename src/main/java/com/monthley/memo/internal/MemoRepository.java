package com.monthley.memo.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

interface MemoRepository extends JpaRepository<MemoNotice, Long> {
    Optional<MemoNotice> findByIdAndSpCode(Long id, String spCode);
}
