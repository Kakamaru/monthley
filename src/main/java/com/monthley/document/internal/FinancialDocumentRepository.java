package com.monthley.document.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

interface FinancialDocumentRepository extends JpaRepository<FinancialDocument, Long> {

    Optional<FinancialDocument> findBySpCodeAndSourceRef(String spCode, String sourceRef);

    /**
     * Baca dokumen dengan kunci pesimis — dokumen ialah sempadan agregat
     * bagi alokasi, jadi alokasi serentak bersiri di sini.
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query(
            "SELECT d FROM FinancialDocument d WHERE d.id = :id")
    Optional<FinancialDocument> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") Long id);
}
