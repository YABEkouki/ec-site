package com.example.ecsite.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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

import com.example.ecsite.entity.Category;
import com.example.ecsite.exception.CategoryAlreadyExistsException;
import com.example.ecsite.exception.CategoryNotFoundException;
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
}