package com.example.ecsite.service;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import com.example.ecsite.entity.StockMovement;
import com.example.ecsite.entity.StockMovementType;
import com.example.ecsite.repository.StockMovementRepository;

@ExtendWith(MockitoExtension.class)
class StockMovementServiceTest {

        @Mock
        private StockMovementRepository stockMovementRepository;

        @Test
        void searchConvertsConditionsForRepository() {

                Long productId = 1L;

                LocalDate from = LocalDate.of(2026, 8, 1);
                LocalDate to = LocalDate.of(2026, 8, 27);

                Page<StockMovement> expected = new PageImpl<>(List.of());

                when(stockMovementRepository.searchByProduct(
                                eq(productId),
                                eq(LocalDateTime.of(
                                                2026, 8, 1, 0, 0)),
                                eq(LocalDateTime.of(
                                                2026, 8, 28, 0, 0)),
                                eq("admin"),
                                eq(StockMovementType.ORDER_PLACEMENT),
                                any(Pageable.class)))
                                .thenReturn(expected);

                StockMovementService stockMovementService = new StockMovementService(
                                stockMovementRepository);

                Page<StockMovement> actual = stockMovementService.search(
                                productId,
                                from,
                                to,
                                "  admin  ",
                                StockMovementType.ORDER_PLACEMENT,
                                0,
                                20);

                assertSame(expected, actual);

                verify(stockMovementRepository)
                                .searchByProduct(
                                                eq(productId),
                                                eq(LocalDateTime.of(
                                                                2026, 8, 1, 0, 0)),
                                                eq(LocalDateTime.of(
                                                                2026, 8, 28, 0, 0)),
                                                eq("admin"),
                                                eq(StockMovementType.ORDER_PLACEMENT),
                                                any(Pageable.class));
        }

        @Test
        void searchUsesDefaultConditionsWhenNotSpecified() {

                Long productId = 1L;

                LocalDateTime expectedFrom = LocalDateTime.of(
                                1970, 1, 1, 0, 0);

                LocalDateTime expectedTo = LocalDateTime.of(
                                9999, 12, 31, 0, 0);

                Page<StockMovement> expected = new PageImpl<>(List.of());

                when(stockMovementRepository.searchByProduct(
                                eq(productId),
                                eq(expectedFrom),
                                eq(expectedTo),
                                eq(""),
                                isNull(),
                                any(Pageable.class)))
                                .thenReturn(expected);

                StockMovementService stockMovementService = new StockMovementService(
                                stockMovementRepository);

                Page<StockMovement> actual = stockMovementService.search(
                                productId,
                                null,
                                null,
                                null,
                                null,
                                0,
                                20);

                assertSame(expected, actual);

                verify(stockMovementRepository)
                                .searchByProduct(
                                                eq(productId),
                                                eq(expectedFrom),
                                                eq(expectedTo),
                                                eq(""),
                                                isNull(),
                                                any(Pageable.class));
        }
}
