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

    /** Kepala daripada VIEW statement_header. */
    com.monthley.statement.api.StatementHeader header(String spCode, long accountId) {
        return jdbc.sql("""
                SELECT * FROM statement_header
                WHERE sp_code = :sp AND account_id = :acc
                """)
                .param("sp", spCode)
                .param("acc", accountId)
                .query((rs, n) -> new com.monthley.statement.api.StatementHeader(
                        rs.getString("statement_title"),
                        rs.getString("currency"),
                        rs.getString("language"),
                        rs.getString("date_format"),
                        rs.getString("tax_name"),
                        rs.getString("sp_name"),
                        rs.getString("sp_registration_no"),
                        rs.getString("sp_addr_line1"),
                        rs.getString("sp_addr_line2"),
                        rs.getString("sp_addr_line3"),
                        rs.getString("sp_postcode"),
                        rs.getString("sp_city"),
                        rs.getString("sp_state"),
                        rs.getString("sp_country"),
                        rs.getString("sp_phone"),
                        rs.getString("sp_website"),
                        rs.getString("sp_email"),
                        rs.getString("sp_helpdesk_email"),
                        rs.getString("sp_helpdesk_phone"),
                        rs.getString("sp_logo_url"),
                        rs.getString("sp_bank_code"),
                        rs.getString("sp_bank_account_no"),
                        rs.getString("sp_bank_account_name"),
                        rs.getString("account_no"),
                        rs.getString("account_name"),
                        rs.getString("member_name"),
                        rs.getString("billto_name"),
                        rs.getString("billto_email"),
                        rs.getString("billto_addr_line1"),
                        rs.getString("billto_addr_line2"),
                        rs.getString("billto_addr_line3"),
                        rs.getString("billto_postcode"),
                        rs.getString("billto_state"),
                        rs.getString("billto_country")))
                .single();
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
                SELECT e.document_id,
                       e.doc_date,
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
                        rs.getLong("document_id"),
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

    /**
     * Padanan alokasi untuk julat yang sama, kedua-dua arah sekali.
     *
     * Satu query; pemanggil mengindeksnya mengikut sisi mana yang menjadi
     * baris penyata. Baris RESIT menunjukkan invois yang dibayarnya; baris
     * INVOIS menunjukkan resit yang membayarnya. Legacy hanya boleh yang
     * pertama.
     *
     * Tempoh datang daripada BARIS invois, bukan dokumen — satu invois boleh
     * membawa dua belas baris bulanan.
     */
    List<AllocationMatch> matches(String spCode, long accountId,
                                  LocalDate from, LocalDate to) {
        return jdbc.sql("""
                SELECT m.credit_document_id,
                       m.debit_document_id,
                       m.credit_doc_no,
                       m.debit_doc_no,
                       m.debit_period_start,
                       m.debit_period_end,
                       COALESCE(m.product_name, m.line_description, m.debit_title)
                           AS keterangan,
                       m.amount
                FROM   account_allocation_match m
                WHERE  m.sp_code    = :sp
                  AND  m.account_id = :acc
                  AND  (m.credit_doc_date BETWEEN :from AND :to
                        OR m.debit_doc_date BETWEEN :from AND :to)
                ORDER  BY m.debit_period_start, m.debit_doc_no,
                          m.debit_document_line_id
                """)
                .param("sp", spCode)
                .param("acc", accountId)
                .param("from", from)
                .param("to", to)
                .query((rs, n) -> new AllocationMatch(
                        rs.getLong("credit_document_id"),
                        rs.getLong("debit_document_id"),
                        rs.getString("credit_doc_no"),
                        rs.getString("debit_doc_no"),
                        rs.getDate("debit_period_start") != null
                                ? rs.getDate("debit_period_start").toLocalDate() : null,
                        rs.getDate("debit_period_end") != null
                                ? rs.getDate("debit_period_end").toLocalDate() : null,
                        rs.getString("keterangan"),
                        rs.getBigDecimal("amount")))
                .list();
    }

    record AllocationMatch(long creditDocumentId, long debitDocumentId,
                           String creditDocNo, String debitDocNo,
                           LocalDate periodStart, LocalDate periodEnd,
                           String description, BigDecimal amount) {
    }

    record DocumentEntry(long documentId, LocalDate docDate, String docType, String docNo,
                         String title, String cancelReason, boolean cancelled,
                         BigDecimal signedAmount, BigDecimal runningBalance) {
    }
}
