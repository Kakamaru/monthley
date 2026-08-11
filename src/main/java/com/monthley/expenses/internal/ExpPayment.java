package com.monthley.expenses.internal;

import com.monthley.shared.BaseEntity;
import jakarta.persistence.*;
import org.hibernate.envers.Audited;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Baucar bayaran (PV) — membayar invois pembekal.
 *
 * Satu PV membayar SATU invois. Bayaran separa dibenarkan: beberapa PV
 * boleh menunjuk invois yang sama, dan baki datang dari
 * VIEW exp_invoice_balance.
 *
 * pv_no dijana melalui DocumentNumberPort (kaunter berkunci, ADR 0012)
 * dengan prefix dari exp_setting. Keunikan dijamin oleh UNIQUE pada
 * jadual ini — semakan dalaman penomboran hanya melihat
 * financial_document.
 */
@Entity
@Table(name = "exp_payment")
@Audited
public class ExpPayment extends BaseEntity {

    public enum Status { ACTIVE, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sp_code", nullable = false, length = 20)
    private String spCode;

    @Column(name = "pv_no", nullable = false, length = 30)
    private String pvNo;

    @Column(name = "invoice_id", nullable = false)
    private Long invoiceId;

    @Column(name = "pay_date", nullable = false)
    private LocalDate payDate;

    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "method", nullable = false, length = 40)
    private String method;

    @Column(name = "ref_no", length = 60) private String refNo;
    @Column(name = "note")                private String note;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private Status status = Status.ACTIVE;

    @Column(name = "cancelled_at")  private LocalDateTime cancelledAt;
    @Column(name = "cancelled_by")  private Long cancelledBy;
    @Column(name = "cancel_reason") private String cancelReason;

    /** Posting Dr AP / Cr Bank. Disimpan supaya pembatalan boleh membalikkannya. */
    @Column(name = "journal_entry_id")
    private Long journalEntryId;

    protected ExpPayment() {}

    public ExpPayment(String spCode, String pvNo, Long invoiceId,
                      LocalDate payDate, BigDecimal amount, String method) {
        this.spCode = spCode;
        this.pvNo = pvNo;
        this.invoiceId = invoiceId;
        this.payDate = payDate;
        this.amount = amount;
        this.method = method;
    }

    public Long getId() { return id; }
    public String getSpCode() { return spCode; }
    public String getPvNo() { return pvNo; }
    public Long getInvoiceId() { return invoiceId; }
    public LocalDate getPayDate() { return payDate; }
    public BigDecimal getAmount() { return amount; }
    public String getMethod() { return method; }
    public String getRefNo() { return refNo; }
    public String getNote() { return note; }
    public Status getStatus() { return status; }
    public Long getJournalEntryId() { return journalEntryId; }

    public void setRefNo(String v) { this.refNo = v; }
    public void setNote(String v) { this.note = v; }
    public void setJournalEntryId(Long v) { this.journalEntryId = v; }

    public void cancel(String reason, Long by) {
        this.status = Status.CANCELLED;
        this.cancelReason = reason;
        this.cancelledBy = by;
        this.cancelledAt = LocalDateTime.now();
    }
}
