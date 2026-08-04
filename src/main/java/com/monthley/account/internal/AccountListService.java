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
}
