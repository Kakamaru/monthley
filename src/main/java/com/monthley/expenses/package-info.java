/**
 * Expenses — perbelanjaan: pembekal, invois belian, baucar bayaran,
 * bayaran terus, dan buku tunai.
 *
 * Modul memiliki data operasinya sendiri (exp_*) dan TIDAK menyentuh
 * financial_document: invois pembekal ialah hutang KEPADA pihak lain, dan
 * memasukkannya ke sana bermakna setiap query jualan sedia ada perlu
 * menapis jenis dokumen.
 *
 * Tetapi ia mempos ke ledger YANG SAMA (ADR 0017). Untung Rugi mempunyai
 * pendapatan di atas dan perbelanjaan di tengah; kalau perbelanjaan
 * mempunyai lejar sendiri, penyata itu tidak boleh dibuktikan seimbang.
 *
 * Setiap endpoint TULIS dilindungi ModuleGuard.require(PERBELANJAAN).
 * Endpoint baca dibenarkan dan memulangkan keadaan kosong — 'benarkan
 * masuk, sekat transaksi' (ADR 0016).
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Expenses",
        allowedDependencies = { "shared", "ledger :: api", "document :: api" })
package com.monthley.expenses;
