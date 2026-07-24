package com.monthley.payment.api;

import java.math.BigDecimal;

/**
 * Guna advance sedia ada untuk invois yang baru dicipta (ADR 0009 P3).
 *
 * Permukaan sengaja SEMPIT: billing tidak tahu apa itu alokasi, FIFO, atau
 * line. Ia hanya memberitahu bahawa invois baharu wujud.
 *
 * Nota tentang BAKI: selepas ADR 0009, baki akaun sudah betul tanpa langkah
 * ini — baki ialah dokumen debit tolak dokumen kredit, dan resit advance
 * sudah dikira sebagai kredit. Yang ini menambah PADANAN: resit mana
 * membayar invois mana.
 *
 * Padanan itu penting kerana tanpanya, invois yang sudah ditampung advance
 * masih kelihatan belum dibayar dalam Manual Payment — dan kerani boleh
 * menerima bayaran KEDUA untuk invois yang sama.
 */
public interface AdvancePort {

    /**
     * Gunakan advance akaun (jika ada) terhadap invois yang baru dicipta.
     *
     * Mesti dipanggil dalam transaksi yang SAMA dengan penciptaan invois —
     * lihat accounting-invariants.md §7: kesan kewangan segerak, jangan
     * jadikan event.
     *
     * @return amaun advance yang digunakan; ZERO jika tiada
     */
    BigDecimal applyAdvance(String spCode, Long accountId, Long invoiceDocumentId);
}
