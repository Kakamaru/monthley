package com.monthley.ledger.internal;

import com.monthley.ledger.api.CollectionReportPort;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Senarai Kutipan — dua bentuk, dipilih oleh byProduct.
 *
 * BENTUK A: satu baris per RESIT, diringkaskan mengikut jenis bayaran.
 * Soalannya "apa yang kita kutip".
 *
 * BENTUK B: satu baris per ALOKASI produk, diringkaskan mengikut
 * produk. Resit RM101 yang melangsaikan tiga baris invois muncul TIGA
 * kali dengan bahagiannya. Soalannya "kutipan itu untuk produk apa".
 *
 * MONTHLY BASIS menapis mengikut tempoh INVOIS yang dilangsaikan, bukan
 * tarikh bayaran — daripada yang dikutip bulan ini, berapa untuk bil
 * bulan ini dan berapa untuk tunggakan lama. Ia bekerja pada aras
 * ALOKASI walaupun dalam bentuk A, kerana pemecahan hanya wujud di
 * situ.
 *
 * JENIS BAYARAN daripada jadual payment: financial_document.payment_type
 * kosong untuk setiap resit, kerana tiada laluan menulisnya.
 *
 * NOTA KREDIT dikecualikan — ia mengurangkan hutang tetapi tiada duit
 * masuk, dan memasukkannya menjadikan jumlah tidak sepadan dengan
 * penyata bank.
 */
@Service
class CollectionReportService implements CollectionReportPort {

    @PersistenceContext
    private EntityManager em;

    @Override
    @Transactional(readOnly = true)
    public Result collection(Query q) {
        List<Object[]> rows = (q.byProduct() || q.monthlyBasis())
                ? barisAlokasi(q)
                : barisResit(q);

        List<Row> items = new ArrayList<>();
        Map<String, int[]> kiraan = new LinkedHashMap<>();
        Map<String, BigDecimal> jumlahKumpulan = new LinkedHashMap<>();
        BigDecimal jumlah = BigDecimal.ZERO;

        for (Object[] r : rows) {
            BigDecimal amt = (BigDecimal) r[8];
            jumlah = jumlah.add(amt);
            items.add(new Row(tarikh(r[0]), (String) r[1], (String) r[2],
                    (String) r[3], (String) r[4], (String) r[5],
                    (String) r[6], (String) r[7], amt));

            String kunci = q.byProduct()
                    ? (r[7] == null ? "(tiada produk)" : (String) r[7])
                    : (r[6] == null ? "(tidak dinyatakan)" : (String) r[6]);
            kiraan.computeIfAbsent(kunci, k -> new int[1])[0]++;
            jumlahKumpulan.merge(kunci, amt, BigDecimal::add);
        }

        List<Summary> ringkasan = new ArrayList<>();
        jumlahKumpulan.forEach((k, v) ->
                ringkasan.add(new Summary(k, kiraan.get(k)[0], v)));

        return new Result(q.from(), q.to(), q.byProduct(), q.monthlyBasis(),
                labelProduk(q), items, ringkasan, jumlah);
    }

    /** Tajuk PDF: 'COLLECTION REPORT BY <PRODUK> PRODUCT' bila ditapis. */
    private String labelProduk(Query q) {
        if (q.productId() == null) return null;
        var r = em.createNativeQuery("SELECT name FROM product WHERE id = :id")
                .setParameter("id", q.productId()).getResultList();
        return r.isEmpty() ? null : (String) r.get(0);
    }

    /** Bentuk A — satu baris per resit, amaun penuh. */
    @SuppressWarnings("unchecked")
    private List<Object[]> barisResit(Query q) {
        return em.createNativeQuery("""
                SELECT d.doc_date, d.doc_no,
                       COALESCE(a.account_no, ''),
                       COALESCE(NULLIF(d.issued_to_name,''), a.account_name, ''),
                       CONCAT(COALESCE(d.title,''),
                              IFNULL(CONCAT(' ( ', p.remarks, ' )'), '')),
                       d.status, p.method, NULL,
                       (d.amount + d.tax_amount)
                FROM   financial_document d
                LEFT   JOIN account a ON a.id = d.account_id
                LEFT   JOIN payment p ON p.receipt_document_id = d.id
                WHERE  d.sp_code = :sp
                  AND  d.doc_type = 'RECEIPT'
                  AND  d.doc_date BETWEEN :from AND :to
                  AND  (:status IS NULL OR d.status = :status)
                  AND  (:pt IS NULL OR p.method = :pt)
                ORDER  BY d.doc_date, d.id
                """)
                .setParameter("sp", q.spCode())
                .setParameter("from", q.from()).setParameter("to", q.to())
                .setParameter("status", kosongNull(q.status()))
                .setParameter("pt", kosongNull(q.paymentType()))
                .getResultList();
    }

    /** Bentuk B dan Monthly Basis — satu baris per alokasi. */
    @SuppressWarnings("unchecked")
    private List<Object[]> barisAlokasi(Query q) {
        return em.createNativeQuery("""
                SELECT d.doc_date, m.credit_doc_no,
                       COALESCE(a.account_no, ''),
                       COALESCE(NULLIF(d.issued_to_name,''), a.account_name, ''),
                       CONCAT(COALESCE(d.title,''),
                              IFNULL(CONCAT(' ( ', p.remarks, ' )'), '')),
                       d.status, p.method,
                       COALESCE(m.product_name, m.line_description, m.debit_title),
                       m.amount
                FROM   account_allocation_match m
                JOIN   financial_document d ON d.id = m.credit_document_id
                LEFT   JOIN account a ON a.id = m.account_id
                LEFT   JOIN payment p ON p.receipt_document_id = d.id
                LEFT   JOIN financial_document_line fdl ON fdl.id = m.debit_document_line_id
                WHERE  m.sp_code = :sp
                  AND  d.doc_type = 'RECEIPT'
                  AND  d.doc_date BETWEEN :from AND :to
                  AND  (:status IS NULL OR d.status = :status)
                  AND  (:pt IS NULL OR p.method = :pt)
                  AND  (:prod IS NULL OR fdl.product_id = :prod)
                  -- Monthly Basis: tempoh INVOIS yang dilangsaikan mesti
                  -- jatuh dalam julat laporan. Bayaran kepada tunggakan
                  -- lama gugur.
                  AND  (:monthly = 0 OR m.debit_period_start BETWEEN :from AND :to)
                ORDER  BY d.doc_date, d.id, m.debit_document_line_id
                """)
                .setParameter("sp", q.spCode())
                .setParameter("from", q.from()).setParameter("to", q.to())
                .setParameter("status", kosongNull(q.status()))
                .setParameter("pt", kosongNull(q.paymentType()))
                .setParameter("prod", q.productId())
                .setParameter("monthly", q.monthlyBasis() ? 1 : 0)
                .getResultList();
    }


    private static String kosongNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }

    private static LocalDate tarikh(Object v) {
        if (v == null) return null;
        if (v instanceof LocalDate d) return d;
        if (v instanceof java.sql.Date d) return d.toLocalDate();
        return LocalDate.parse(String.valueOf(v));
    }
}
