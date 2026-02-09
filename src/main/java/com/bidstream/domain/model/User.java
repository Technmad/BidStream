package com.bidstream.domain.model;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public final class User {

    private final UUID id;
    private final String username;
    private final String email;
    private final String passwordHash;
    private final Set<String> roles;
    private final Instant createdAt;

    public User(UUID id, String username, String email, String passwordHash,
                Set<String> roles, Instant createdAt) {
        this.id = id;
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
        this.roles = Set.copyOf(roles);
        this.createdAt = createdAt;
    }

    public UUID id() {
        return id;
    }

    public String username() {
        return username;
    }

    public String email() {
        return email;
    }

    public String passwordHash() {
        return passwordHash;
    }

    public Set<String> roles() {
        return roles;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
