package com.monthley.ledger.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Senarai Kutipan — data sahaja, tiada rendering.
 *
 * Modul ledger memiliki makna journal_line, status REVERSED, dan
 * mengapa DRAFT dikecualikan. Kalau modul lain menyoal jadual itu
 * terus, peraturan tersebut wujud di dua tempat dan yang kedua akan
 * menyimpang — kami baru membetulkan pepijat sedemikian pagi ini.
 *
 * Jadi: ledger memulangkan baris, statement merendernya.
 */
public interface CollectionReportPort {

    /** Satu baris — resit, atau alokasi produk bergantung pada bentuk. */
    record Row(LocalDate date, String receiptNo, String accountNo,
               String issuedTo, String description, String status,
               String paymentType, String productName, BigDecimal amount) {}

    record Summary(String label, int count, BigDecimal amount) {}

    /**
     * @param byProduct     satu baris per ALOKASI produk, bukan per resit
     * @param monthlyBasis  hanya alokasi yang tempoh INVOISnya jatuh
     *                      dalam julat — jumlah sengaja tidak sepadan
     *                      dengan jumlah resit
     */
    record Query(String spCode, LocalDate from, LocalDate to,
                 boolean byProduct, boolean monthlyBasis,
                 String status, String paymentType, Long productId) {}

    record Result(LocalDate from, LocalDate to, boolean byProduct,
                  boolean monthlyBasis, String productLabel,
                  List<Row> rows, List<Summary> summary, BigDecimal total) {}

    Result collection(Query q);
}
