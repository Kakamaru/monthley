package com.monthley.expenses.internal;

import com.monthley.shared.BaseEntity;
import jakarta.persistence.*;
import org.hibernate.envers.Audited;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Bayaran terus — duit keluar tanpa invois pembekal (gaji, khairat,
 * tunai runcit).
 *
 * Bukan pendua ledger: ia dokumen sumber, dengan penerima dan kategori
 * yang lejar sahaja tidak menyimpan.
 */
@Entity
@Table(name = "exp_cash_entry")
@Audited
public class ExpCashEntry extends BaseEntity {

    public enum Status { ACTIVE, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sp_code", nullable = false, length = 20)
    private String spCode;

    @Column(name = "voucher_no", nullable = false, length = 30)
    private String voucherNo;

    @Column(name = "entry_date", nullable = false)
    private LocalDate entryDate;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Column(name = "payee", nullable = false, length = 150)
    private String payee;

    @Column(name = "description") private String description;

    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "method", nullable = false, length = 40)
    private String method;

    @Column(name = "ref_no", length = 60) private String refNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private Status status = Status.ACTIVE;

    @Column(name = "cancelled_at")  private LocalDateTime cancelledAt;
    @Column(name = "cancelled_by")  private Long cancelledBy;
    @Column(name = "cancel_reason") private String cancelReason;

    /** Posting Dr Belanja / Cr Bank. */
    @Column(name = "journal_entry_id")
    private Long journalEntryId;

    protected ExpCashEntry() {}

    public ExpCashEntry(String spCode, String voucherNo, LocalDate entryDate,
                        Long categoryId, String payee, BigDecimal amount, String method) {
        this.spCode = spCode;
        this.voucherNo = voucherNo;
        this.entryDate = entryDate;
        this.categoryId = categoryId;
        this.payee = payee;
        this.amount = amount;
        this.method = method;
    }

    public Long getId() { return id; }
    public String getSpCode() { return spCode; }
    public String getVoucherNo() { return voucherNo; }
    public LocalDate getEntryDate() { return entryDate; }
    public Long getCategoryId() { return categoryId; }
    public String getPayee() { return payee; }
    public String getDescription() { return description; }
    public BigDecimal getAmount() { return amount; }
    public String getMethod() { return method; }
    public String getRefNo() { return refNo; }
    public Status getStatus() { return status; }
    public Long getJournalEntryId() { return journalEntryId; }

    public void setDescription(String v) { this.description = v; }
    public void setRefNo(String v) { this.refNo = v; }
    public void setJournalEntryId(Long v) { this.journalEntryId = v; }

    public void cancel(String reason, Long by) {
        this.status = Status.CANCELLED;
        this.cancelReason = reason;
        this.cancelledBy = by;
        this.cancelledAt = LocalDateTime.now();
    }
}
