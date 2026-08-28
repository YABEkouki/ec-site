package com.example.ecsite.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.ecsite.entity.Product;
import com.example.ecsite.entity.StockMovement;
import com.example.ecsite.entity.StockMovementActorType;
import com.example.ecsite.entity.StockMovementType;
import com.example.ecsite.exception.InvalidStockAdjustmentException;
import com.example.ecsite.repository.ProductRepository;
import com.example.ecsite.repository.StockMovementRepository;

@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

        @Mock
        private ProductRepository productRepository;

        @Mock
        private StockMovementRepository stockMovementRepository;

        @Test
        void adjustByAdminUpdatesStockAndSavesMovement() {

                Long productId = 1L;

                Product product = new Product();
                product.setId(productId);
                product.setName("テスト商品");
                product.setStock(10);

                when(productRepository.findByIdForUpdate(productId))
                                .thenReturn(Optional.of(product));

                InventoryService inventoryService = new InventoryService(
                                productRepository,
                                stockMovementRepository);

                inventoryService.adjustByAdmin(
                                productId,
                                -3,
                                10L,
                                "admin",
                                "棚卸し差異");

                assertEquals(7, product.getStock());

                verify(productRepository)
                                .findByIdForUpdate(productId);

                ArgumentCaptor<StockMovement> captor = ArgumentCaptor.forClass(StockMovement.class);

                verify(stockMovementRepository)
                                .save(captor.capture());

                StockMovement movement = captor.getValue();

                assertEquals(
                                StockMovementType.ADMIN_ADJUSTMENT,
                                movement.getMovementType());

                assertEquals(product, movement.getProduct());
                assertEquals("テスト商品", movement.getProductName());

                assertEquals(-3, movement.getQuantity());
                assertEquals(10, movement.getStockBefore());
                assertEquals(7, movement.getStockAfter());

                assertEquals(
                                StockMovementActorType.ADMIN,
                                movement.getChangedByType());

                assertEquals(10L, movement.getChangedByAccountId());
                assertEquals("admin", movement.getChangedByUsername());

                assertNull(movement.getOrderId());

                assertEquals(
                                "棚卸し差異",
                                movement.getReason());
        }

        @Test
        void adjustByAdminDoesNotSaveMovementWhenStockWouldBecomeNegative() {

                Long productId = 1L;

                Product product = new Product();
                product.setId(productId);
                product.setName("テスト商品");
                product.setStock(2);

                when(productRepository.findByIdForUpdate(productId))
                                .thenReturn(Optional.of(product));

                InventoryService inventoryService = new InventoryService(
                                productRepository,
                                stockMovementRepository);

                assertThrows(
                                InvalidStockAdjustmentException.class,
                                () -> inventoryService.adjustByAdmin(
                                                productId,
                                                -3,
                                                10L,
                                                "admin",
                                                "棚卸し差異"));

                assertEquals(2, product.getStock());

                verify(stockMovementRepository, never())
                                .save(any(StockMovement.class));
        }

        @Test
        void adjustByAdminDoesNotSaveMovementWhenQuantityIsZero() {

                Long productId = 1L;

                Product product = new Product();
                product.setId(productId);
                product.setName("テスト商品");
                product.setStock(10);

                when(productRepository.findByIdForUpdate(productId))
                                .thenReturn(Optional.of(product));

                InventoryService inventoryService = new InventoryService(
                                productRepository,
                                stockMovementRepository);

                assertThrows(
                                InvalidStockAdjustmentException.class,
                                () -> inventoryService.adjustByAdmin(
                                                productId,
                                                0,
                                                10L,
                                                "admin",
                                                null));

                assertEquals(10, product.getStock());

                verify(stockMovementRepository, never())
                                .save(any(StockMovement.class));
        }

        @Test
        void decreaseForOrderUpdatesStockAndSavesOrderPlacementMovement() {

                Long productId = 1L;
                Long orderId = 100L;

                Product product = new Product();
                product.setId(productId);
                product.setName("テスト商品");
                product.setStock(10);

                when(productRepository.findByIdForUpdate(productId))
                                .thenReturn(Optional.of(product));

                InventoryService inventoryService = new InventoryService(
                                productRepository,
                                stockMovementRepository);

                inventoryService.decreaseForOrder(
                                productId,
                                3,
                                orderId);

                assertEquals(7, product.getStock());

                verify(productRepository)
                                .findByIdForUpdate(productId);

                ArgumentCaptor<StockMovement> captor = ArgumentCaptor.forClass(StockMovement.class);

                verify(stockMovementRepository)
                                .save(captor.capture());

                StockMovement movement = captor.getValue();

                assertEquals(
                                StockMovementType.ORDER_PLACEMENT,
                                movement.getMovementType());

                assertEquals(product, movement.getProduct());
                assertEquals("テスト商品", movement.getProductName());

                assertEquals(-3, movement.getQuantity());
                assertEquals(10, movement.getStockBefore());
                assertEquals(7, movement.getStockAfter());

                assertEquals(
                                StockMovementActorType.SYSTEM,
                                movement.getChangedByType());

                assertNull(movement.getChangedByAccountId());
                assertEquals("SYSTEM", movement.getChangedByUsername());

                assertEquals(orderId, movement.getOrderId());
                assertNull(movement.getReason());
        }

        @Test
        void restoreForOrderCancellationUpdatesStockAndSavesMovement() {

                Long productId = 1L;
                Long orderId = 100L;

                Product product = new Product();
                product.setId(productId);
                product.setName("テスト商品");
                product.setStock(7);

                when(productRepository.findByIdForUpdateIncludingInactive(productId))
                                .thenReturn(Optional.of(product));

                InventoryService inventoryService = new InventoryService(
                                productRepository,
                                stockMovementRepository);

                inventoryService.restoreForOrderCancellation(
                                productId,
                                3,
                                orderId);

                assertEquals(10, product.getStock());

                verify(productRepository)
                                .findByIdForUpdateIncludingInactive(productId);

                ArgumentCaptor<StockMovement> captor = ArgumentCaptor.forClass(StockMovement.class);

                verify(stockMovementRepository)
                                .save(captor.capture());

                StockMovement movement = captor.getValue();

                assertEquals(
                                StockMovementType.ORDER_CANCELLATION,
                                movement.getMovementType());

                assertEquals(product, movement.getProduct());
                assertEquals("テスト商品", movement.getProductName());

                assertEquals(3, movement.getQuantity());
                assertEquals(7, movement.getStockBefore());
                assertEquals(10, movement.getStockAfter());

                assertEquals(
                                StockMovementActorType.SYSTEM,
                                movement.getChangedByType());

                assertNull(movement.getChangedByAccountId());
                assertEquals("SYSTEM", movement.getChangedByUsername());

                assertEquals(orderId, movement.getOrderId());
                assertNull(movement.getReason());
        }
}