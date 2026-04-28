package com.bidstream.adapter.in.rest;

import com.bidstream.adapter.in.rest.dto.CategoryDtos.CategoryResponse;
import com.bidstream.adapter.in.rest.dto.CategoryDtos.CreateCategoryRequest;
import com.bidstream.application.CategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** PDR §14.4: the categories table has existed since V1; this is the first endpoint reading it. */
@RestController
@RequestMapping("/api/v1/categories")
@Tag(name = "Categories", description = "Admin-curated auction category taxonomy")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping
    @SecurityRequirements
    @Operation(summary = "List all categories")
    public List<CategoryResponse> list() {
        return categoryService.listAll().stream().map(CategoryResponse::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a category", description = "Requires ROLE_ADMIN (PDR §17.1).")
    public CategoryResponse create(@Valid @RequestBody CreateCategoryRequest request) {
        return CategoryResponse.from(categoryService.create(request.name()));
    }
}
