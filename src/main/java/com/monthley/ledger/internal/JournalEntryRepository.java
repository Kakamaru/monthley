package com.monthley.ledger.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

interface JournalEntryRepository extends JpaRepository<JournalEntry, Long> {

    Optional<JournalEntry> findBySpCodeAndEntryNo(String spCode, String entryNo);

    @Query("select coalesce(max(je.id),0) from JournalEntry je where je.spCode = :sp")
    long lastId(@Param("sp") String spCode);
}
