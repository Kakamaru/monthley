package com.monthley.gateway.api;

import java.math.BigDecimal;

/**
 * Kontrak gerbang bayaran.
 *
 * Wujud supaya menukar gerbang bermakna satu kelas baharu dan bukan
 * menulis semula modul. ToyyibPay ialah pelaksanaan pertama; MonthleyPay
 * menyusul apabila algoritma checksumnya diketahui.
 *
 * Kontrak ini menyatakan apa yang SETIAP gerbang mesti berikan, dan ADR
 * 0007 menetapkan cara menggunakannya: amaun diambil daripada gerbang,
 * handler stateless, idempotency pada rujukan, reconciliation harian.
 */
public interface GatewayPort {

    /** Kod gerbang yang dikendalikan pelaksanaan ini — 'TP', 'MP'. */
    String code();

    record NewBill(
            String spCode,
            String ourRef,          // rujukan kita; kembali dalam callback
            String payerName,
            String payerEmail,
            String payerPhone,
            BigDecimal amount,
            String description,
            String returnUrl,       // pelayar selepas bayar
            String callbackUrl      // server-ke-server
    ) {}

    /** @return kod bil gerbang dan URL untuk pelanggan bayar */
    record BillCreated(String billCode, String paymentUrl) {}

    BillCreated createBill(NewBill req);

    /**
     * Transaksi bagi satu bil, terus daripada gerbang.
     *
     * ToyyibPay TIDAK menandatangani callbacknya. Tanpa tandatangan,
     * satu-satunya cara mengesahkan bayaran benar-benar berlaku ialah
     * bertanya kepada gerbang — kalau tidak, sesiapa yang tahu URL callback
     * boleh mencipta resit untuk bayaran yang tidak wujud.
     */
    record BillTxn(
            boolean paid,
            String gatewayRef,      // rujukan transaksi bank
            BigDecimal paidAmount,  // amaun SEBENAR daripada gerbang
            String status,
            String raw
    ) {}

    BillTxn fetchTransaction(String spCode, String billCode);
}
