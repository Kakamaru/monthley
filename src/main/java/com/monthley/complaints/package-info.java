/**
 * Complaints — aduan pelanggan: kategori, aduan, thread balasan.
 *
 * Modul memiliki data operasinya sendiri (adu_*). Berbeza daripada
 * Perbelanjaan, ia tidak mempos ke ledger — aduan bukan peristiwa
 * kewangan.
 *
 * Setiap endpoint TULIS dilindungi ModuleGuard.require(ADUAN); endpoint
 * baca dibenarkan dan memulangkan keadaan kosong (ADR 0016).
 *
 * account :: api diperlukan supaya pelanggan boleh memilih akaun yang
 * mereka bayar — aduan dipaut kepada akaun, bukan kepada SP secara terus.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Complaints",
        allowedDependencies = { "shared", "document :: api", "account :: api" })
package com.monthley.complaints;
