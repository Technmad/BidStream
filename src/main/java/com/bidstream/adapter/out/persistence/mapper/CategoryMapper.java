package com.bidstream.adapter.out.persistence.mapper;

import com.bidstream.adapter.out.persistence.entity.CategoryJpaEntity;
import com.bidstream.domain.model.Category;

public final class CategoryMapper {

    private CategoryMapper() {
    }

    public static Category toDomain(CategoryJpaEntity entity) {
        return new Category(entity.getId(), entity.getName(), entity.getSlug());
    }

    public static CategoryJpaEntity toEntity(Category category) {
        return new CategoryJpaEntity(category.id(), category.name(), category.slug());
    }
}
