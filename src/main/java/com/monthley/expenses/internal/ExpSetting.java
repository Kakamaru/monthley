package com.monthley.expenses.internal;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Tetapan modul — satu baris per SP, sp_code sebagai kunci utama.
 *
 * Duduk di sini dan bukan sp_document_setting kerana SP yang tidak
 * melanggan modul tidak sepatutnya membawa lajur PV dalam tetapan teras
 * mereka (ADR 0016).
 */
@Entity
@Table(name = "exp_setting")
public class ExpSetting {

    @Id
    @Column(name = "sp_code", length = 20)
    private String spCode;

    @Column(name = "sst_enabled", nullable = false)
    private boolean sstEnabled = false;

    @Column(name = "sst_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal sstRate = BigDecimal.ZERO;

    @Column(name = "pv_prefix", nullable = false, length = 10)
    private String pvPrefix = "PV";

    @Column(name = "pv_no_size", nullable = false)
    private int pvNoSize = 6;

    @Column(name = "pv_no_start", nullable = false)
    private long pvNoStart = 1L;

    @Column(name = "cash_prefix", nullable = false, length = 10)
    private String cashPrefix = "BT";

    @Column(name = "cash_no_size", nullable = false)
    private int cashNoSize = 6;

    @Column(name = "cash_no_start", nullable = false)
    private long cashNoStart = 1L;

    /** NULL = jatuh ke 1000 Bank / Tunai. */
    @Column(name = "bank_gl_account_id")
    private Long bankGlAccountId;

    @Column(name = "updated_at")  private LocalDateTime updatedAt;
    @Column(name = "updated_by")  private String updatedBy;

    @Version
    @Column(name = "version")
    private Long version;

    protected ExpSetting() {}

    public ExpSetting(String spCode) { this.spCode = spCode; }

    public String getSpCode() { return spCode; }
    public boolean isSstEnabled() { return sstEnabled; }
    public BigDecimal getSstRate() { return sstRate; }
    public String getPvPrefix() { return pvPrefix; }
    public int getPvNoSize() { return pvNoSize; }
    public long getPvNoStart() { return pvNoStart; }
    public String getCashPrefix() { return cashPrefix; }
    public int getCashNoSize() { return cashNoSize; }
    public long getCashNoStart() { return cashNoStart; }
    public Long getBankGlAccountId() { return bankGlAccountId; }

    public void setSstEnabled(boolean v) { this.sstEnabled = v; }
    public void setSstRate(BigDecimal v) { this.sstRate = v; }
    public void setPvPrefix(String v) { this.pvPrefix = v; }
    public void setPvNoSize(int v) { this.pvNoSize = v; }
    public void setPvNoStart(long v) { this.pvNoStart = v; }
    public void setCashPrefix(String v) { this.cashPrefix = v; }
    public void setCashNoSize(int v) { this.cashNoSize = v; }
    public void setCashNoStart(long v) { this.cashNoStart = v; }
    public void setBankGlAccountId(Long v) { this.bankGlAccountId = v; }
}
