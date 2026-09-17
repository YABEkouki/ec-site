package com.example.ecsite.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.util.ReflectionTestUtils;

import com.example.ecsite.entity.Category;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CategoryRepositoryTest {

    @Autowired
    private CategoryRepository categoryRepository;

    @Test
    void findHomeCategoriesReturnsOnlyActiveNonSystemCategoriesInDisplayOrder() {

        Category categoryB = createCategory(
                "Feature68-B",
                true,
                false,
                20);

        Category categoryC = createCategory(
                "Feature68-C",
                true,
                false,
                10);

        Category categoryA = createCategory(
                "Feature68-A",
                true,
                false,
                20);

        createCategory(
                "Feature68-Inactive",
                false,
                false,
                1);

        createCategory(
                "Feature68-System",
                true,
                true,
                1);

        List<Category> result = categoryRepository
                .findByActiveTrueAndSystemCategoryFalseOrderByDisplayOrderAscNameAsc();

        assertThat(result)
                .extracting(Category::getId)
                .containsSubsequence(
                        categoryC.getId(),
                        categoryA.getId(),
                        categoryB.getId());

        assertThat(result)
                .extracting(Category::getName)
                .doesNotContain(
                        "Feature68-Inactive",
                        "Feature68-System");
    }

    private Category createCategory(
            String name,
            boolean active,
            boolean systemCategory,
            int displayOrder) {

        Category category = new Category(name);
        category.setActive(active);
        category.setDisplayOrder(displayOrder);

        ReflectionTestUtils.setField(
                category,
                "systemCategory",
                systemCategory);

        return categoryRepository.save(category);
    }
}
