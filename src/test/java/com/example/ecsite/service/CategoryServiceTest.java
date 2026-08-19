package com.example.ecsite.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import com.example.ecsite.entity.Category;
import com.example.ecsite.exception.CategoryAlreadyExistsException;
import com.example.ecsite.exception.CategoryNotFoundException;
import com.example.ecsite.exception.ProtectedCategoryException;
import com.example.ecsite.form.CategoryForm;
import com.example.ecsite.repository.CategoryRepository;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

        @Mock
        private CategoryRepository categoryRepository;

        private CategoryService categoryService;

        @BeforeEach
        void setUp() {

                categoryService = new CategoryService(categoryRepository);
        }

        @Test
        void createTrimsNameAndSavesCategory() {

                CategoryForm form = new CategoryForm();
                form.setName("  食品  ");

                when(categoryRepository
                                .existsByNameIgnoreCase("食品"))
                                .thenReturn(false);

                when(categoryRepository.saveAndFlush(any()))
                                .thenAnswer(invocation -> invocation.getArgument(0));

                Category result = categoryService.create(form);

                ArgumentCaptor<Category> captor = ArgumentCaptor.forClass(Category.class);

                verify(categoryRepository)
                                .saveAndFlush(captor.capture());

                assertEquals(
                                "食品",
                                captor.getValue().getName());

                assertEquals(
                                "食品",
                                result.getName());
        }

        @Test
        void createRejectsDuplicateName() {

                CategoryForm form = new CategoryForm();
                form.setName("食品");

                when(categoryRepository
                                .existsByNameIgnoreCase("食品"))
                                .thenReturn(true);

                assertThrows(
                                CategoryAlreadyExistsException.class,
                                () -> categoryService.create(form));

                verify(categoryRepository, never())
                                .saveAndFlush(any());
        }

        @Test
        void findActiveByIdReturnsCategory() {

                Category category = new Category("食品");

                when(categoryRepository
                                .findByIdAndActiveTrue(1L))
                                .thenReturn(Optional.of(category));

                Category result = categoryService.findActiveById(1L);

                assertSame(category, result);
        }

        @Test
        void findActiveByIdThrowsWhenNotFound() {

                when(categoryRepository
                                .findByIdAndActiveTrue(99L))
                                .thenReturn(Optional.empty());

                assertThrows(
                                CategoryNotFoundException.class,
                                () -> categoryService
                                                .findActiveById(99L));
        }

        @Test
        void createConvertsDatabaseDuplicateError() {

                CategoryForm form = new CategoryForm();
                form.setName("食品");

                when(categoryRepository
                                .existsByNameIgnoreCase("食品"))
                                .thenReturn(false);

                when(categoryRepository.saveAndFlush(any()))
                                .thenThrow(
                                                new DataIntegrityViolationException(
                                                                "duplicate category"));

                assertThrows(
                                CategoryAlreadyExistsException.class,
                                () -> categoryService.create(form));

                verify(categoryRepository)
                                .saveAndFlush(any());
        }

        @Test
        void updateTrimsNameAndSavesCategory() {

                Category category = new Category("旧カテゴリ名");

                CategoryForm form = new CategoryForm();

                form.setName("  新カテゴリ名  ");

                when(categoryRepository.findById(1L))
                                .thenReturn(Optional.of(category));

                when(categoryRepository
                                .existsByNameIgnoreCaseAndIdNot(
                                                "新カテゴリ名",
                                                1L))
                                .thenReturn(false);

                when(categoryRepository
                                .saveAndFlush(category))
                                .thenReturn(category);

                Category result = categoryService.update(
                                1L,
                                form);

                assertSame(category, result);

                assertEquals(
                                "新カテゴリ名",
                                result.getName());

                verify(categoryRepository)
                                .existsByNameIgnoreCaseAndIdNot(
                                                "新カテゴリ名",
                                                1L);

                verify(categoryRepository)
                                .saveAndFlush(category);
        }

        @Test
        void updateRejectsDuplicateName() {

                Category category = new Category("旧カテゴリ名");

                CategoryForm form = new CategoryForm();

                form.setName("  食品  ");

                when(categoryRepository.findById(1L))
                                .thenReturn(Optional.of(category));

                when(categoryRepository
                                .existsByNameIgnoreCaseAndIdNot(
                                                "食品",
                                                1L))
                                .thenReturn(true);

                assertThrows(
                                CategoryAlreadyExistsException.class,
                                () -> categoryService.update(
                                                1L,
                                                form));

                assertEquals(
                                "旧カテゴリ名",
                                category.getName());

                verify(categoryRepository, never())
                                .saveAndFlush(any());
        }

        @Test
        void updateThrowsWhenCategoryNotFound() {

                CategoryForm form = new CategoryForm();

                form.setName("食品");

                when(categoryRepository.findById(99L))
                                .thenReturn(Optional.empty());

                assertThrows(
                                CategoryNotFoundException.class,
                                () -> categoryService.update(
                                                99L,
                                                form));

                verify(categoryRepository, never())
                                .existsByNameIgnoreCaseAndIdNot(
                                                anyString(),
                                                anyLong());

                verify(categoryRepository, never())
                                .saveAndFlush(any());
        }

        @Test
        void updateConvertsDatabaseDuplicateError() {

                Category category = new Category("旧カテゴリ名");

                CategoryForm form = new CategoryForm();

                form.setName("食品");

                when(categoryRepository.findById(1L))
                                .thenReturn(Optional.of(category));

                when(categoryRepository
                                .existsByNameIgnoreCaseAndIdNot(
                                                "食品",
                                                1L))
                                .thenReturn(false);

                when(categoryRepository
                                .saveAndFlush(category))
                                .thenThrow(
                                                new DataIntegrityViolationException(
                                                                "duplicate category"));

                assertThrows(
                                CategoryAlreadyExistsException.class,
                                () -> categoryService.update(
                                                1L,
                                                form));

                verify(categoryRepository)
                                .saveAndFlush(category);
        }

        @Test
        void updateRejectsRenamingSystemCategory() {

                Category category = new Category("未分類");

                ReflectionTestUtils.setField(
                                category,
                                "systemCategory",
                                true);

                CategoryForm form = new CategoryForm();

                form.setName("その他");

                when(categoryRepository.findById(1L))
                                .thenReturn(Optional.of(category));

                assertThrows(
                                ProtectedCategoryException.class,
                                () -> categoryService.update(
                                                1L,
                                                form));

                assertEquals(
                                "未分類",
                                category.getName());

                verify(categoryRepository, never())
                                .existsByNameIgnoreCaseAndIdNot(
                                                anyString(),
                                                anyLong());

                verify(categoryRepository, never())
                                .saveAndFlush(any());
        }

        @Test
        void deactivateSetsCategoryInactive() {

                Category category = new Category("食品");

                when(categoryRepository.findById(1L))
                                .thenReturn(Optional.of(category));

                when(categoryRepository
                                .saveAndFlush(category))
                                .thenReturn(category);

                Category result = categoryService.deactivate(1L);

                assertSame(category, result);

                assertEquals(
                                false,
                                result.isActive());

                verify(categoryRepository)
                                .saveAndFlush(category);
        }

        @Test
        void deactivateRejectsSystemCategory() {

                Category category = new Category("未分類");

                ReflectionTestUtils.setField(
                                category,
                                "systemCategory",
                                true);

                when(categoryRepository.findById(1L))
                                .thenReturn(Optional.of(category));

                assertThrows(
                                ProtectedCategoryException.class,
                                () -> categoryService.deactivate(1L));

                assertTrue(category.isActive());

                verify(categoryRepository, never())
                                .saveAndFlush(any());
        }

        @Test
        void activateSetsCategoryActive() {

                Category category = new Category("食品");

                category.setActive(false);

                when(categoryRepository.findById(1L))
                                .thenReturn(Optional.of(category));

                when(categoryRepository
                                .saveAndFlush(category))
                                .thenReturn(category);

                Category result = categoryService.activate(1L);

                assertSame(category, result);

                assertTrue(result.isActive());

                verify(categoryRepository)
                                .saveAndFlush(category);
        }
}