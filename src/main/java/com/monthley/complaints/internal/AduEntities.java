package com.monthley.complaints.internal;

import com.monthley.shared.BaseEntity;
import jakarta.persistence.*;

import java.time.LocalDateTime;

/** Kategori aduan — senarai rata, boleh diubah SP. */
@Entity
@Table(name = "adu_category")
class AduCategory extends BaseEntity {

    enum Status { ACTIVE, INACTIVE }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sp_code", nullable = false, length = 20)
    private String spCode;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private Status status = Status.ACTIVE;

    protected AduCategory() {}

    AduCategory(String spCode, String name) {
        this.spCode = spCode;
        this.name = name;
    }

    Long getId() { return id; }
    String getSpCode() { return spCode; }
    String getName() { return name; }
    int getSortOrder() { return sortOrder; }
    Status getStatus() { return status; }

    void setName(String v) { this.name = v; }
    void setSortOrder(int v) { this.sortOrder = v; }
    void setStatus(Status v) { this.status = v; }
}

/**
 * Aduan.
 *
 * reporter_name/phone wujud kerana pengadu boleh berbeza daripada
 * pemegang akaun: penyewa mengadu tentang unit yang disewa, atau kerani
 * merekod aduan daripada panggilan telefon. Nama pada akaun bukan
 * semestinya nama orang yang mengadu.
 */
@Entity
@Table(name = "adu_complaint")
class AduComplaint extends BaseEntity {

    enum Priority { HIGH, MEDIUM, LOW }
    enum Status { NEW, IN_PROGRESS, RESOLVED, REOPENED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sp_code", nullable = false, length = 20)
    private String spCode;

    @Column(name = "complaint_no", nullable = false, length = 30)
    private String complaintNo;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "category_id")
    private Long categoryId;

    @Column(name = "subject", nullable = false, length = 200)
    private String subject;

    @Column(name = "detail", columnDefinition = "TEXT")
    private String detail;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 10)
    private Priority priority = Priority.MEDIUM;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 15)
    private Status status = Status.NEW;

    @Column(name = "assigned_to")    private Long assignedTo;
    @Column(name = "reported_by")    private Long reportedBy;
    @Column(name = "reporter_name", length = 150)  private String reporterName;
    @Column(name = "reporter_phone", length = 30)  private String reporterPhone;
    @Column(name = "internal_note", length = 500)  private String internalNote;

    /**
     * Disimpan kerana perubahan status tidak dilog — dikosongkan semula
     * apabila aduan dibuka semula, supaya 'purata masa selesai' mengukur
     * penyelesaian TERKINI dan bukan yang pertama.
     */
    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    protected AduComplaint() {}

    AduComplaint(String spCode, String complaintNo, Long accountId, String subject) {
        this.spCode = spCode;
        this.complaintNo = complaintNo;
        this.accountId = accountId;
        this.subject = subject;
    }

    Long getId() { return id; }
    String getSpCode() { return spCode; }
    String getComplaintNo() { return complaintNo; }
    Long getAccountId() { return accountId; }
    Long getCategoryId() { return categoryId; }
    String getSubject() { return subject; }
    String getDetail() { return detail; }
    Priority getPriority() { return priority; }
    Status getStatus() { return status; }
    Long getAssignedTo() { return assignedTo; }
    String getInternalNote() { return internalNote; }

    void setCategoryId(Long v) { this.categoryId = v; }
    void setDetail(String v) { this.detail = v; }
    void setPriority(Priority v) { this.priority = v; }
    void setAssignedTo(Long v) { this.assignedTo = v; }
    void setReportedBy(Long v) { this.reportedBy = v; }
    void setReporterName(String v) { this.reporterName = v; }
    void setReporterPhone(String v) { this.reporterPhone = v; }
    void setInternalNote(String v) { this.internalNote = v; }

    /** Tukar status; resolvedAt dijaga supaya ia sentiasa konsisten. */
    void setStatus(Status v) {
        this.status = v;
        this.resolvedAt = (v == Status.RESOLVED) ? LocalDateTime.now() : null;
    }
}

/** Balasan — thread aduan, termasuk nota dalaman. */
@Entity
@Table(name = "adu_reply")
class AduReply {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "complaint_id", nullable = false)
    private Long complaintId;

    @Column(name = "message", nullable = false, columnDefinition = "TEXT")
    private String message;

    @Column(name = "replied_by")
    private Long repliedBy;

    /**
     * Membezakan balasan SP daripada balasan pengadu.
     *
     * Tanpa bendera ini, mengira 'purata masa maklum balas' bermakna
     * menyemak peranan setiap pengguna pada masa laporan dijana — dan
     * peranan berubah.
     */
    @Column(name = "from_sp", nullable = false)
    private boolean fromSp = false;

    /** Nota dalaman: pengadu TIDAK nampak. */
    @Column(name = "internal", nullable = false)
    private boolean internal = false;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    protected AduReply() {}

    AduReply(Long complaintId, String message, Long repliedBy, boolean fromSp, boolean internal) {
        this.complaintId = complaintId;
        this.message = message;
        this.repliedBy = repliedBy;
        this.fromSp = fromSp;
        this.internal = internal;
    }

    Long getId() { return id; }
    LocalDateTime getCreatedAt() { return createdAt; }
}

/** Tetapan modul — penomboran dan SLA. */
@Entity
@Table(name = "adu_setting")
class AduSetting extends BaseEntity {

    @Id
    @Column(name = "sp_code", length = 20)
    private String spCode;

    @Column(name = "prefix", nullable = false, length = 10)
    private String prefix = "ADU";

    @Column(name = "no_size", nullable = false)
    private int noSize = 6;

    @Column(name = "no_start", nullable = false)
    private long noStart = 1L;

    /** Hari sebelum aduan dikira melebihi SLA dalam dashboard. */
    @Column(name = "sla_days", nullable = false)
    private int slaDays = 5;

    protected AduSetting() {}

    AduSetting(String spCode) { this.spCode = spCode; }

    String getSpCode() { return spCode; }
    String getPrefix() { return prefix; }
    int getNoSize() { return noSize; }
    long getNoStart() { return noStart; }
    int getSlaDays() { return slaDays; }

    void setPrefix(String v) { this.prefix = v; }
    void setNoSize(int v) { this.noSize = v; }
    void setNoStart(long v) { this.noStart = v; }
    void setSlaDays(int v) { this.slaDays = v; }
}
