package com.monthley.document.internal;

import jakarta.persistence.*;
import org.hibernate.envers.Audited;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "financial_document_line")
@Audited
public class FinancialDocumentLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    private FinancialDocument document;

    @Column(name = "product_id")
    private Long productId;

    @Column(name = "account_id")
    private Long accountId;

    @Column(name = "description")
    private String description;

    @Column(name = "quantity", nullable = false, precision = 15, scale = 4)
    private BigDecimal quantity = BigDecimal.ONE;

    @Column(name = "unit_price", nullable = false, precision = 15, scale = 2)
    private BigDecimal unitPrice = BigDecimal.ZERO;

    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount = BigDecimal.ZERO;

    @Column(name = "tax_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal taxAmount = BigDecimal.ZERO;

    @Column(name = "period_start")
    private LocalDate periodStart;

    @Column(name = "period_end")
    private LocalDate periodEnd;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    // idem_key = generated column dalam DB (STORED) — read-only di sini
    @Column(name = "idem_key", insertable = false, updatable = false)
    private String idemKey;

    protected FinancialDocumentLine() {}

    public FinancialDocumentLine(Long productId, Long accountId, String description,
                                 BigDecimal quantity, BigDecimal unitPrice,
                                 BigDecimal amount, BigDecimal taxAmount,
                                 LocalDate periodStart, LocalDate periodEnd) {
        this.productId = productId;
        this.accountId = accountId;
        this.description = description;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.amount = amount;
        this.taxAmount = taxAmount;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
    }

    void setDocument(FinancialDocument d) { this.document = d; }

    public Long getId() { return id; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getTaxAmount() { return taxAmount; }
}
