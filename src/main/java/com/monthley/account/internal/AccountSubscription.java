package com.monthley.account.internal;

import com.monthley.shared.BaseEntity;
import jakarta.persistence.*;
import org.hibernate.envers.Audited;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "account_subscription")
@Audited
public class AccountSubscription extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sp_code", nullable = false, length = 20)
    private String spCode;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "quantity", nullable = false, precision = 15, scale = 4)
    private BigDecimal quantity = BigDecimal.ONE;

    @Column(name = "unit_price", precision = 15, scale = 2)
    private BigDecimal unitPrice;   // effective unit price — override

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private Status status = Status.ACTIVE;

    public enum Status { ACTIVE, INACTIVE, ENDED }

    protected AccountSubscription() {}

    public AccountSubscription(String spCode, Long accountId, Long productId,
                              BigDecimal quantity, LocalDate startDate) {
        this.spCode = spCode;
        this.accountId = accountId;
        this.productId = productId;
        this.quantity = quantity;
        this.startDate = startDate;
    }

    public void setUnitPrice(BigDecimal p) { this.unitPrice = p; }
    public void setEndDate(LocalDate d) { this.endDate = d; }

    public Long getId() { return id; }
    public Long getAccountId() { return accountId; }
    public Long getProductId() { return productId; }
    public BigDecimal getQuantity() { return quantity; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate() { return endDate; }
    public Status getStatus() { return status; }
}
