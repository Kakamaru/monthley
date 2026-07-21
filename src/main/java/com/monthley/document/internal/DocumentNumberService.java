package com.monthley.document.internal;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Jana nombor dokumen berturutan per SP, thread-safe guna row lock.
 * Guna jadual document_number_sequence.
 */
@Service
class DocumentNumberService {

    @PersistenceContext
    private EntityManager em;

    @Transactional(propagation = Propagation.MANDATORY)
    String next(String spCode, String seqType) {
        // Kunci baris turutan (SELECT ... FOR UPDATE)
        Object[] row;
        try {
            row = (Object[]) em.createNativeQuery("""
                SELECT id, prefix, suffix, next_value, padding
                FROM document_number_sequence
                WHERE sp_code = :sp AND seq_type = :type
                FOR UPDATE
                """)
                .setParameter("sp", spCode)
                .setParameter("type", seqType)
                .getSingleResult();
        } catch (jakarta.persistence.NoResultException e) {
            // Cipta turutan default jika belum ada
            em.createNativeQuery("""
                INSERT INTO document_number_sequence (sp_code, seq_type, prefix, next_value, padding, version)
                VALUES (:sp, :type, :prefix, 1, 6, 0)
                """)
                .setParameter("sp", spCode)
                .setParameter("type", seqType)
                .setParameter("prefix", switch (seqType) { case "INVOICE" -> "INV"; case "RECEIPT" -> "RCP"; case "CREDIT_NOTE" -> "CN"; case "DEBIT_NOTE" -> "DN"; default -> "DOC"; })
                .executeUpdate();
            return next(spCode, seqType);
        }

        Long id = ((Number) row[0]).longValue();
        String prefix = row[1] == null ? "" : row[1].toString();
        String suffix = row[2] == null ? "" : row[2].toString();
        long value = ((Number) row[3]).longValue();
        int padding = ((Number) row[4]).intValue();

        em.createNativeQuery("UPDATE document_number_sequence SET next_value = next_value + 1 WHERE id = :id")
                .setParameter("id", id)
                .executeUpdate();

        return prefix + String.format("%0" + padding + "d", value) + suffix;
    }
}
