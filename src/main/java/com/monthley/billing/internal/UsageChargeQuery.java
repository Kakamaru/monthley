package com.monthley.billing.internal;

import com.monthley.shared.Charge;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Caj berasaskan penggunaan yang belum dibil (V58).
 *
 * Kerani memuat naik Excel dengan kuantiti atau amaun per akaun; baris
 * duduk sebagai PENDING sehingga bil dijana. Larian SETERUSNYA menyapu
 * semuanya, tanpa mengira tempoh yang ditanda pada baris.
 *
 * PERIOD DARIPADA BARIS, BUKAN DARIPADA MOD BIL. Dua muat naik untuk
 * produk yang sama — Jun dan Julai — menghasilkan DUA baris invois
 * dalam larian yang sama, satu bertanda Jun dan satu bertanda Julai.
 *
 * onceOnly = FALSE walaupun caj ini tidak berulang.
 *
 * idem_key untuk baris onceOnly menyekat ikut (akaun, produk) TANPA
 * tempoh — usage Julai akan digugurkan secara senyap kerana usage Jun
 * sudah wujud untuk produk yang sama. Dengan false, kekangan
 * menggunakan period_start, dan dua tempoh berbeza ialah dua caj
 * berbeza. Itu betul.
 */
@Component
class UsageChargeQuery {

    @PersistenceContext
    private EntityManager em;

    /** Satu baris PENDING = satu CalculatedLine. */
    record Baris(long id, CalculatedLine line) {}

    @SuppressWarnings("unchecked")
    /**
     * GL hasil datang daripada PRODUK, sama seperti baris langganan
     * (InvoiceCalculator). Null bermakna guna lalai SP.
     */
    List<Baris> pendingFor(String spCode, long accountId) {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT u.id, u.product_id, u.period_id, u.quantity, u.amount,
                       p.name AS descr, u.remarks AS remarks,
                       p.unit_rate, f.start_dt, f.end_dt, p.income_gl_account_id
                FROM   account_usage_charge u
                JOIN   product   p ON p.id = u.product_id
                JOIN   fi_period f ON f.period_id = u.period_id
                WHERE  u.sp_code = :sp
                  AND  u.account_id = :acc
                  AND  u.status = 'PENDING'
                ORDER  BY f.start_dt, p.code
                """)
                .setParameter("sp", spCode)
                .setParameter("acc", accountId)
                .getResultList();

        List<Baris> out = new ArrayList<>();
        for (Object[] r : rows) {
            LocalDate mula = tarikh(r[8]);
            LocalDate tamat = tarikh(r[9]);

            // Liputan = kitaran penuh. Caj penggunaan tidak diprorata:
            // kuantiti SUDAH mewakili apa yang digunakan.
            Charge charge = new Charge(((Number) r[2]).longValue(),
                    mula, tamat, mula, tamat);

            out.add(new Baris(((Number) r[0]).longValue(), new CalculatedLine(
                    ((Number) r[1]).longValue(),
                    accountId,
                    charge,
                    // description = nama PRODUK; catatan mempunyai
                    // lajurnya sendiri (V59). Sebelum ini catatan
                    // menggantikan nama produk, dan satu lajur yang
                    // bermakna dua perkara tidak boleh disoal.
                    (String) r[5],
                    (String) r[6],
                    (BigDecimal) r[3],
                    (BigDecimal) r[7],
                    BigDecimal.ONE,
                    (BigDecimal) r[4],
                    BigDecimal.ZERO,
                    r[10] == null ? null : ((Number) r[10]).longValue(),
                    false)));
        }
        return out;
    }

    /** Tandakan baris sebagai sudah dibil. */
    void tandaInvois(List<Long> ids, long documentId) {
        if (ids.isEmpty()) return;
        em.createNativeQuery("""
                UPDATE account_usage_charge
                SET    status = 'INVOICED', document_id = :doc, invoiced_at = NOW()
                WHERE  id IN (:ids)
                """)
                .setParameter("doc", documentId)
                .setParameter("ids", ids)
                .executeUpdate();
    }

    /** Connector/J memulangkan LocalDate untuk DATE, bukan java.sql.Date. */
    private static LocalDate tarikh(Object v) {
        if (v instanceof LocalDate d) return d;
        if (v instanceof java.sql.Date d) return d.toLocalDate();
        return LocalDate.parse(String.valueOf(v));
    }
}
