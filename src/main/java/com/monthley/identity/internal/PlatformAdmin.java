package com.monthley.identity.internal;

import com.monthley.shared.BaseEntity;
import jakarta.persistence.*;

/** Superadmin Monthley — onboard SP, urus platform. */
@Entity
@Table(name = "platform_admin")
public class PlatformAdmin extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @Column(name = "full_name", nullable = false, length = 255)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 12)
    private Role role = Role.SUPPORT;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private Status status = Status.ACTIVE;

    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    public enum Role { SUPERADMIN, SUPPORT }
    public enum Status { ACTIVE, INACTIVE }

    protected PlatformAdmin() {}

    public PlatformAdmin(String email, String fullName, Role role, String passwordHash) {
        this.email = email.toLowerCase().trim();
        this.fullName = fullName;
        this.role = role;
        this.passwordHash = passwordHash;
    }

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getFullName() { return fullName; }
    public Role getRole() { return role; }
    public Status getStatus() { return status; }
    public String getPasswordHash() { return passwordHash; }
}
