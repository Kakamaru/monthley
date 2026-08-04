package com.monthley.account.internal;

import com.monthley.account.api.AccountListPort;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Senarai Akaun untuk laporan.
 *
 * BAKI DARIPADA account_balance, bukan dikira semula. VIEW itu diterbitkan
 * daripada lapisan tanda (V33) dan sudah disahkan sepadan dengan lejar am
 * dan lejar SP — mengiranya sekali lagi di sini bermakna takrifan keempat
 * bagi 'apa itu baki akaun'.
 *
 * ADHOC-SALES dikecualikan: akaun teknikal untuk jualan tanpa pelanggan
 * berdaftar, bukan pelanggan.
 */
@Service
class AccountListService implements AccountListPort {

    @PersistenceContext
    private EntityManager em;

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Result accountList(Query q) {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT a.account_no,
                       a.account_name,
                       COALESCE(a.member_id_no, ''),
                       COALESCE(NULLIF(a.billto_name,''), a.member_name, a.account_name),
                       COALESCE(NULLIF(a.billto_mobile,''), a.member_mobile, ''),
                       COALESCE(NULLIF(a.billto_email,''), a.member_email, ''),
                       -- Alamat sebagai SATU rentetan: empat lajur
                       -- berasingan menjadikan laporan tidak boleh dibaca,
                       -- dan tiga daripadanya kosong untuk kebanyakan akaun.
                       TRIM(BOTH ', ' FROM CONCAT_WS(', ',
                            NULLIF(a.addr_line1,''), NULLIF(a.addr_line2,''),
                            NULLIF(a.addr_line3,''), NULLIF(a.addr_line4,''))),
                       COALESCE(a.addr_postcode, ''),
                       COALESCE(a.addr_state, ''),
                       COALESCE(c.name, ''),
                       a.status,
                       COALESCE(b.balance, 0)
                FROM   account a
                LEFT   JOIN account_category c ON c.id = a.category_id
                LEFT   JOIN account_balance  b ON b.account_id = a.id
                WHERE  a.sp_code = :sp
                  AND  COALESCE(a.account_type,'') <> 'ADHOC'
                  AND  (:active IS NULL
                        OR (:active = 1 AND a.status = 'ACTIVE')
                        OR (:active = 0 AND a.status <> 'ACTIVE'))
                  AND  (:cat IS NULL OR a.category_id = :cat)
                  AND  (:q IS NULL
                        OR LOWER(a.account_no) LIKE :q
                        OR LOWER(a.account_name) LIKE :q)
                ORDER  BY a.account_no
                """)
                .setParameter("sp", q.spCode())
                .setParameter("active", q.active() == null ? null : (q.active() ? 1 : 0))
                .setParameter("cat", q.categoryId())
                .setParameter("q", q.search() == null || q.search().isBlank()
                        ? null : "%" + q.search().trim().toLowerCase() + "%")
                .getResultList();

        List<Row> items = new ArrayList<>();
        BigDecimal jumlah = BigDecimal.ZERO;
        int aktif = 0, tidakAktif = 0;

        for (Object[] r : rows) {
            BigDecimal baki = (BigDecimal) r[11];
            jumlah = jumlah.add(baki);
            if ("ACTIVE".equals(r[10])) aktif++; else tidakAktif++;

            items.add(new Row((String) r[0], (String) r[1], (String) r[2],
                    (String) r[3], (String) r[4], (String) r[5],
                    (String) r[6], (String) r[7], (String) r[8],
                    (String) r[9], (String) r[10], baki));
        }
        return new Result(items, jumlah, aktif, tidakAktif);
    }

    // ── Senarai Langganan ────────────────────────────────────────────

    /**
     * Satu baris per LANGGANAN, dengan nama produk pada setiap baris.
     *
     * AKTIF bermakna status ACTIVE DAN end_date belum lepas.
     *
     * Lima langganan dalam data pengeluaran mempunyai status ACTIVE
     * dengan end_date yang sudah berlalu: bil sudah berhenti dijana
     * untuk mereka, jadi laporan menunjukkannya sebagai Tamat.
     *
     * AKAUN mereka mungkin masih aktif — itu soalan berbeza, dijawab
     * oleh Senarai Akaun.
     */
    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public SubResult subscriptionList(SubQuery q) {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT a.account_no, a.account_name,
                       p.code, p.name,
                       COALESCE(pc.name, ''),
                       s.quantity, s.start_date, s.end_date,
                       (s.status = 'ACTIVE'
                        AND (s.end_date IS NULL OR s.end_date >= CURDATE())) AS aktif
                FROM   account_subscription s
                JOIN   account a ON a.id = s.account_id
                JOIN   product p ON p.id = s.product_id
                LEFT   JOIN product_category pc ON pc.id = p.category_id
                WHERE  s.sp_code = :sp
                  AND  COALESCE(a.account_type,'') <> 'ADHOC'
                  AND  (:cat IS NULL OR p.category_id = :cat)
                  AND  (:prod IS NULL OR s.product_id = :prod)
                  AND  (:status IS NULL
                        OR (:status = 1 AND s.status = 'ACTIVE'
                            AND (s.end_date IS NULL OR s.end_date >= CURDATE()))
                        OR (:status = 0 AND NOT (s.status = 'ACTIVE'
                            AND (s.end_date IS NULL OR s.end_date >= CURDATE()))))
                ORDER  BY p.code, a.account_no
                """)
                .setParameter("sp", q.spCode())
                .setParameter("cat", q.productCategoryId())
                .setParameter("prod", q.productId())
                .setParameter("status", q.status() == null ? null : (q.status() ? 1 : 0))
                .getResultList();

        List<SubRow> items = new ArrayList<>();
        int aktif = 0, tamat = 0;

        for (Object[] r : rows) {
            boolean isAktif = benar(r[8]);
            if (isAktif) aktif++; else tamat++;
            items.add(new SubRow((String) r[0], (String) r[1],
                    (String) r[2], (String) r[3], (String) r[4],
                    (java.math.BigDecimal) r[5],
                    r[6] == null ? null : r[6].toString(),
                    r[7] == null ? null : r[7].toString(),
                    isAktif));
        }
        return new SubResult(items, aktif, tamat);
    }

    // ── Senarai Tunggakan ────────────────────────────────────────────

    /**
     * Baki setiap akaun pada satu TARIKH.
     *
     * POTRET, bukan tapisan baris. Dokumen DAN alokasi ditapis pada
     * tarikh yang sama: pelanggan yang berhutang RM500 pada 31 Julai dan
     * membayar RM200 pada 2 Ogos muncul sebagai RM500.
     *
     * Menapis invois sahaja bermakna bayaran kemudian mengurangkan
     * tunggakan lampau, dan laporan yang sama memberi nombor berbeza
     * setiap kali dijana.
     *
     * Baki dikira daripada signed_amount (V33) — takrifan yang sama
     * seperti account_balance, tetapi dengan had tarikh yang VIEW itu
     * tidak boleh terima.
     *
     * TEMPOH ialah julat baris invois yang masih menyumbang: invois
     * tertua yang belum lunas hingga yang terbaharu. Ia menerangkan
     * MENGAPA jumlah itu wujud.
     */
    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public ArrearResult arrears(ArrearQuery q) {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT a.account_no,
                       COALESCE(NULLIF(a.billto_name,''), a.account_name),
                       COALESCE(NULLIF(a.billto_email,''), a.member_email, ''),
                       MIN(t.period_start), MAX(t.period_end),
                       SUM(t.signed) AS baki
                FROM   account a
                JOIN (
                    -- Dokumen sehingga tarikh, bertanda.
                    SELECT e.account_id, e.signed_amount AS signed,
                           (SELECT MIN(l.period_start) FROM financial_document_line l
                             WHERE l.document_id = e.document_id AND l.active = 1)
                               AS period_start,
                           (SELECT MAX(l.period_end) FROM financial_document_line l
                             WHERE l.document_id = e.document_id AND l.active = 1)
                               AS period_end
                    FROM   account_document_entry e
                    WHERE  e.sp_code = :sp AND e.doc_date <= :asAt
                ) t ON t.account_id = a.id
                WHERE  a.sp_code = :sp
                  AND  COALESCE(a.account_type,'') <> 'ADHOC'
                GROUP  BY a.id, a.account_no, a.billto_name, a.account_name,
                          a.billto_email, a.member_email
                HAVING (:arrearsOnly = 0 AND ABS(SUM(t.signed)) > 0.005)
                    OR (:arrearsOnly = 1 AND SUM(t.signed) > 0.005)
                ORDER  BY baki, a.account_no
                """)
                .setParameter("sp", q.spCode())
                .setParameter("asAt", q.asAt())
                .setParameter("arrearsOnly", q.arrearsOnly() ? 1 : 0)
                .getResultList();

        List<ArrearRow> items = new ArrayList<>();
        BigDecimal jumlah = BigDecimal.ZERO;

        for (Object[] r : rows) {
            BigDecimal baki = (BigDecimal) r[5];
            jumlah = jumlah.add(baki);
            items.add(new ArrearRow((String) r[0], (String) r[1], (String) r[2],
                    tempohJulat(r[3], r[4]), baki));
        }
        return new ArrearResult(q.asAt(), items, jumlah);
    }

    /** 'Julai 2026' atau 'Jun 2022 - Julai 2026'. */
    private static String tempohJulat(Object mula, Object tamat) {
        if (mula == null) return "";
        java.time.LocalDate a = tarikh(mula);
        java.time.LocalDate b = tamat == null ? a : tarikh(tamat);
        String namaA = BULAN[a.getMonthValue() - 1] + " " + a.getYear();
        if (a.getYear() == b.getYear() && a.getMonthValue() == b.getMonthValue()) {
            return namaA;
        }
        return namaA + " - " + BULAN[b.getMonthValue() - 1] + " " + b.getYear();
    }

    private static final String[] BULAN = {
            "Januari", "Februari", "Mac", "April", "Mei", "Jun",
            "Julai", "Ogos", "September", "Oktober", "November", "Disember" };

    private static java.time.LocalDate tarikh(Object v) {
        if (v instanceof java.time.LocalDate d) return d;
        if (v instanceof java.sql.Date d) return d.toLocalDate();
        return java.time.LocalDate.parse(String.valueOf(v));
    }

    /**
     * tinyint(1) dipulangkan sebagai Boolean oleh Connector/J, bukan
     * Number — corak ini sudah muncul tiga kali dalam projek.
     */
    private static boolean benar(Object v) {
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() != 0;
        return false;
    }
}
