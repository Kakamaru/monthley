package com.monthley.expenses.internal;

import com.monthley.shared.BaseEntity;
import jakarta.persistence.*;
import org.hibernate.envers.Audited;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Invois pembekal.
 *
 * inv_no DIMASUKKAN, bukan dijana — ia nombor pembekal (cth no invois TNB).
 * Unik per pembekal, bukan per SP: dua pembekal boleh guna nombor sama.
 *
 * subtotal/sst/total DISIMPAN kerana ia snapshot dokumen. Baki TIDAK —
 * ia datang dari VIEW exp_invoice_balance. Menyimpan baki ialah corak
 * cached_balance yang baru digugurkan: ia menyimpang sebaik ada satu
 * laluan tulis yang terlepas.
 *
 * sst_rate/sst_amount di sini ialah SST BELIAN — apa yang SP bayar kepada
 * pembekal. Ia disimpan untuk PADANAN DOKUMEN, bukan untuk cukai: SST
 * Malaysia tiada tuntutan input, jadi tiada apa untuk dilaporkan atau
 * dituntut balik. Ia wujud supaya pengguna boleh memasukkan angka persis
 * seperti tertera pada invois pembekal (Subtotal 1000, SST 80, Jumlah
 * 1080) dan bukan mengira sendiri lalu menaip 1080 — kemasukan yang
 * sepadan dengan kertas di tangan kurang silap.
 *
 * JANGAN kelirukan dengan financial_document.tax_amount, iaitu SST JUALAN
 * yang SP kutip daripada pelanggan dan mesti diserahkan kepada Kastam.
 * Yang itu dikredit ke 2100 SST Payable sebagai liabiliti; yang ini
 * didebit ke akaun belanja sebagai kos.
 *
 * sst_rate ialah snapshot kadar pada tarikh invois; kadar berubah dan
 * invois lama mesti kekal menunjukkan apa yang sebenarnya dicaj.
 */
@Entity
@Table(name = "exp_invoice")
@Audited
public class ExpInvoice extends BaseEntity {

    public enum Status { ACTIVE, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sp_code", nullable = false, length = 20)
    private String spCode;

    @Column(name = "inv_no", nullable = false, length = 50)
    private String invNo;

    @Column(name = "supplier_id", nullable = false)
    private Long supplierId;

    @Column(name = "inv_date", nullable = false)
    private LocalDate invDate;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "note")
    private String note;

    @Column(name = "subtotal", nullable = false, precision = 15, scale = 2)
    private BigDecimal subtotal = BigDecimal.ZERO;

    @Column(name = "sst_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal sstRate = BigDecimal.ZERO;

    @Column(name = "sst_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal sstAmount = BigDecimal.ZERO;

    @Column(name = "total", nullable = false, precision = 15, scale = 2)
    private BigDecimal total = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private Status status = Status.ACTIVE;

    @Column(name = "cancelled_at")   private LocalDateTime cancelledAt;
    @Column(name = "cancelled_by")   private Long cancelledBy;
    @Column(name = "cancel_reason")  private String cancelReason;

    /** Posting Dr Belanja / Cr AP. Disimpan supaya pembatalan boleh membalikkannya. */
    @Column(name = "journal_entry_id")
    private Long journalEntryId;

    protected ExpInvoice() {}

    public ExpInvoice(String spCode, String invNo, Long supplierId, LocalDate invDate) {
        this.spCode = spCode;
        this.invNo = invNo;
        this.supplierId = supplierId;
        this.invDate = invDate;
    }

    public Long getId() { return id; }
    public String getSpCode() { return spCode; }
    public String getInvNo() { return invNo; }
    public Long getSupplierId() { return supplierId; }
    public LocalDate getInvDate() { return invDate; }
    public LocalDate getDueDate() { return dueDate; }
    public String getNote() { return note; }
    public BigDecimal getSubtotal() { return subtotal; }
    public BigDecimal getSstRate() { return sstRate; }
    public BigDecimal getSstAmount() { return sstAmount; }
    public BigDecimal getTotal() { return total; }
    public Status getStatus() { return status; }
    public Long getJournalEntryId() { return journalEntryId; }
    public String getCancelReason() { return cancelReason; }

    public void setDueDate(LocalDate v) { this.dueDate = v; }
    public void setNote(String v) { this.note = v; }
    public void setSubtotal(BigDecimal v) { this.subtotal = v; }
    public void setSstRate(BigDecimal v) { this.sstRate = v; }
    public void setSstAmount(BigDecimal v) { this.sstAmount = v; }
    public void setTotal(BigDecimal v) { this.total = v; }
    public void setJournalEntryId(Long v) { this.journalEntryId = v; }

    public void cancel(String reason, Long by) {
        this.status = Status.CANCELLED;
        this.cancelReason = reason;
        this.cancelledBy = by;
        this.cancelledAt = LocalDateTime.now();
    }
}
