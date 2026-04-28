package com.bidstream.domain.model;

import java.util.UUID;

/**
 * PDR §7.1/§14.4: an admin-curated taxonomy, deliberately not part of the {@link AuctionItem}
 * aggregate - an auction merely references a {@code categoryId}, so a category never needs
 * §9's concurrency machinery.
 */
public final class Category {

    private final UUID id;
    private final String name;
    private final String slug;

    public Category(UUID id, String name, String slug) {
        this.id = id;
        this.name = name;
        this.slug = slug;
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String slug() {
        return slug;
    }
}
