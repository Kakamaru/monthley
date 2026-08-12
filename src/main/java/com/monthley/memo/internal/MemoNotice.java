package com.monthley.memo.internal;

import com.monthley.shared.BaseEntity;
import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Hebahan kepada pelanggan.
 *
 * expiresOn ialah per memo dan bukan tetapan global: hebahan kerja
 * penyelenggaraan patut hilang selepas kerja siap, tetapi nombor telefon
 * pengurusan baharu tidak patut luput langsung. NULL bermakna kekal
 * aktif.
 */
@Entity
@Table(name = "memo_notice")
class MemoNotice extends BaseEntity {

    enum Status { DRAFT, PUBLISHED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sp_code", nullable = false, length = 20)
    private String spCode;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private Status status = Status.DRAFT;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "expires_on")
    private LocalDate expiresOn;

    protected MemoNotice() {}

    MemoNotice(String spCode, String title, String body) {
        this.spCode = spCode;
        this.title = title;
        this.body = body;
    }

    Long getId() { return id; }
    String getSpCode() { return spCode; }
    String getTitle() { return title; }
    String getBody() { return body; }
    Status getStatus() { return status; }
    LocalDateTime getPublishedAt() { return publishedAt; }
    LocalDate getExpiresOn() { return expiresOn; }

    void setTitle(String v) { this.title = v; }
    void setBody(String v) { this.body = v; }
    void setExpiresOn(LocalDate v) { this.expiresOn = v; }

    /**
     * Terbitkan.
     *
     * publishedAt ditetapkan sekali sahaja — menerbitkan semula memo yang
     * sudah terbit tidak menukar tarikhnya, kerana pelanggan sudah
     * melihatnya pada tarikh asal.
     */
    void publish() {
        this.status = Status.PUBLISHED;
        if (this.publishedAt == null) {
            this.publishedAt = LocalDateTime.now();
        }
    }

    void unpublish() {
        this.status = Status.DRAFT;
    }

    /**
     * Tamatkan lebih awal — memo masuk ke 'Memo Lama' pelanggan.
     *
     * Berbeza daripada unpublish(): status kekal PUBLISHED dan tarikh
     * terbit kekal, jadi rekod menunjukkan memo ini PERNAH dihebahkan.
     * Menariknya balik menjadikan memo yang sudah dibaca selama dua minggu
     * kelihatan seolah-olah tidak pernah wujud.
     *
     * Semalam dan bukan hari ini: sempadan aktif ialah
     * 'expires_on >= CURDATE()', jadi tarikh hari ini bermakna ia masih
     * kelihatan sehingga tengah malam.
     */
    void endNow() {
        this.expiresOn = LocalDate.now().minusDays(1);
    }
}
