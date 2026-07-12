package com.monthley.payment.api;

import java.math.BigDecimal;

public class PaymentBelowMinimumException extends RuntimeException {
    public PaymentBelowMinimumException(BigDecimal amount, BigDecimal minimum) {
        super("Bayaran RM" + amount + " kurang daripada minimum RM" + minimum);
    }
}
