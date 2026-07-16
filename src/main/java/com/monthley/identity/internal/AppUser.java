package com.monthley.identity.internal;

import com.monthley.shared.BaseEntity;
import jakarta.persistence.*;

@Entity
@Table(name = "app_user")
public class AppUser extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @Column(name = "full_name", nullable = false, length = 255)
    private String fullName;

    @Column(name = "mobile", length = 30)
    private String mobile;

    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private Status status = Status.ACTIVE;

    @Column(name = "email_verified_at")
    private java.time.LocalDateTime emailVerifiedAt;

    @Column(name = "uuid", length = 36)
    private String uuid;

    public enum Status { ACTIVE, INACTIVE }

    protected AppUser() {}

    public AppUser(String email, String fullName, String mobile, String passwordHash) {
        this.email = email.toLowerCase().trim();
        this.fullName = fullName;
        this.mobile = mobile;
        this.passwordHash = passwordHash;
        this.uuid = java.util.UUID.randomUUID().toString();
    }

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getFullName() { return fullName; }
    public String getMobile() { return mobile; }
    public String getPasswordHash() { return passwordHash; }
    public Status getStatus() { return status; }
    public void setPasswordHash(String h) { this.passwordHash = h; }

    public java.time.LocalDateTime getEmailVerifiedAt() { return emailVerifiedAt; }
    public boolean isEmailVerified() { return emailVerifiedAt != null; }
    public void markEmailVerified() { this.emailVerifiedAt = java.time.LocalDateTime.now(); }
}
