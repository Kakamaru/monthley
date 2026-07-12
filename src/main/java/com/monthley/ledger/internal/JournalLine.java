package com.monthley.ledger.internal;

import jakarta.persistence.*;
import org.hibernate.envers.Audited;

import java.math.BigDecimal;

/**
 * Satu leg journal. Tepat SATU daripada debit/credit > 0
 * (dikuatkuasakan CHECK constraint dalam DB + kilang di sini).
 */
@Entity
@Table(name = "journal_line")
@Audited
public class JournalLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "journal_entry_id", nullable = false)
    private JournalEntry entry;

    @Column(name = "gl_account_id", nullable = false)
    private Long glAccountId;

    @Column(name = "debit_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal debitAmount = BigDecimal.ZERO;

    @Column(name = "credit_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal creditAmount = BigDecimal.ZERO;

    @Column(name = "sub_ledger_account_id")
    private Long subLedgerAccountId;

    @Column(name = "product_id")
    private Long productId;

    @Column(name = "line_desc", length = 255)
    private String lineDesc;

    protected JournalLine() {}

    private JournalLine(Long glAccountId, BigDecimal debit, BigDecimal credit,
                        Long subLedgerAccountId, Long productId, String lineDesc) {
        this.glAccountId = glAccountId;
        this.debitAmount = debit;
        this.creditAmount = credit;
        this.subLedgerAccountId = subLedgerAccountId;
        this.productId = productId;
        this.lineDesc = lineDesc;
    }

    public static JournalLine debit(Long glAccountId, BigDecimal amount, Long subLedgerAccountId, String desc) {
        return new JournalLine(glAccountId, amount, BigDecimal.ZERO, subLedgerAccountId, null, desc);
    }

    public static JournalLine credit(Long glAccountId, BigDecimal amount, Long productId, String desc) {
        return new JournalLine(glAccountId, BigDecimal.ZERO, amount, null, productId, desc);
    }

    void setEntry(JournalEntry entry) { this.entry = entry; }

    public Long getId() { return id; }
    public Long getGlAccountId() { return glAccountId; }
    public BigDecimal getDebitAmount() { return debitAmount; }
    public BigDecimal getCreditAmount() { return creditAmount; }
    public Long getSubLedgerAccountId() { return subLedgerAccountId; }
}
