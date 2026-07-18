package com.monthley.tenancy.internal;

import com.monthley.tenancy.api.BillingSettingsPort;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
class BillingSettingsService implements BillingSettingsPort {

    @PersistenceContext private EntityManager em;

    @Override
    @Transactional(readOnly = true)
    public BillingSettings forSp(String spCode) {
        Object[] r = (Object[]) em.createNativeQuery("""
                SELECT d.invoice_gen_mode,
                       b.tax_rate,
                       b.payment_term_days,
                       b.ar_gl_account_id,
                       b.income_gl_account_id,
                       b.smallest_denomination,
                       d.allow_price_override
                FROM sp_document_setting d
                JOIN sp_billing_setting b ON b.sp_code = d.sp_code
                WHERE d.sp_code = :sp
                """).setParameter("sp", spCode).getSingleResult();

        return new BillingSettings(
                (String) r[0],
                r[1] == null ? BigDecimal.ZERO : (BigDecimal) r[1],
                r[2] == null ? 14 : ((Number) r[2]).intValue(),
                r[3] == null ? null : ((Number) r[3]).longValue(),
                r[4] == null ? null : ((Number) r[4]).longValue(),
                r[5] == null ? BigDecimal.ZERO : (BigDecimal) r[5],
                toBool(r[6]));
    }

    /** TINYINT(1) datang sebagai Boolean atau Number bergantung pemandu/nilai. */
    private static boolean toBool(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean b) return b;
        return ((Number) o).intValue() == 1;
    }
}
