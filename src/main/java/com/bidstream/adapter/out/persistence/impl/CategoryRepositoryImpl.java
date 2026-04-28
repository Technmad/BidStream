package com.bidstream.adapter.out.persistence.impl;

import com.bidstream.adapter.out.persistence.mapper.CategoryMapper;
import com.bidstream.adapter.out.persistence.repository.CategoryJpaRepository;
import com.bidstream.domain.model.Category;
import com.bidstream.domain.port.CategoryRepository;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class CategoryRepositoryImpl implements CategoryRepository {

    private final CategoryJpaRepository jpaRepository;

    public CategoryRepositoryImpl(CategoryJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public List<Category> findAllOrderByName() {
        return jpaRepository.findAllByOrderByNameAsc().stream().map(CategoryMapper::toDomain).toList();
    }

    @Override
    public Category save(Category category) {
        return CategoryMapper.toDomain(jpaRepository.save(CategoryMapper.toEntity(category)));
    }

    @Override
    public boolean existsByNameOrSlug(String name, String slug) {
        return jpaRepository.existsByNameIgnoreCaseOrSlugIgnoreCase(name, slug);
    }
}
