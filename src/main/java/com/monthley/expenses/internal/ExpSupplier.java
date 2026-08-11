package com.monthley.expenses.internal;

import com.monthley.shared.BaseEntity;
import jakarta.persistence.*;

/** Pembekal — pihak yang menghantar invois kepada SP. */
@Entity
@Table(name = "exp_supplier")
public class ExpSupplier extends BaseEntity {

    public enum Status { ACTIVE, INACTIVE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sp_code", nullable = false, length = 20)
    private String spCode;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "reg_no", length = 50)      private String regNo;
    @Column(name = "tin", length = 30)         private String tin;
    @Column(name = "address")                  private String address;
    @Column(name = "phone", length = 30)       private String phone;
    @Column(name = "email", length = 100)      private String email;
    @Column(name = "bank_name", length = 60)   private String bankName;
    @Column(name = "bank_acc_no", length = 40) private String bankAccNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private Status status = Status.ACTIVE;

    protected ExpSupplier() {}

    public ExpSupplier(String spCode, String name) {
        this.spCode = spCode;
        this.name = name;
    }

    public Long getId() { return id; }
    public String getSpCode() { return spCode; }
    public String getName() { return name; }
    public String getRegNo() { return regNo; }
    public String getTin() { return tin; }
    public String getAddress() { return address; }
    public String getPhone() { return phone; }
    public String getEmail() { return email; }
    public String getBankName() { return bankName; }
    public String getBankAccNo() { return bankAccNo; }
    public Status getStatus() { return status; }

    public void setName(String v) { this.name = v; }
    public void setRegNo(String v) { this.regNo = v; }
    public void setTin(String v) { this.tin = v; }
    public void setAddress(String v) { this.address = v; }
    public void setPhone(String v) { this.phone = v; }
    public void setEmail(String v) { this.email = v; }
    public void setBankName(String v) { this.bankName = v; }
    public void setBankAccNo(String v) { this.bankAccNo = v; }
    public void setStatus(Status v) { this.status = v; }
}
