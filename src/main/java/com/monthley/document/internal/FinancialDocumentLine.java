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

    /**
     * Period LIPUTAN — aras charge_frequency PRODUK.
     * Berbeza dari FinancialDocument.periodId (period LARIAN, aras akaun).
     * Nullable: baris usage/per-use boleh tiada period.
     */
    @Column(name = "period_id")
    private Long periodId;

    /**
     * Produk ONE_TIME. Menyebabkan idem_key jadi (account, product, 'ONCE')
     * — sekali seumur hidup, bukan sekali setahun. Rujuk V18.
     */
    @Column(name = "once_only", nullable = false)
    private boolean onceOnly = false;

    @Column(name = "description")
    private String description;

    /** Kuantiti ASAL — ratio TIDAK dibakar ke dalamnya. Rujuk V20. */
    @Column(name = "quantity", nullable = false, precision = 15, scale = 4)
    private BigDecimal quantity = BigDecimal.ONE;

    /** 0..1. amount = ROUND(unit_price x quantity x proration_ratio, 2). */
    @Column(name = "proration_ratio", nullable = false, precision = 9, scale = 8)
    private BigDecimal prorationRatio = BigDecimal.ONE;

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

    public FinancialDocumentLine(Long productId, Long accountId, Long periodId,
                                 String description,
                                 BigDecimal quantity, BigDecimal unitPrice,
                                 BigDecimal prorationRatio,
                                 BigDecimal amount, BigDecimal taxAmount,
                                 LocalDate periodStart, LocalDate periodEnd,
                                 boolean onceOnly) {
        this.productId = productId;
        this.accountId = accountId;
        this.periodId = periodId;
        this.description = description;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.prorationRatio = prorationRatio == null ? BigDecimal.ONE : prorationRatio;
        this.amount = amount;
        this.taxAmount = taxAmount;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.onceOnly = onceOnly;
    }

    void setDocument(FinancialDocument d) { this.document = d; }

    public Long getId() { return id; }
    public Long getPeriodId() { return periodId; }
    public boolean isOnceOnly() { return onceOnly; }
    public BigDecimal getQuantity() { return quantity; }
    public BigDecimal getProrationRatio() { return prorationRatio; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getTaxAmount() { return taxAmount; }
}
