package com.monthley.payment.internal;

import com.monthley.payment.api.PaymentMethod;
import com.monthley.shared.BaseEntity;
import jakarta.persistence.*;
import org.hibernate.envers.Audited;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "payment")
@Audited
public class Payment extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sp_code", nullable = false, length = 20)
    private String spCode;

    @Column(name = "receipt_document_id", nullable = false)
    private Long receiptDocumentId;

    @Column(name = "payer_account_id")
    private Long payerAccountId;

    @Column(name = "payment_date", nullable = false)
    private LocalDate paymentDate;

    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount = BigDecimal.ZERO;

    @Column(name = "allocated_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal allocatedAmount = BigDecimal.ZERO;

    @Column(name = "deposit_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal depositAmount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, length = 15)
    private PaymentMethod method;

    @Column(name = "payment_ref_no", length = 100)
    private String paymentRefNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private Status status = Status.ACTIVE;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "journal_entry_id")
    private Long journalEntryId;

    @Column(name = "uuid", length = 36)
    private String uuid;

    public enum Status { ACTIVE, CANCELLED }

    protected Payment() {}

    public Payment(String spCode, Long receiptDocumentId, Long payerAccountId,
                   BigDecimal amount, PaymentMethod method, String paymentRefNo) {
        this.spCode = spCode;
        this.receiptDocumentId = receiptDocumentId;
        this.payerAccountId = payerAccountId;
        this.amount = amount;
        this.method = method;
        this.paymentRefNo = paymentRefNo;
        this.paymentDate = LocalDate.now();
        this.uuid = java.util.UUID.randomUUID().toString();
    }

    public void setTotals(BigDecimal allocated, BigDecimal deposit) {
        this.allocatedAmount = allocated;
        this.depositAmount = deposit;
    }

    public void setJournalEntryId(Long id) { this.journalEntryId = id; }
    public void markCancelled() {
        this.status = Status.CANCELLED;
        this.cancelledAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Long getReceiptDocumentId() { return receiptDocumentId; }
    public BigDecimal getAmount() { return amount; }
    public Long getJournalEntryId() { return journalEntryId; }
    public Status getStatus() { return status; }
}
