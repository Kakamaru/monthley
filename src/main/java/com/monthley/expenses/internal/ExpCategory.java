package com.monthley.expenses.internal;

import com.monthley.shared.BaseEntity;
import jakarta.persistence.*;

/**
 * Kategori perbelanjaan — pokok dua aras.
 *
 * GL diletak pada kategori INDUK sahaja; jenis (anak) mewarisi. Untung
 * Rugi menunjukkan tiga baris perbelanjaan, bukan berpuluh — pecahan
 * sehingga 'Elektrik' datang dari laporan yang membaca kategori.
 */
@Entity
@Table(name = "exp_category")
public class ExpCategory extends BaseEntity {

    public enum Status { ACTIVE, INACTIVE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sp_code", nullable = false, length = 20)
    private String spCode;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    /** NULL = kategori induk. */
    @Column(name = "parent_id")
    private Long parentId;

    /** NULL pada induk = jatuh ke 5900 Perbelanjaan Am. Sentiasa NULL pada anak. */
    @Column(name = "gl_account_id")
    private Long glAccountId;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private Status status = Status.ACTIVE;

    protected ExpCategory() {}

    public ExpCategory(String spCode, String name, Long parentId) {
        this.spCode = spCode;
        this.name = name;
        this.parentId = parentId;
    }

    public Long getId() { return id; }
    public String getSpCode() { return spCode; }
    public String getName() { return name; }
    public Long getParentId() { return parentId; }
    public Long getGlAccountId() { return glAccountId; }
    public int getSortOrder() { return sortOrder; }
    public Status getStatus() { return status; }

    public void setName(String v) { this.name = v; }
    public void setParentId(Long v) { this.parentId = v; }
    public void setGlAccountId(Long v) { this.glAccountId = v; }
    public void setSortOrder(int v) { this.sortOrder = v; }
    public void setStatus(Status v) { this.status = v; }
}
