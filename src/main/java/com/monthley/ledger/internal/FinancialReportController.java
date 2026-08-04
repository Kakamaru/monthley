package com.monthley.ledger.internal;

import com.monthley.shared.Access;
import com.monthley.shared.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Laporan kewangan daripada ledger — Imbangan Duga dan Untung Rugi.
 *
 * ENTRI DIBALIKKAN KEKAL DIKIRA.
 *
 * Membatalkan invois menandakan entri asal REVERSED dan mengepos entri
 * CANCELLATION yang membalikkannya. Kedua-duanya mesti dikira: ia
 * membatalkan satu sama lain secara semula jadi, dan itulah maksud
 * pembukuan berpasangan.
 *
 * Menapis status = 'POSTED' membuang entri asal sambil MENGEKALKAN
 * pembalikannya — kesan bersih tolak sekali, bukan sifar. Draf pertama
 * laporan ini melakukan tepat itu, dan AR terkurang RM391.50 daripada
 * sub-lejar.
 *
 * Hanya DRAFT dikecualikan: ia belum berlaku.
 *
 * BALANCE SHEET TIDAK ADA DI SINI. Ia memerlukan baki pembukaan dan
 * ekuiti; source_type OPENING wujud tetapi belum ada data. Laporan
 * kunci kira-kira tanpa baki pembukaan bukan kosong — ia SALAH, dan itu
 * lebih teruk.
 */
@RestController
@RequestMapping("/api/v1/reports")
class FinancialReportController {

    @PersistenceContext
    private EntityManager em;

    // ── Imbangan Duga ────────────────────────────────────────────────

    record TrialRow(String code, String name, String accountType,
                    BigDecimal debit, BigDecimal credit) {}

    record TrialBalance(LocalDate asAt, List<TrialRow> rows,
                        BigDecimal totalDebit, BigDecimal totalCredit,
                        boolean balanced) {}

    /**
     * Imbangan Duga pada satu tarikh.
     *
     * Bukan laporan untuk membuat keputusan — ia UJIAN. Kalau dua jumlah
     * tidak sama, ada sesuatu rosak dalam pembukuan dan setiap laporan
     * lain tidak boleh dipercayai sehingga ia dibetulkan.
     *
     * Terkumpul sejak permulaan hingga tarikh dipilih: itu maksud
     * 'imbangan duga pada 31 Disember'.
     *
     * Setiap akaun muncul dalam SATU lajur — sisi bakinya. Baki negatif
     * dalam satu lajur bukan konvensyen yang akauntan kenal.
     */
    @GetMapping("/trial-balance")
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    TrialBalance trialBalance(@RequestParam(required = false) LocalDate asAt) {
        Access.requireAnyRole("melihat imbangan duga", "SP_ADMIN", "CLERK", "VIEWER");

        LocalDate had = asAt == null ? LocalDate.now() : asAt;

        List<Object[]> rows = em.createNativeQuery("""
                SELECT c.code, c.name, c.account_type,
                       SUM(l.debit_amount - l.credit_amount) AS baki
                FROM   journal_line      l
                JOIN   journal_entry     e ON e.id = l.journal_entry_id
                JOIN   chart_of_accounts c ON c.id = l.gl_account_id
                WHERE  e.sp_code = :sp
                  AND  e.status <> 'DRAFT'
                  AND  e.entry_date <= :had
                GROUP  BY c.id, c.code, c.name, c.account_type
                HAVING ABS(SUM(l.debit_amount - l.credit_amount)) > 0.005
                ORDER  BY c.code
                """)
                .setParameter("sp", sp())
                .setParameter("had", had)
                .getResultList();

        List<TrialRow> items = new ArrayList<>();
        BigDecimal jumDr = BigDecimal.ZERO, jumCr = BigDecimal.ZERO;

        for (Object[] r : rows) {
            BigDecimal baki = (BigDecimal) r[3];
            BigDecimal dr = baki.signum() > 0 ? baki : BigDecimal.ZERO;
            BigDecimal cr = baki.signum() < 0 ? baki.negate() : BigDecimal.ZERO;
            jumDr = jumDr.add(dr);
            jumCr = jumCr.add(cr);
            items.add(new TrialRow((String) r[0], (String) r[1], (String) r[2], dr, cr));
        }

        return new TrialBalance(had, items, jumDr, jumCr,
                jumDr.subtract(jumCr).abs().compareTo(new BigDecimal("0.005")) < 0);
    }

    // ── Untung Rugi ──────────────────────────────────────────────────

    record PnlRow(String code, String name, BigDecimal amount) {}

    record ProfitLoss(LocalDate from, LocalDate to,
                      List<PnlRow> income, BigDecimal totalIncome,
                      List<PnlRow> expense, BigDecimal totalExpense,
                      BigDecimal net, boolean expenseModuleActive) {}

    /**
     * Untung Rugi bagi julat tarikh.
     *
     * INCOME dan EXPENSE sahaja — akaun kunci kira-kira tidak muncul.
     *
     * Hasil ialah baki KREDIT, jadi tandanya dibalikkan untuk paparan:
     * pendapatan RM8,831 muncul sebagai 8,831 positif, bukan -8,831.
     *
     * PERBELANJAAN MUNGKIN KOSONG. Monthley menjejaki hasil dan
     * penghutang; gaji, elektrik dan penyelenggaraan datang daripada
     * modul Expenses. Laporan menyatakannya secara eksplisit supaya SP
     * tidak membaca kosong sebagai 'tiada perbelanjaan'.
     */
    @GetMapping("/profit-loss")
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    ProfitLoss profitLoss(@RequestParam(required = false) LocalDate from,
                          @RequestParam(required = false) LocalDate to) {
        Access.requireAnyRole("melihat untung rugi", "SP_ADMIN", "CLERK", "VIEWER");

        LocalDate mula = from == null ? LocalDate.now().withDayOfYear(1) : from;
        LocalDate tamat = to == null ? LocalDate.now() : to;

        List<Object[]> rows = em.createNativeQuery("""
                SELECT c.code, c.name, c.account_type,
                       SUM(l.debit_amount - l.credit_amount) AS baki
                FROM   journal_line      l
                JOIN   journal_entry     e ON e.id = l.journal_entry_id
                JOIN   chart_of_accounts c ON c.id = l.gl_account_id
                WHERE  e.sp_code = :sp
                  AND  e.status <> 'DRAFT'
                  AND  e.entry_date BETWEEN :mula AND :tamat
                  AND  c.account_type IN ('INCOME', 'EXPENSE')
                GROUP  BY c.id, c.code, c.name, c.account_type
                HAVING ABS(SUM(l.debit_amount - l.credit_amount)) > 0.005
                ORDER  BY c.code
                """)
                .setParameter("sp", sp())
                .setParameter("mula", mula)
                .setParameter("tamat", tamat)
                .getResultList();

        List<PnlRow> hasil = new ArrayList<>(), belanja = new ArrayList<>();
        BigDecimal jumHasil = BigDecimal.ZERO, jumBelanja = BigDecimal.ZERO;

        for (Object[] r : rows) {
            BigDecimal baki = (BigDecimal) r[3];
            if ("INCOME".equals(r[2])) {
                BigDecimal v = baki.negate();   // kredit -> positif
                jumHasil = jumHasil.add(v);
                hasil.add(new PnlRow((String) r[0], (String) r[1], v));
            } else {
                jumBelanja = jumBelanja.add(baki);
                belanja.add(new PnlRow((String) r[0], (String) r[1], baki));
            }
        }

        return new ProfitLoss(mula, tamat, hasil, jumHasil, belanja, jumBelanja,
                jumHasil.subtract(jumBelanja), !belanja.isEmpty());
    }

    private String sp() {
        String sp = TenantContext.get();
        if (sp == null || sp.isBlank()) {
            throw new IllegalStateException("Header X-SP-Id diperlukan");
        }
        return sp;
    }
}
