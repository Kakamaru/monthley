package com.monthley.statement.internal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Baca account_document_entry (V33) SAHAJA.
 *
 * Query di sini TIDAK PERNAH menyebut doc_type. Tanda dokumen dan
 * kemasukan cukai ditakrif sekali dalam VIEW; mengulanginya di sini
 * akan mencipta takrifan kedua (guard 6).
 *
 * JdbcClient dan bukan JPA kerana ini unjuran baca-sahaja atas VIEW,
 * bukan aggregate. Memetakan VIEW sebagai @Entity mencipta entiti yang
 * Hibernate sangka boleh disimpan, dan SUM(...) OVER (...) tidak wujud
 * dalam JPQL.
 */
@Repository
class StatementQuery {

    private final JdbcClient jdbc;

    StatementQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Baki sebelum tarikh mula — baki bawa ke hadapan. */
    BigDecimal openingBalance(String spCode, long accountId, LocalDate from) {
        return jdbc.sql("""
                SELECT COALESCE(SUM(e.signed_amount), 0)
                FROM   account_document_entry e
                WHERE  e.sp_code    = :sp
                  AND  e.account_id = :acc
                  AND  e.doc_date   < :from
                """)
                .param("sp", spCode)
                .param("acc", accountId)
                .param("from", from)
                .query(BigDecimal.class)
                .single();
    }

    /**
     * Baris dokumen dengan baki berjalan. Satu pass, satu ordering.
     * Baki bermula daripada opening, jadi baris pertama sudah betul.
     */
    List<DocumentEntry> entries(String spCode, long accountId,
                                LocalDate from, LocalDate to, BigDecimal opening) {
        return jdbc.sql("""
                SELECT e.doc_date,
                       e.doc_type,
                       e.doc_no,
                       e.title,
                       e.cancel_reason,
                       e.status,
                       e.signed_amount,
                       :opening + SUM(e.signed_amount)
                           OVER (ORDER BY e.doc_date, e.document_id) AS running_balance
                FROM   account_document_entry e
                WHERE  e.sp_code    = :sp
                  AND  e.account_id = :acc
                  AND  e.doc_date  >= :from
                  AND  e.doc_date  <= :to
                ORDER  BY e.doc_date, e.document_id
                """)
                .param("sp", spCode)
                .param("acc", accountId)
                .param("from", from)
                .param("to", to)
                .param("opening", opening)
                .query((rs, n) -> new DocumentEntry(
                        rs.getDate("doc_date").toLocalDate(),
                        rs.getString("doc_type"),
                        rs.getString("doc_no"),
                        rs.getString("title"),
                        rs.getString("cancel_reason"),
                        "CANCELLED".equals(rs.getString("status")),
                        rs.getBigDecimal("signed_amount"),
                        rs.getBigDecimal("running_balance")))
                .list();
    }

    record DocumentEntry(LocalDate docDate, String docType, String docNo,
                         String title, String cancelReason, boolean cancelled,
                         BigDecimal signedAmount, BigDecimal runningBalance) {
    }
}
