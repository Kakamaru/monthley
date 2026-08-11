package com.monthley.expenses.internal;

import com.monthley.shared.BaseEntity;
import jakarta.persistence.*;

/**
 * Kaedah bayaran — senarai boleh ubah per SP.
 *
 * Transaksi menyimpan NAMA kaedah sebagai rentetan, bukan FK: ia snapshot
 * pada masa bayaran. Menamakan semula 'Maybank2u' kepada 'Online Banking'
 * tahun depan tidak sepatutnya menulis semula baucar yang sudah dicetak.
 */
@Entity
@Table(name = "exp_payment_method")
public class ExpPaymentMethod extends BaseEntity {

    public enum Status { ACTIVE, INACTIVE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sp_code", nullable = false, length = 20)
    private String spCode;

    @Column(name = "name", nullable = false, length = 50)
    private String name;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private Status status = Status.ACTIVE;

    protected ExpPaymentMethod() {}

    public ExpPaymentMethod(String spCode, String name) {
        this.spCode = spCode;
        this.name = name;
    }

    public Long getId() { return id; }
    public String getSpCode() { return spCode; }
    public String getName() { return name; }
    public int getSortOrder() { return sortOrder; }
    public Status getStatus() { return status; }

    public void setName(String v) { this.name = v; }
    public void setSortOrder(int v) { this.sortOrder = v; }
    public void setStatus(Status v) { this.status = v; }
}
