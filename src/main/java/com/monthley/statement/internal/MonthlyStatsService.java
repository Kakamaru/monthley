package com.monthley.statement.internal;

import com.monthley.statement.api.MonthlyStatsPort;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Statistik bulanan.
 *
 * KUTIPAN DIPECAHKAN, TIDAK DILETAK BERSEBELAHAN INVOIS.
 *
 * Laporan legacy menunjukkan 'Jumlah Invois RM31,416' di sebelah
 * 'Jumlah Resit RM43,700' untuk bulan yang sama. Kedua-duanya betul —
 * resit Julai boleh membayar invois Mac — tetapi bersebelahan tanpa
 * penjelasan ia kelihatan seperti JMB mengutip lebih daripada yang
 * dibil.
 *
 * Di sini kutipan dipecah kepada 'untuk tempoh ini' dan 'tunggakan
 * lama', menggunakan tempoh INVOIS yang dilangsaikan — takrifan yang
 * sama seperti Monthly Basis dalam Senarai Kutipan.
 */
@Service
class MonthlyStatsService implements MonthlyStatsPort {

    @PersistenceContext
    private EntityManager em;

    private static final String[] BULAN = {
            "Januari", "Februari", "Mac", "April", "Mei", "Jun",
            "Julai", "Ogos", "September", "Oktober", "November", "Disember" };

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Stats monthly(String sp, int year, int month) {
        LocalDate from = LocalDate.of(year, month, 1);
        LocalDate to = from.withDayOfMonth(from.lengthOfMonth());
        LocalDate today = LocalDate.now();
        LocalDate asAt = today.isBefore(to) ? today : to;

        // ── Invois dijana dalam tempoh ─────────────────────────────
        Object[] inv = (Object[]) em.createNativeQuery("""
                SELECT COUNT(*), COALESCE(SUM(d.amount + d.tax_amount), 0)
                FROM   financial_document d
                WHERE  d.sp_code = :sp AND d.doc_type IN ('INVOICE','DEBIT_NOTE')
                  AND  d.status <> 'CANCELLED'
                  AND  d.doc_date BETWEEN :from AND :to
                """).setParameter("sp", sp)
                .setParameter("from", from).setParameter("to", to)
                .getSingleResult();

        // ── Resit dalam tempoh ─────────────────────────────────────
        Object[] rcp = (Object[]) em.createNativeQuery("""
                SELECT COUNT(*), COALESCE(SUM(d.amount + d.tax_amount), 0)
                FROM   financial_document d
                WHERE  d.sp_code = :sp AND d.doc_type = 'RECEIPT'
                  AND  d.status <> 'CANCELLED'
                  AND  d.doc_date BETWEEN :from AND :to
                """).setParameter("sp", sp)
                .setParameter("from", from).setParameter("to", to)
                .getSingleResult();

        // ── Pecahan kutipan: tempoh ini vs tunggakan lama ──────────
        //
        // Menggunakan tempoh INVOIS yang dilangsaikan, bukan tarikh
        // bayaran — takrifan yang sama seperti Monthly Basis.
        Object[] pecah = (Object[]) em.createNativeQuery("""
                SELECT COALESCE(SUM(CASE WHEN m.debit_period_start BETWEEN :from AND :to
                                         THEN m.amount ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN m.debit_period_start IS NULL
                                          OR m.debit_period_start NOT BETWEEN :from AND :to
                                         THEN m.amount ELSE 0 END), 0)
                FROM   account_allocation_match m
                JOIN   financial_document d ON d.id = m.credit_document_id
                WHERE  m.sp_code = :sp AND d.doc_type = 'RECEIPT'
                  AND  d.status <> 'CANCELLED'
                  AND  d.doc_date BETWEEN :from AND :to
                """).setParameter("sp", sp)
                .setParameter("from", from).setParameter("to", to)
                .getSingleResult();

        BigDecimal billed = (BigDecimal) inv[1];
        BigDecimal collected = (BigDecimal) rcp[1];
        BigDecimal thisPeriod = (BigDecimal) pecah[0];
        BigDecimal arrearsPaid = (BigDecimal) pecah[1];

        BigDecimal rate = billed.signum() == 0 ? BigDecimal.ZERO
                : thisPeriod.multiply(BigDecimal.valueOf(100))
                            .divide(billed, 1, RoundingMode.HALF_UP);

        // ── Tunggakan pada hujung tempoh dan tempoh sebelumnya ─────
        BigDecimal arrears = tunggakan(sp, asAt);
        BigDecimal arrearsPrev = tunggakan(sp, from.minusDays(1));

        Object[] akaun = (Object[]) em.createNativeQuery("""
                SELECT COUNT(*),
                       SUM(CASE WHEN COALESCE(b.balance,0) > 0.005 THEN 1 ELSE 0 END)
                FROM   account a
                LEFT   JOIN account_balance b ON b.account_id = a.id
                WHERE  a.sp_code = :sp AND a.status = 'ACTIVE'
                  AND  COALESCE(a.account_type,'') <> 'ADHOC'
                """).setParameter("sp", sp).getSingleResult();

        return new Stats(
                BULAN[month - 1] + " " + year, from, to, asAt,
                ((Number) inv[0]).intValue(), billed,
                ((Number) rcp[0]).intValue(), collected,
                thisPeriod, arrearsPaid, rate,
                arrears, arrearsPrev,
                ((Number) akaun[0]).intValue(),
                akaun[1] == null ? 0 : ((Number) akaun[1]).intValue(),
                trend(sp, from),
                slice(sp, from, to, true),
                slice(sp, from, to, false),
                topArrears(sp, asAt),
                longestSilent(sp, asAt));
    }

    /** Baki pada satu tarikh — takrifan sama seperti Senarai Tunggakan. */
    private BigDecimal tunggakan(String sp, LocalDate asAt) {
        Object v = em.createNativeQuery("""
                SELECT COALESCE(SUM(e.signed_amount), 0)
                FROM   account_document_entry e
                JOIN   account a ON a.id = e.account_id
                WHERE  e.sp_code = :sp AND e.doc_date <= :asAt
                  AND  COALESCE(a.account_type,'') <> 'ADHOC'
                """).setParameter("sp", sp).setParameter("asAt", asAt)
                .getSingleResult();
        return new BigDecimal(v.toString());
    }

    /** Dua belas bulan hingga tempoh dipilih. */
    @SuppressWarnings("unchecked")
    private List<MonthPoint> trend(String sp, LocalDate akhir) {
        LocalDate mula = akhir.minusMonths(11);
        List<Object[]> rows = em.createNativeQuery("""
                SELECT DATE_FORMAT(d.doc_date, '%Y-%m') AS bln,
                       COALESCE(SUM(CASE WHEN d.doc_type IN ('INVOICE','DEBIT_NOTE')
                                         THEN d.amount + d.tax_amount ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN d.doc_type = 'RECEIPT'
                                         THEN d.amount + d.tax_amount ELSE 0 END), 0)
                FROM   financial_document d
                WHERE  d.sp_code = :sp AND d.status <> 'CANCELLED'
                  AND  d.doc_date >= :mula AND d.doc_date <= :akhir
                GROUP  BY bln ORDER BY bln
                """).setParameter("sp", sp)
                .setParameter("mula", mula.withDayOfMonth(1))
                .setParameter("akhir", akhir.withDayOfMonth(akhir.lengthOfMonth()))
                .getResultList();

        // Bulan tanpa aktiviti mesti muncul sebagai SIFAR, bukan hilang:
        // jurang dalam carta bar kelihatan seperti data yang rosak.
        java.util.Map<String, Object[]> ikut = new java.util.HashMap<>();
        for (Object[] r : rows) ikut.put((String) r[0], r);

        List<MonthPoint> hasil = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            LocalDate m = mula.plusMonths(i);
            String kunci = String.format("%d-%02d", m.getYear(), m.getMonthValue());
            Object[] r = ikut.get(kunci);
            hasil.add(new MonthPoint(
                    BULAN[m.getMonthValue() - 1].substring(0, 3) + " " + (m.getYear() % 100),
                    r == null ? BigDecimal.ZERO : (BigDecimal) r[1],
                    r == null ? BigDecimal.ZERO : (BigDecimal) r[2]));
        }
        return hasil;
    }

    /** Pecahan kutipan ikut jenis bayaran atau ikut produk. */
    @SuppressWarnings("unchecked")
    private List<Slice> slice(String sp, LocalDate from, LocalDate to, boolean ikutBayaran) {
        String sql = ikutBayaran ? """
                SELECT COALESCE(p.method, '(tidak dinyatakan)'),
                       COALESCE(SUM(d.amount + d.tax_amount), 0) AS jum
                FROM   financial_document d
                LEFT   JOIN payment p ON p.receipt_document_id = d.id
                WHERE  d.sp_code = :sp AND d.doc_type = 'RECEIPT'
                  AND  d.status <> 'CANCELLED'
                  AND  d.doc_date BETWEEN :from AND :to
                GROUP  BY p.method ORDER BY jum DESC
                """ : """
                SELECT COALESCE(m.product_name, m.line_description, '(lain-lain)'),
                       COALESCE(SUM(m.amount), 0) AS jum
                FROM   account_allocation_match m
                JOIN   financial_document d ON d.id = m.credit_document_id
                WHERE  m.sp_code = :sp AND d.doc_type = 'RECEIPT'
                  AND  d.status <> 'CANCELLED'
                  AND  d.doc_date BETWEEN :from AND :to
                GROUP  BY 1 ORDER BY jum DESC
                """;

        List<Object[]> rows = em.createNativeQuery(sql)
                .setParameter("sp", sp)
                .setParameter("from", from).setParameter("to", to)
                .getResultList();

        List<Slice> hasil = new ArrayList<>();
        for (Object[] r : rows) hasil.add(new Slice((String) r[0], (BigDecimal) r[1]));
        return hasil;
    }

    @SuppressWarnings("unchecked")
    private List<TopAccount> topArrears(String sp, LocalDate asAt) {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT a.account_no,
                       COALESCE(NULLIF(a.billto_name,''), a.account_name),
                       SUM(e.signed_amount) AS baki
                FROM   account_document_entry e
                JOIN   account a ON a.id = e.account_id
                WHERE  e.sp_code = :sp AND e.doc_date <= :asAt
                  AND  COALESCE(a.account_type,'') <> 'ADHOC'
                GROUP  BY a.id, a.account_no, a.billto_name, a.account_name
                HAVING baki > 0.005
                ORDER  BY baki DESC LIMIT 5
                """).setParameter("sp", sp).setParameter("asAt", asAt)
                .getResultList();

        List<TopAccount> hasil = new ArrayList<>();
        for (Object[] r : rows) {
            hasil.add(new TopAccount((String) r[0], (String) r[1],
                    (BigDecimal) r[2], null));
        }
        return hasil;
    }

    /**
     * Akaun yang paling lama tidak membuat bayaran.
     *
     * Menggantikan '5 akaun terawal membuat bayaran' legacy, yang tidak
     * membantu sebarang keputusan. Ini menjawab siapa yang sudah lama
     * senyap — dan itu senarai yang JMB hubungi.
     *
     * Akaun yang TIDAK PERNAH membayar didahulukan.
     */
    @SuppressWarnings("unchecked")
    private List<TopAccount> longestSilent(String sp, LocalDate asAt) {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT a.account_no,
                       COALESCE(NULLIF(a.billto_name,''), a.account_name),
                       COALESCE(b.balance, 0),
                       MAX(d.doc_date) AS terakhir
                FROM   account a
                LEFT   JOIN account_balance b ON b.account_id = a.id
                LEFT   JOIN financial_document d
                       ON d.account_id = a.id AND d.doc_type = 'RECEIPT'
                      AND d.status <> 'CANCELLED' AND d.doc_date <= :asAt
                WHERE  a.sp_code = :sp AND a.status = 'ACTIVE'
                  AND  COALESCE(a.account_type,'') <> 'ADHOC'
                  AND  COALESCE(b.balance, 0) > 0.005
                GROUP  BY a.id, a.account_no, a.billto_name, a.account_name, b.balance
                ORDER  BY terakhir IS NULL DESC, terakhir ASC LIMIT 5
                """).setParameter("sp", sp).setParameter("asAt", asAt)
                .getResultList();

        List<TopAccount> hasil = new ArrayList<>();
        for (Object[] r : rows) {
            String nota = r[3] == null ? "Tiada bayaran direkod"
                    : "Bayaran terakhir " + r[3];
            hasil.add(new TopAccount((String) r[0], (String) r[1],
                    (BigDecimal) r[2], nota));
        }
        return hasil;
    }
}
