package com.bidstream.application;

import com.bidstream.common.ConflictException;
import com.bidstream.domain.model.Category;
import com.bidstream.domain.port.CategoryRepository;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** PDR §14.4: admin-curated taxonomy - sellers pick from this list, only ROLE_ADMIN extends it. */
@Service
public class CategoryService {

    private static final Pattern NON_SLUG_CHARS = Pattern.compile("[^a-z0-9]+");

    private final CategoryRepository categoryRepository;

    public CategoryService(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    public List<Category> listAll() {
        return categoryRepository.findAllOrderByName();
    }

    @Transactional
    public Category create(String name) {
        String slug = slugify(name);
        if (categoryRepository.existsByNameOrSlug(name, slug)) {
            throw new ConflictException("A category named '" + name + "' already exists");
        }
        return categoryRepository.save(new Category(UUID.randomUUID(), name, slug));
    }

    private static String slugify(String name) {
        String slug = NON_SLUG_CHARS.matcher(name.toLowerCase().trim()).replaceAll("-");
        slug = slug.replaceAll("^-+|-+$", "");
        if (slug.isEmpty()) {
            throw new IllegalArgumentException("Category name must contain at least one letter or digit");
        }
        return slug;
    }
}
