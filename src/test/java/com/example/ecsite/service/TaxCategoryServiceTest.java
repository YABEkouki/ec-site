package com.example.ecsite.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.ecsite.entity.TaxCategory;
import com.example.ecsite.repository.TaxCategoryRepository;

@ExtendWith(MockitoExtension.class)
class TaxCategoryServiceTest {

    @Mock
    private TaxCategoryRepository taxCategoryRepository;

    private TaxCategoryService taxCategoryService;

    @BeforeEach
    void setUp() {
        taxCategoryService =
                new TaxCategoryService(taxCategoryRepository);
    }

    @Test
    void findActiveTaxCategoriesReturnsActiveCategories() {

        TaxCategory standard = createTaxCategory(
                "STANDARD",
                "標準税率");

        TaxCategory reduced = createTaxCategory(
                "REDUCED",
                "軽減税率");

        when(taxCategoryRepository
                .findByActiveTrueOrderByDisplayOrderAscIdAsc())
                .thenReturn(List.of(standard, reduced));

        List<TaxCategory> result =
                taxCategoryService.findActiveTaxCategories();

        assertThat(result)
                .containsExactly(standard, reduced);
    }

    @Test
    void findActiveByIdReturnsActiveCategory() {

        TaxCategory taxCategory = createTaxCategory(
                "STANDARD",
                "標準税率");

        when(taxCategoryRepository.findByIdAndActiveTrue(1L))
                .thenReturn(Optional.of(taxCategory));

        TaxCategory result =
                taxCategoryService.findActiveById(1L);

        assertThat(result).isSameAs(taxCategory);
    }

    @Test
    void findActiveByIdThrowsWhenCategoryDoesNotExist() {

        when(taxCategoryRepository.findByIdAndActiveTrue(1L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(
                () -> taxCategoryService.findActiveById(1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("有効な税区分が見つかりません。");
    }

    @Test
    void findStandardTaxCategoryReturnsStandardCategory() {

        TaxCategory taxCategory = createTaxCategory(
                "STANDARD",
                "標準税率");

        when(taxCategoryRepository.findByCode("STANDARD"))
                .thenReturn(Optional.of(taxCategory));

        TaxCategory result =
                taxCategoryService.findStandardTaxCategory();

        assertThat(result).isSameAs(taxCategory);
    }

    @Test
    void findStandardTaxCategoryThrowsWhenStandardCategoryDoesNotExist() {

        when(taxCategoryRepository.findByCode("STANDARD"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(
                () -> taxCategoryService.findStandardTaxCategory())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("標準税率の税区分が見つかりません。");
    }

    private TaxCategory createTaxCategory(
            String code,
            String name) {

        TaxCategory taxCategory = new TaxCategory();

        taxCategory.setCode(code);
        taxCategory.setName(name);

        return taxCategory;
    }
}
