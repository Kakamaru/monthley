package com.monthley.account.internal;

import com.monthley.shared.BaseEntity;
import com.monthley.shared.ChargeFrequency;
import jakarta.persistence.*;
import org.hibernate.envers.Audited;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "account")
@Audited
public class Account extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sp_code", nullable = false, length = 20)
    private String spCode;

    @Column(name = "account_no", nullable = false, length = 30)
    private String accountNo;

    @Column(name = "account_name", nullable = false)
    private String accountName;

    @Column(name = "category_id")
    private Long categoryId;

    @Column(name = "branch_id")
    private Long branchId;

    @Column(name = "payer_user_id")
    private Long payerUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "charge_frequency", length = 20)
    private ChargeFrequency chargeFrequency;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private Status status = Status.ACTIVE;

    /** Cache sahaja — baki sebenar diderive dari ledger. */
    @Column(name = "cached_balance", nullable = false, precision = 15, scale = 2)
    private BigDecimal cachedBalance = BigDecimal.ZERO;

    @Column(name = "uuid", length = 36)
    private String uuid;

    public enum Status { ACTIVE, INACTIVE }

    protected Account() {}

    public Account(String spCode, String accountNo, String accountName) {
        this.spCode = spCode;
        this.accountNo = accountNo;
        this.accountName = accountName;
    }

    public void setChargeFrequency(ChargeFrequency f) { this.chargeFrequency = f; }
    public void setStartDate(LocalDate d) { this.startDate = d; }
    public void setExpiryDate(LocalDate d) { this.expiryDate = d; }

    public Long getId() { return id; }
    public String getSpCode() { return spCode; }
    public String getAccountNo() { return accountNo; }
    public String getAccountName() { return accountName; }
    public ChargeFrequency getChargeFrequency() { return chargeFrequency; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getExpiryDate() { return expiryDate; }
    public Status getStatus() { return status; }
}
