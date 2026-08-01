package com.monthley.notification.internal;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * Satu baris gilir penghantaran (ADR 0014, V55).
 *
 * Ditulis dalam transaksi yang mencetuskannya; dihantar kemudian oleh
 * tugas berjadual. Jana bil tidak menunggu penyedia e-mel.
 *
 * Dua pasang kunci/nilai, bukan JSON — corak legacy yang terbukti.
 * Params sebenar cuma akaun dan tempoh.
 */
@Entity
@Table(name = "email_outbox")
class EmailOutbox {

    enum Status { PENDING, SENT, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sp_code", nullable = false)
    private String spCode;

    @Column(name = "channel", nullable = false)
    private String channel = "EMAIL";

    @Column(name = "kind", nullable = false)
    private String kind;

    @Column(name = "ref_key", nullable = false)
    private String refKey;

    @Column(name = "to_email", nullable = false)
    private String toEmail;

    @Column(name = "cc_email")
    private String ccEmail;

    @Column(name = "param1_key")
    private String param1Key;

    @Column(name = "param1_val")
    private String param1Val;

    @Column(name = "param2_key")
    private String param2Key;

    @Column(name = "param2_val")
    private String param2Val;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status = Status.PENDING;

    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    protected EmailOutbox() {}

    EmailOutbox(String spCode, String kind, String refKey,
                String toEmail, String ccEmail,
                String param1Key, String param1Val,
                String param2Key, String param2Val) {
        this.spCode = spCode;
        this.kind = kind;
        this.refKey = refKey;
        this.toEmail = toEmail;
        this.ccEmail = ccEmail;
        this.param1Key = param1Key;
        this.param1Val = param1Val;
        this.param2Key = param2Key;
        this.param2Val = param2Val;
    }

    void tandaHantar() {
        this.status = Status.SENT;
        this.sentAt = LocalDateTime.now();
        this.attempts++;
        this.lastError = null;
    }

    /**
     * Percubaan gagal.
     *
     * Kekal PENDING sehingga had percubaan dicapai — kegagalan sementara
     * (penyedia tunggang, had kadar) mesti dicuba semula. FAILED
     * bermakna berhenti mencuba, bukan "gagal sekali".
     */
    void tandaGagal(String sebab, int hadPercubaan) {
        this.attempts++;
        this.lastError = sebab == null ? null
                : sebab.length() > 500 ? sebab.substring(0, 500) : sebab;
        if (this.attempts >= hadPercubaan) {
            this.status = Status.FAILED;
        }
    }

    Long getId() { return id; }
    String getSpCode() { return spCode; }
    String getKind() { return kind; }
    String getRefKey() { return refKey; }
    String getToEmail() { return toEmail; }
    String getCcEmail() { return ccEmail; }
    String getParam1Key() { return param1Key; }
    String getParam1Val() { return param1Val; }
    String getParam2Key() { return param2Key; }
    String getParam2Val() { return param2Val; }
    Status getStatus() { return status; }
    int getAttempts() { return attempts; }
    String getLastError() { return lastError; }
}
