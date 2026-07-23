package com.monthley.payment.internal;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Menentukan pecahan amaun kepada line bagi SATU dokumen (ADR 0006).
 *
 * Digunakan oleh tiga laluan:
 *   1. PaymentService     — bayaran baharu
 *   2. AdjustmentService  — pelarasan
 *   3. Backfill           — alokasi sedia ada
 *
 * Baki terbuka setiap line = (amount + tax_amount) - SUM(alokasi ACTIVE line).
 * Dokumen tanpa line (DEBIT_NOTE/CREDIT_NOTE) mengembalikan senarai kosong,
 * jadi keseluruhan amaun jadi {@code unallocated} — pemanggil merekodkannya
 * sebagai alokasi peringkat dokumen (lineId null).
 *
 * Rujukan: docs/decisions/0006-line-level-allocation-plan.md
 */
@Component
class LineAllocationResolver {

    @PersistenceContext
    private EntityManager em;

    /**
     * Agih {@code amount} merentas line terbuka dokumen {@code documentId}.
     * Tidak menulis apa-apa — hanya mengira.
     */
    @SuppressWarnings("unchecked")
    LineFifoAllocator.Result resolve(Long documentId, BigDecimal amount) {
        return LineFifoAllocator.allocate(amount, openLines(documentId));
    }

    /** Line yang masih ada baki, untuk satu dokumen. */
    @SuppressWarnings("unchecked")
    List<LineFifoAllocator.OpenLine> openLines(Long documentId) {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT l.id,
                       l.period_start,
                       (l.amount + l.tax_amount) - COALESCE((
                           SELECT SUM(a.amount) FROM fi_allocation a
                           WHERE a.debit_document_line_id = l.id
                             AND a.status = 'ACTIVE'), 0) AS baki
                FROM financial_document_line l
                WHERE l.document_id = :doc AND l.active = 1
                """)
                .setParameter("doc", documentId)
                .getResultList();

        List<LineFifoAllocator.OpenLine> out = new ArrayList<>();
        for (Object[] r : rows) {
            out.add(new LineFifoAllocator.OpenLine(
                    ((Number) r[0]).longValue(),
                    toLocalDate(r[1]),
                    r[2] == null ? BigDecimal.ZERO : new BigDecimal(r[2].toString())));
        }
        return out;
    }

    /** Pemandu MySQL boleh pulangkan LocalDate / java.sql.Date — terima semua. */
    private static LocalDate toLocalDate(Object o) {
        if (o == null) return null;
        if (o instanceof LocalDate d) return d;
        if (o instanceof Date d) return d.toLocalDate();
        if (o instanceof java.time.LocalDateTime dt) return dt.toLocalDate();
        return null;
    }
}
