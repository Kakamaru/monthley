package com.monthley.account.internal;

import com.monthley.shared.BaseEntity;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "account_invitation")
public class AccountInvitation extends BaseEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sp_code", nullable = false, length = 20)
    private String spCode;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "email", nullable = false)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private Status status = Status.PENDING;

    @Column(name = "invited_by", length = 64)
    private String invitedBy;

    @Column(name = "accepted_at")
    private LocalDateTime acceptedAt;

    public enum Status { PENDING, ACCEPTED, CANCELLED }

    protected AccountInvitation() {}

    public AccountInvitation(String spCode, Long accountId, String email, String invitedBy) {
        this.spCode = spCode;
        this.accountId = accountId;
        this.email = email;
        this.invitedBy = invitedBy;
    }

    public Long getId() { return id; }
    public Long getAccountId() { return accountId; }
    public String getEmail() { return email; }
    public Status getStatus() { return status; }
    public void setStatus(Status s) { this.status = s; }
    public void setAcceptedAt(LocalDateTime t) { this.acceptedAt = t; }
}
