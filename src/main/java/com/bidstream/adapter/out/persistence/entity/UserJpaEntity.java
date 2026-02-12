package com.bidstream.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "users")
public class UserJpaEntity {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "roles", columnDefinition = "text[]", nullable = false)
    private Set<String> roles;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected UserJpaEntity() {
        // JPA
    }

    public UserJpaEntity(UUID id, String username, String email, String passwordHash,
                          Set<String> roles, Instant createdAt) {
        this.id = id;
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
        this.roles = roles;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Set<String> getRoles() {
        return roles;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
