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

    // ── Ahli (member) — orang yang akaun ini wakili ──
    @Column(name = "member_name")        private String memberName;
    @Column(name = "member_id_no", length = 50) private String memberIdNo;
    @Column(name = "member_email")       private String memberEmail;
    @Column(name = "member_mobile", length = 30) private String memberMobile;

    // ── Alamat akaun (JMB: alamat unit/owner) ──
    @Column(name = "addr_line1")         private String addrLine1;
    @Column(name = "addr_line2")         private String addrLine2;
    @Column(name = "addr_line3")         private String addrLine3;
    @Column(name = "addr_line4")         private String addrLine4;
    @Column(name = "addr_postcode", length = 10) private String addrPostcode;
    @Column(name = "addr_state", length = 100)   private String addrState;
    @Column(name = "addr_country", length = 100) private String addrCountry;

    // ── Bil kepada (billto) — penerima invois (JMB: penyewa) ──
    @Column(name = "billto_name")        private String billtoName;
    @Column(name = "billto_email")       private String billtoEmail;
    @Column(name = "billto_mobile", length = 30) private String billtoMobile;
    @Column(name = "billto_addr_line1")  private String billtoAddrLine1;
    @Column(name = "billto_addr_line2")  private String billtoAddrLine2;
    @Column(name = "billto_addr_line3")  private String billtoAddrLine3;
    @Column(name = "billto_addr_line4")  private String billtoAddrLine4;
    @Column(name = "billto_postcode", length = 10) private String billtoPostcode;
    @Column(name = "billto_state", length = 100)   private String billtoState;
    @Column(name = "billto_country", length = 100) private String billtoCountry;
    @Column(name = "billto_email_secondary") private String billtoEmailSecondary;
    @Column(name = "deposit_amount", precision = 15, scale = 2) private java.math.BigDecimal depositAmount;
    @Column(name = "opening_amount", precision = 15, scale = 2) private java.math.BigDecimal openingAmount;
    @Column(name = "remarks", length = 500) private String remarks;
    @Column(name = "account_type", length = 50) private String accountType;

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

    public void setCategoryId(Long id) { this.categoryId = id; }
    public void setBranchId(Long id) { this.branchId = id; }
    public void setPayerUserId(Long id) { this.payerUserId = id; }

    public void setMemberName(String v) { this.memberName = v; }
    public void setMemberIdNo(String v) { this.memberIdNo = v; }
    public void setMemberEmail(String v) { this.memberEmail = v; }
    public void setMemberMobile(String v) { this.memberMobile = v; }

    public void setAddrLine1(String v) { this.addrLine1 = v; }
    public void setAddrLine2(String v) { this.addrLine2 = v; }
    public void setAddrLine3(String v) { this.addrLine3 = v; }
    public void setAddrLine4(String v) { this.addrLine4 = v; }
    public void setAddrPostcode(String v) { this.addrPostcode = v; }
    public void setAddrState(String v) { this.addrState = v; }
    public void setAddrCountry(String v) { this.addrCountry = v; }

    public void setBilltoName(String v) { this.billtoName = v; }
    public void setBilltoEmail(String v) { this.billtoEmail = v; }
    public void setBilltoMobile(String v) { this.billtoMobile = v; }
    public void setBilltoAddrLine1(String v) { this.billtoAddrLine1 = v; }
    public void setBilltoAddrLine2(String v) { this.billtoAddrLine2 = v; }
    public void setBilltoAddrLine3(String v) { this.billtoAddrLine3 = v; }
    public void setBilltoAddrLine4(String v) { this.billtoAddrLine4 = v; }
    public void setBilltoPostcode(String v) { this.billtoPostcode = v; }
    public void setBilltoState(String v) { this.billtoState = v; }
    public void setBilltoCountry(String v) { this.billtoCountry = v; }
    public void setBilltoEmailSecondary(String v) { this.billtoEmailSecondary = v; }
    public void setDepositAmount(java.math.BigDecimal v) { this.depositAmount = v; }
    public void setOpeningAmount(java.math.BigDecimal v) { this.openingAmount = v; }
    public void setRemarks(String v) { this.remarks = v; }
    public void setAccountType(String v) { this.accountType = v; }

    public Long getId() { return id; }
    public String getSpCode() { return spCode; }
    public String getAccountNo() { return accountNo; }
    public String getAccountName() { return accountName; }
    public ChargeFrequency getChargeFrequency() { return chargeFrequency; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getExpiryDate() { return expiryDate; }
    public Status getStatus() { return status; }
    public Long getCategoryId() { return categoryId; }
    public Long getBranchId() { return branchId; }
    public Long getPayerUserId() { return payerUserId; }

    public String getMemberName() { return memberName; }
    public String getMemberIdNo() { return memberIdNo; }
    public String getMemberEmail() { return memberEmail; }
    public String getMemberMobile() { return memberMobile; }

    public String getAddrLine1() { return addrLine1; }
    public String getAddrLine2() { return addrLine2; }
    public String getAddrLine3() { return addrLine3; }
    public String getAddrLine4() { return addrLine4; }
    public String getAddrPostcode() { return addrPostcode; }
    public String getAddrState() { return addrState; }
    public String getAddrCountry() { return addrCountry; }

    public String getBilltoName() { return billtoName; }
    public String getBilltoEmail() { return billtoEmail; }
    public String getBilltoMobile() { return billtoMobile; }
    public String getBilltoAddrLine1() { return billtoAddrLine1; }
    public String getBilltoAddrLine2() { return billtoAddrLine2; }
    public String getBilltoAddrLine3() { return billtoAddrLine3; }
    public String getBilltoAddrLine4() { return billtoAddrLine4; }
    public String getBilltoPostcode() { return billtoPostcode; }
    public String getBilltoState() { return billtoState; }
    public String getBilltoCountry() { return billtoCountry; }
    public String getBilltoEmailSecondary() { return billtoEmailSecondary; }
    public java.math.BigDecimal getDepositAmount() { return depositAmount; }
    public java.math.BigDecimal getOpeningAmount() { return openingAmount; }
    public String getRemarks() { return remarks; }
    public String getAccountType() { return accountType; }
}
