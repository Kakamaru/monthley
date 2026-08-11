package com.monthley.expenses.internal;

import jakarta.persistence.*;
import org.hibernate.envers.Audited;

import java.math.BigDecimal;

/** Baris invois pembekal — setiap baris ada kategori sendiri. */
@Entity
@Table(name = "exp_invoice_item")
@Audited
public class ExpInvoiceItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "invoice_id", nullable = false)
    private Long invoiceId;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Column(name = "description")
    private String description;

    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount = BigDecimal.ZERO;

    protected ExpInvoiceItem() {}

    public ExpInvoiceItem(Long invoiceId, Long categoryId, BigDecimal amount) {
        this.invoiceId = invoiceId;
        this.categoryId = categoryId;
        this.amount = amount;
    }

    public Long getId() { return id; }
    public Long getInvoiceId() { return invoiceId; }
    public Long getCategoryId() { return categoryId; }
    public String getDescription() { return description; }
    public BigDecimal getAmount() { return amount; }

    public void setDescription(String v) { this.description = v; }
    public void setAmount(BigDecimal v) { this.amount = v; }
}
