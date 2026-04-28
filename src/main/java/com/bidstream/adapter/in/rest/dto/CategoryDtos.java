package com.bidstream.adapter.in.rest.dto;

import com.bidstream.domain.model.Category;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

public final class CategoryDtos {

    private CategoryDtos() {
    }

    public record CreateCategoryRequest(@NotBlank String name) {
    }

    public record CategoryResponse(UUID id, String name, String slug) {

        public static CategoryResponse from(Category category) {
            return new CategoryResponse(category.id(), category.name(), category.slug());
        }
    }
}
