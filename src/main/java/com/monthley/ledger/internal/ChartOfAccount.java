package com.monthley.ledger.internal;

import com.monthley.shared.BaseEntity;
import jakarta.persistence.*;
import org.hibernate.envers.Audited;

/**
 * Satu akaun dalam carta akaun (per SP).
 * Kod (cth "1100" = Accounts Receivable) unik dalam satu SP.
 */
@Entity
@Table(name = "chart_of_accounts")
@Audited
public class ChartOfAccount extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sp_code", nullable = false, length = 20)
    private String spCode;

    @Column(name = "code", nullable = false, length = 20)
    private String code;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 20)
    private AccountType accountType;

    @Enumerated(EnumType.STRING)
    @Column(name = "normal_side", nullable = false, length = 10)
    private NormalSide normalSide;

    @Column(name = "is_control", nullable = false)
    private boolean control;

    @Enumerated(EnumType.STRING)
    @Column(name = "sub_ledger_type", length = 10)
    private SubLedgerType subLedgerType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private Status status = Status.ACTIVE;

    public enum AccountType { ASSET, LIABILITY, INCOME, EXPENSE, EQUITY }
    public enum NormalSide { DEBIT, CREDIT }
    public enum SubLedgerType { AR, DEPOSIT }
    public enum Status { ACTIVE, INACTIVE }

    protected ChartOfAccount() {}

    public ChartOfAccount(String spCode, String code, String name,
                          AccountType accountType, NormalSide normalSide,
                          boolean control, SubLedgerType subLedgerType) {
        this.spCode = spCode;
        this.code = code;
        this.name = name;
        this.accountType = accountType;
        this.normalSide = normalSide;
        this.control = control;
        this.subLedgerType = subLedgerType;
    }

    public Long getId() { return id; }
    public String getSpCode() { return spCode; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public AccountType getAccountType() { return accountType; }
    public NormalSide getNormalSide() { return normalSide; }
}
