package com.bidstream.adapter.out.persistence.repository;

import com.bidstream.adapter.out.persistence.entity.CategoryJpaEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryJpaRepository extends JpaRepository<CategoryJpaEntity, UUID> {

    List<CategoryJpaEntity> findAllByOrderByNameAsc();

    boolean existsByNameIgnoreCaseOrSlugIgnoreCase(String name, String slug);
}
