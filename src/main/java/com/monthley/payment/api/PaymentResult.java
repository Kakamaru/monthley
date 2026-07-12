package com.monthley.payment.api;

import java.math.BigDecimal;

public record PaymentResult(
        Long receiptId,
        String receiptNo,
        BigDecimal allocated,
        BigDecimal deposit) {   // lebihan → customer deposit
}
