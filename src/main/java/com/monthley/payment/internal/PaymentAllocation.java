package com.monthley.payment.internal;

import com.monthley.shared.BaseEntity;
import jakarta.persistence.*;
import org.hibernate.envers.Audited;

import java.math.BigDecimal;

/**
 * Peruntukan bayaran — double-entry di peringkat dokumen.
 * debit_document_id  = invois yang dibayar (AR dikurangkan)
 * credit_document_id = resit / sumber bayaran
 */
@Entity
@Table(name = "fi_allocation")
@Audited
public class PaymentAllocation extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sp_code", nullable = false, length = 20)
    private String spCode;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "debit_document_id", nullable = false)
    private Long debitDocumentId;    // invois

    @Column(name = "credit_document_id", nullable = false)
    private Long creditDocumentId;   // resit

    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private Status status = Status.ACTIVE;

    @Column(name = "reverses_allocation_id")
    private Long reversesAllocationId;

    @Column(name = "journal_entry_id")
    private Long journalEntryId;

    public enum Status { ACTIVE, REVERSED }

    protected PaymentAllocation() {}

    public PaymentAllocation(String spCode, Long accountId, Long debitDocumentId,
                             Long creditDocumentId, BigDecimal amount) {
        this.spCode = spCode;
        this.accountId = accountId;
        this.debitDocumentId = debitDocumentId;
        this.creditDocumentId = creditDocumentId;
        this.amount = amount;
    }

    public Long getId() { return id; }
    public Long getDebitDocumentId() { return debitDocumentId; }
    public BigDecimal getAmount() { return amount; }
    public Status getStatus() { return status; }
    public void markReversed() { this.status = Status.REVERSED; }
}
