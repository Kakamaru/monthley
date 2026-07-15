package com.monthley.identity.internal;

import com.monthley.identity.api.UserRole;
import com.monthley.shared.BaseEntity;
import jakarta.persistence.*;

/**
 * Pautan pengguna ↔ SP ↔ peranan.
 * Inilah yang menaikkan taraf pengguna berdaftar menjadi admin SP.
 */
@Entity
@Table(name = "sp_membership")
public class SpMembership extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sp_code", nullable = false, length = 20)
    private String spCode;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 10)
    private UserRole role = UserRole.CLERK;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private Status status = Status.ACTIVE;

    public enum Status { ACTIVE, INACTIVE }

    protected SpMembership() {}

    public SpMembership(String spCode, Long userId, UserRole role) {
        this.spCode = spCode;
        this.userId = userId;
        this.role = role;
    }

    public Long getId() { return id; }
    public String getSpCode() { return spCode; }
    public Long getUserId() { return userId; }
    public UserRole getRole() { return role; }
    public Status getStatus() { return status; }
}
