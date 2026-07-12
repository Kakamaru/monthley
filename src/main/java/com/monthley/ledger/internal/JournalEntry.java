package com.monthley.ledger.internal;

import com.monthley.ledger.api.SourceType;
import com.monthley.shared.BaseEntity;
import jakarta.persistence.*;
import org.hibernate.envers.Audited;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Header journal double-entry. Immutable selepas POSTED.
 * Pembetulan = post entri contra baru (reversesEntryId), bukan edit.
 */
@Entity
@Table(name = "journal_entry")
@Audited
public class JournalEntry extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sp_code", nullable = false, length = 20)
    private String spCode;

    @Column(name = "entry_no", nullable = false, length = 30)
    private String entryNo;

    @Column(name = "entry_date", nullable = false)
    private LocalDate entryDate;

    @Column(name = "period_id")
    private Long periodId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 20)
    private SourceType sourceType;

    @Column(name = "source_document_id")
    private Long sourceDocumentId;

    @Column(name = "description", length = 255)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private Status status = Status.POSTED;

    @Column(name = "reverses_entry_id")
    private Long reversesEntryId;

    @Column(name = "posted_at")
    private LocalDateTime postedAt;

    @Column(name = "posted_by", length = 64)
    private String postedBy;

    @OneToMany(mappedBy = "entry", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<JournalLine> lines = new ArrayList<>();

    public enum Status { DRAFT, POSTED, REVERSED }

    protected JournalEntry() {}

    public JournalEntry(String spCode, String entryNo, LocalDate entryDate,
                        SourceType sourceType, Long sourceDocumentId,
                        String description, Long reversesEntryId) {
        this.spCode = spCode;
        this.entryNo = entryNo;
        this.entryDate = entryDate;
        this.sourceType = sourceType;
        this.sourceDocumentId = sourceDocumentId;
        this.description = description;
        this.reversesEntryId = reversesEntryId;
        this.status = Status.POSTED;
        this.postedAt = LocalDateTime.now();
    }

    public void addLine(JournalLine line) {
        line.setEntry(this);
        this.lines.add(line);
    }

    public void markReversed() { this.status = Status.REVERSED; }

    public Long getId() { return id; }
    public String getSpCode() { return spCode; }
    public String getEntryNo() { return entryNo; }
    public SourceType getSourceType() { return sourceType; }
    public Long getSourceDocumentId() { return sourceDocumentId; }
    public Status getStatus() { return status; }
    public Long getReversesEntryId() { return reversesEntryId; }
    public List<JournalLine> getLines() { return lines; }
    public LocalDate getEntryDate() { return entryDate; }
}
