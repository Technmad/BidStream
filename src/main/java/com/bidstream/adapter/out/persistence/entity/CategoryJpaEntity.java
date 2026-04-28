package com.bidstream.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** PDR §7.1/§14.4: an admin-curated taxonomy - the table has existed since V1, unread until v1.4. */
@Entity
@Table(name = "categories")
public class CategoryJpaEntity {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(nullable = false, unique = true)
    private String slug;

    protected CategoryJpaEntity() {
        // JPA
    }

    public CategoryJpaEntity(UUID id, String name, String slug) {
        this.id = id;
        this.name = name;
        this.slug = slug;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getSlug() {
        return slug;
    }
}
