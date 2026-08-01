package com.monthley.notification.internal;

/**
 * Menukar baris outbox menjadi e-mel yang dihantar.
 *
 * Dipisahkan daripada EmailOutboxSender kerana keduanya berubah atas
 * sebab berbeza: sender menguruskan MEKANIK gilir (batch, transaksi,
 * cuba semula), renderer menguruskan KANDUNGAN setiap jenis.
 *
 * Setiap Kind ditambah dalam fasanya sendiri (ADR 0014):
 *
 *   GENERATION_REPORT  P2 — ringkasan larian kepada admin SP
 *   STATEMENT          P4 — perlukan statement_access_token (P3)
 *   REMINDER           P7
 */
interface EmailOutboxRenderer {

    /**
     * Render dan hantar satu baris.
     *
     * Melontar apabila penghantaran gagal — sender menangkapnya dan
     * menandakan baris untuk dicuba semula. Pulang secara normal
     * bermakna e-mel keluar.
     */
    void render(EmailOutbox baris);
}
