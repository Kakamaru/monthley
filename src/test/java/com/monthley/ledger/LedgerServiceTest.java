package com.monthley.ledger;

import com.monthley.ledger.api.*;
import com.monthley.ledger.internal.ChartOfAccountSeeder;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class LedgerServiceTest {

    @Autowired LedgerPort ledger;
    @Autowired ChartOfAccountSeeder seeder;
    @PersistenceContext EntityManager em;

    @BeforeEach
    void setup() {
        em.createNativeQuery("""
            INSERT IGNORE INTO service_provider (sp_code, name, status, created_at, updated_at, version)
            VALUES ('SP01', 'SP Ujian', 'ACTIVE', NOW(), NOW(), 0)
            """).executeUpdate();
        seeder.seedFor("SP01");
    }

    private PostingRequest invoicePosting(String desc, String ar, String income, String tax) {
        return new PostingRequest(
                "SP01", LocalDate.now(), SourceType.INVOICE, null, desc,
                List.of(
                        PostingLine.debit(GlAccounts.ACCOUNTS_RECEIVABLE, new BigDecimal(ar), null),
                        PostingLine.credit(GlAccounts.SERVICE_INCOME, new BigDecimal(income), null),
                        PostingLine.credit(GlAccounts.TAX_PAYABLE, new BigDecimal(tax), null)
                ), null);
    }

    @Test @DisplayName("Journal seimbang berjaya")
    void balancedPosts() {
        assertThat(ledger.post(invoicePosting("Seimbang", "106.00", "100.00", "6.00"))).isNotNull();
    }

    @Test @DisplayName("Journal tak seimbang ditolak")
    void unbalancedRejected() {
        PostingRequest bad = new PostingRequest("SP01", LocalDate.now(), SourceType.INVOICE, null, "Tak seimbang",
                List.of(
                        PostingLine.debit(GlAccounts.ACCOUNTS_RECEIVABLE, new BigDecimal("106.00"), null),
                        PostingLine.credit(GlAccounts.SERVICE_INCOME, new BigDecimal("100.00"), null)
                ), null);
        assertThatThrownBy(() -> ledger.post(bad)).isInstanceOf(UnbalancedJournalException.class);
    }

    @Test @DisplayName("Reverse hasilkan contra")
    void reverseCreatesContra() {
        Long original = ledger.post(invoicePosting("Batal", "106.00", "100.00", "6.00"));
        assertThat(ledger.reverse(original, "ujian")).isNotNull().isNotEqualTo(original);
    }

    @Test @DisplayName("Reverse dua kali ditolak")
    void doubleReverseRejected() {
        Long original = ledger.post(invoicePosting("Sekali", "106.00", "100.00", "6.00"));
        ledger.reverse(original, "pertama");
        assertThatThrownBy(() -> ledger.reverse(original, "kedua")).isInstanceOf(IllegalStateException.class);
    }
}
