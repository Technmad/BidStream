package com.bidstream.domain.port;

import com.bidstream.domain.model.Category;
import java.util.List;

public interface CategoryRepository {

    List<Category> findAllOrderByName();

    Category save(Category category);

    boolean existsByNameOrSlug(String name, String slug);
}
