/**
 * Memo — hebahan sehala daripada SP kepada pelanggan.
 *
 * Modul paling ringkas: satu jadual, tiada kategori, tiada tetapan, tiada
 * thread. Pelanggan membaca; tiada balasan.
 *
 * account :: api diperlukan untuk mengesahkan pelanggan mempunyai akaun
 * dengan SP sebelum memaparkan memonya — memo bukan awam.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Memo",
        allowedDependencies = { "shared", "account :: api" })
package com.monthley.memo;
