package com.monthley.account.api;

import com.monthley.shared.ChargeFrequency;
import java.time.LocalDate;

public record AccountView(
        Long id,
        String spCode,
        String accountNo,
        String accountName,
        ChargeFrequency chargeFrequency,   // berapa banyak invois dijana
        LocalDate startDate,
        LocalDate expiryDate,
        boolean active) {
}
