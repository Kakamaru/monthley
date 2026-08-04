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

    // ── Ageing ───────────────────────────────────────────────────────

    /**
     * Baki setiap akaun dipecahkan mengikut umur pada satu tarikh.
     *
     * BUCKET SEBENAR: jumlah keenam-enam lajur sama dengan total. Laporan
     * legacy mengulang nombor merentas lajur dan menghasilkan bucket yang
     * MELEBIHI jumlah keseluruhan.
     *
     * Umur daripada due_date. Invois yang belum sampai tarikh akhir
     * bayaran masuk 'Belum Matang' — memasukkannya dalam 0-30 bermakna
     * laporan mengatakan pelanggan sudah lewat sedangkan dia masih ada
     * beberapa hari.
     *
     * POTRET, sama seperti Senarai Tunggakan: invois DAN alokasi ditapis
     * pada tarikh yang sama.
     *
     * Baki resit yang TIDAK dialokasikan (advance) dan nota kredit
     * dikurangkan daripada bucket TERTUA dahulu — wang yang belum
     * dipadankan tetap mengurangkan hutang, dan hutang tertua ialah yang
     * paling membimbangkan.
     */
    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public AgeResult ageing(AgeQuery q) {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT a.id, a.account_no,
                       COALESCE(NULLIF(a.billto_name,''), a.account_name),
                       -- Baki belum lunas setiap invois pada tarikh
                       SUM(GREATEST(inv.baki, 0)) AS jumlah,
                       SUM(CASE WHEN inv.umur <  0                       THEN GREATEST(inv.baki,0) ELSE 0 END),
                       SUM(CASE WHEN inv.umur >= 0   AND inv.umur <= 30  THEN GREATEST(inv.baki,0) ELSE 0 END),
                       SUM(CASE WHEN inv.umur >  30  AND inv.umur <= 60  THEN GREATEST(inv.baki,0) ELSE 0 END),
                       SUM(CASE WHEN inv.umur >  60  AND inv.umur <= 90  THEN GREATEST(inv.baki,0) ELSE 0 END),
                       SUM(CASE WHEN inv.umur >  90  AND inv.umur <= 180 THEN GREATEST(inv.baki,0) ELSE 0 END),
                       SUM(CASE WHEN inv.umur >  180                     THEN GREATEST(inv.baki,0) ELSE 0 END)
                FROM   account a
                JOIN (
                    SELECT d.account_id,
                           DATEDIFF(:asAt, d.due_date) AS umur,
                           (d.amount + d.tax_amount)
                             - COALESCE((SELECT SUM(al.amount)
                                           FROM fi_allocation al
                                           JOIN financial_document cr ON cr.id = al.credit_document_id
                                          WHERE al.debit_document_id = d.id
                                            AND al.status = 'ACTIVE'
                                            AND cr.doc_date <= :asAt), 0) AS baki
                    FROM   financial_document d
                    WHERE  d.sp_code = :sp
                      AND  d.doc_type IN ('INVOICE', 'DEBIT_NOTE')
                      AND  d.status <> 'CANCELLED'
                      AND  d.doc_date <= :asAt
                ) inv ON inv.account_id = a.id
                WHERE  a.sp_code = :sp
                  AND  COALESCE(a.account_type,'') <> 'ADHOC'
                  AND  (:cat IS NULL OR a.category_id = :cat)
                GROUP  BY a.id, a.account_no, a.billto_name, a.account_name
                HAVING jumlah > 0.005
                ORDER  BY %s
                """.formatted(urutan(q)))
                .setParameter("sp", q.spCode())
                .setParameter("asAt", q.asAt())
                .setParameter("cat", q.categoryId())
                .getResultList();

        List<AgeRow> items = new ArrayList<>();
        BigDecimal jt = BigDecimal.ZERO, jn = BigDecimal.ZERO, j30 = BigDecimal.ZERO,
                   j60 = BigDecimal.ZERO, j90 = BigDecimal.ZERO,
                   j180 = BigDecimal.ZERO, jo = BigDecimal.ZERO;

        for (Object[] r : rows) {
            BigDecimal total = (BigDecimal) r[3];
            BigDecimal notDue = (BigDecimal) r[4];
            BigDecimal d30 = (BigDecimal) r[5], d60 = (BigDecimal) r[6];
            BigDecimal d90 = (BigDecimal) r[7], d180 = (BigDecimal) r[8];
            BigDecimal over = (BigDecimal) r[9];

            jt = jt.add(total); jn = jn.add(notDue); j30 = j30.add(d30);
            j60 = j60.add(d60); j90 = j90.add(d90); j180 = j180.add(d180);
            jo = jo.add(over);

            items.add(new AgeRow((String) r[1], (String) r[2],
                    total, notDue, d30, d60, d90, d180, over));
        }
        return new AgeResult(q.asAt(), items, jt, jn, j30, j60, j90, j180, jo);
    }

    /**
     * Klausa ORDER BY daripada pilihan pengguna.
     *
     * Lalai ialah nombor akaun MENAIK, atas permintaan JMB: mereka
     * menyemak baris demi baris terhadap rekod mereka sendiri, dan
     * susunan yang boleh diramal lebih bernilai daripada jumlah menurun
     * di situ.
     *
     * Susunan dihantar dari skrin supaya PDF SEPADAN dengannya. Tanpa
     * ia, kerani menyusun ikut jumlah, menekan cetak, dan mendapat
     * kertas dalam susunan yang berbeza daripada yang dilihatnya.
     *
     * Nilai dipadankan kepada senarai TETAP, tidak pernah disisipkan ke
     * dalam SQL: parameter luar dalam klausa ORDER BY ialah jalan masuk
     * suntikan.
     */
    private static String urutan(AgeQuery q) {
        String arah = q.asc() ? "ASC" : "DESC";
        if (q.sortBy() == null) return "a.account_no ASC";
        return switch (q.sortBy()) {
            case "nama"   -> "COALESCE(NULLIF(a.billto_name,''), a.account_name) " + arah;
            case "jumlah" -> "jumlah " + arah + ", a.account_no";
            default       -> "a.account_no " + arah;
        };
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
