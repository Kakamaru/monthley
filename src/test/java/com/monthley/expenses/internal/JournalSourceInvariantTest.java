package com.monthley.expenses.internal;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Setiap journal_entry mesti merujuk baris yang WUJUD dalam jadual yang
 * betul mengikut source_type.
 *
 * Ini menggantikan fk_journal_doc yang digugurkan dalam V69. FK itu
 * menganggap setiap jurnal berasal daripada financial_document — benar
 * sehingga modul Perbelanjaan wujud. MySQL tiada FK polimorfik, jadi
 * kekangan tunggal ke satu jadual tidak boleh bertahan sebaik ada modul
 * kedua yang mempos ke ledger.
 *
 * Liputan di sini LEBIH LUAS daripada FK asal: FK hanya menyemak
 * financial_document; ini menyemak setiap jenis, termasuk yang akan
 * ditambah kemudian — jenis source_type baharu tanpa jadual padanan akan
 * memerahkan ujian terakhir.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class JournalSourceInvariantTest {

    @PersistenceContext EntityManager em;

    /** source_type -> jadual yang source_document_id merujuknya. */
    private static final List<String[]> PEMETAAN = List.of(
            new String[]{"INVOICE",      "financial_document"},
            new String[]{"PAYMENT",      "financial_document"},
            new String[]{"PENALTY",      "financial_document"},
            new String[]{"ADJUSTMENT",   "financial_document"},
            new String[]{"WRITEOFF",     "financial_document"},
            new String[]{"CANCELLATION", "financial_document"},
            new String[]{"OPENING",      "financial_document"},
            new String[]{"EXP_INVOICE",  "exp_invoice"},
            new String[]{"EXP_PAYMENT",  "exp_payment"},
            new String[]{"EXP_CASH",     "exp_cash_entry"},
            // Derma mempos terhadap RESIT — dokumen kewangan, sama
            // seperti bayaran. Yang berbeza ialah kredit pergi ke
            // hasil derma dan bukan ke akaun belum terima.
            new String[]{"DONATION",     "financial_document"});

    @Test
    @DisplayName("tiada journal_entry menunjuk dokumen sumber yang tidak wujud")
    void tiadaRujukanYatim() {
        for (String[] m : PEMETAAN) {
            Number yatim = (Number) em.createNativeQuery(
                    "SELECT COUNT(*) FROM journal_entry j "
                    + "WHERE j.source_type = :t AND j.source_document_id IS NOT NULL "
                    + "  AND NOT EXISTS (SELECT 1 FROM " + m[1] + " d WHERE d.id = j.source_document_id)")
                    .setParameter("t", m[0])
                    .getSingleResult();

            assertThat(yatim.intValue())
                    .withFailMessage("journal_entry dengan source_type=%s menunjuk baris "
                            + "yang tidak wujud dalam %s: %d baris yatim", m[0], m[1], yatim.intValue())
                    .isZero();
        }
    }

    /**
     * Setiap nilai dalam ENUM source_type mesti ada dalam PEMETAAN.
     *
     * Tanpa semakan ini, menambah jenis baharu (modul Aduan, Sumbangan)
     * tidak akan memerahkan apa-apa — dan jurnalnya lolos tanpa disemak
     * langsung. FK asal sekurang-kurangnya menolak apa yang ia tidak
     * fahami; senarai yang tidak lengkap diam sahaja.
     */
    @Test
    @DisplayName("setiap source_type ada jadual padanan dalam pemetaan")
    void pemetaanLengkap() {
        for (com.monthley.ledger.api.SourceType t : com.monthley.ledger.api.SourceType.values()) {
            assertThat(PEMETAAN.stream().anyMatch(m -> m[0].equals(t.name())))
                    .withFailMessage("SourceType.%s tiada dalam PEMETAAN — jurnalnya "
                            + "tidak akan disemak. Tambah jadual padanannya.", t.name())
                    .isTrue();
        }
    }
}
