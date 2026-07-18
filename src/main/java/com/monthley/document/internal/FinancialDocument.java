package com.monthley.document.internal;

import com.monthley.document.api.DocumentType;
import com.monthley.shared.BaseEntity;
import jakarta.persistence.*;
import org.hibernate.envers.Audited;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "financial_document")
@Audited
public class FinancialDocument extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sp_code", nullable = false, length = 20)
    private String spCode;

    @Column(name = "doc_no", nullable = false, length = 30)
    private String docNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "doc_type", nullable = false, length = 20)
    private DocumentType docType;

    @Column(name = "account_id")
    private Long accountId;

    @Column(name = "doc_date", nullable = false)
    private LocalDate docDate;

    /** Period LARIAN — aras charge_frequency AKAUN. Null untuk resit. */
    @Column(name = "period_id")
    private Long periodId;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "title")
    private String title;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "MYR";

    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount = BigDecimal.ZERO;

    @Column(name = "tax_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal taxAmount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private Status status = Status.ACTIVE;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "uuid", length = 36)
    private String uuid;

    @OneToMany(mappedBy = "document", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<FinancialDocumentLine> lines = new ArrayList<>();

    public enum Status { ACTIVE, PAID, PARTIAL, CANCELLED }

    protected FinancialDocument() {}

    public FinancialDocument(String spCode, String docNo, DocumentType docType,
                             Long accountId, LocalDate docDate, Long periodId,
                             LocalDate dueDate, String title) {
        this.spCode = spCode;
        this.docNo = docNo;
        this.docType = docType;
        this.accountId = accountId;
        this.docDate = docDate;
        this.periodId = periodId;
        this.dueDate = dueDate;
        this.title = title;
        this.uuid = java.util.UUID.randomUUID().toString();
    }

    public void addLine(FinancialDocumentLine line) {
        line.setDocument(this);
        this.lines.add(line);
    }

    public void recomputeTotals() {
        this.amount = lines.stream().map(FinancialDocumentLine::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        this.taxAmount = lines.stream().map(FinancialDocumentLine::getTaxAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public void setReceiptAmount(java.math.BigDecimal amt) {
        this.amount = amt;
        this.taxAmount = java.math.BigDecimal.ZERO;
    }

    public void markCancelled() {
        this.status = Status.CANCELLED;
        this.cancelledAt = java.time.LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Long getPeriodId() { return periodId; }
    public String getDocNo() { return docNo; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getTaxAmount() { return taxAmount; }
    public BigDecimal getTotal() { return amount.add(taxAmount); }
    public List<FinancialDocumentLine> getLines() { return lines; }
}
