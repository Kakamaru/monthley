/**
 * Gateway — bayaran dalam talian.
 *
 * Modul memiliki transaksi gerbang (gateway_txn) dan kelayakan
 * disulitkan. Ia TIDAK memiliki bayaran: transaksi gerbang yang berjaya
 * menghasilkan payment melalui payment :: api, dan payment itulah yang
 * menjadi resit.
 *
 * Pemisahan itu penting kerana kebanyakan transaksi gerbang bukan
 * bayaran — dalam legacy, 12% tidak pernah selesai.
 *
 * Reka bentuk mengikut ADR 0007: amaun diambil daripada gerbang,
 * handler stateless, idempotency pada rujukan, reconciliation harian.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Gateway",
        allowedDependencies = { "shared", "document :: api", "account :: api",
                                "payment :: api" })
package com.monthley.gateway;
