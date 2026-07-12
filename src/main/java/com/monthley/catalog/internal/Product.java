package com.monthley.catalog.internal;

import com.monthley.shared.BaseEntity;
import com.monthley.shared.ChargeFrequency;
import jakarta.persistence.*;
import org.hibernate.envers.Audited;

import java.math.BigDecimal;

@Entity
@Table(name = "product")
@Audited
public class Product extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sp_code", nullable = false, length = 20)
    private String spCode;

    @Column(name = "code", nullable = false, length = 50)
    private String code;

    @Column(name = "subscription_code", length = 50)
    private String subscriptionCode;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "category_id")
    private Long categoryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "charge_frequency", nullable = false, length = 20)
    private ChargeFrequency chargeFrequency = ChargeFrequency.MONTHLY;

    @Column(name = "anchor_month")
    private Integer anchorMonth;

    @Column(name = "unit_rate", nullable = false, precision = 15, scale = 2)
    private BigDecimal unitRate = BigDecimal.ZERO;

    @Column(name = "income_gl_account_id")
    private Long incomeGlAccountId;

    @Column(name = "main_product", nullable = false)
    private boolean mainProduct;

    @Column(name = "mandatory", nullable = false)
    private boolean mandatory;

    @Column(name = "prorated", nullable = false)
    private boolean prorated;

    @Column(name = "late_penalty", nullable = false)
    private boolean latePenalty;

    @Column(name = "generation_day")
    private Integer generationDay;

    @Column(name = "term_days")
    private Integer termDays;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private Status status = Status.ACTIVE;

    public enum Status { ACTIVE, INACTIVE }

    protected Product() {}

    public Product(String spCode, String code, String name,
                   ChargeFrequency chargeFrequency, BigDecimal unitRate) {
        this.spCode = spCode;
        this.code = code;
        this.name = name;
        this.chargeFrequency = chargeFrequency;
        this.unitRate = unitRate;
    }

    public void setAnchorMonth(Integer m) { this.anchorMonth = m; }
    public void setProrated(boolean p) { this.prorated = p; }
    public void setLatePenalty(boolean p) { this.latePenalty = p; }
    public void setMandatory(boolean m) { this.mandatory = m; }
    public void setIncomeGlAccountId(Long id) { this.incomeGlAccountId = id; }
    public void setSubscriptionCode(String c) { this.subscriptionCode = c; }
    public void setDescription(String d) { this.description = d; }

    public Long getId() { return id; }
    public String getSpCode() { return spCode; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public ChargeFrequency getChargeFrequency() { return chargeFrequency; }
    public Integer getAnchorMonth() { return anchorMonth; }
    public BigDecimal getUnitRate() { return unitRate; }
    public Long getIncomeGlAccountId() { return incomeGlAccountId; }
    public boolean isProrated() { return prorated; }
    public boolean isLatePenalty() { return latePenalty; }
    public boolean isMandatory() { return mandatory; }
    public Status getStatus() { return status; }
}
