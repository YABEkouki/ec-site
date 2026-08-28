package com.example.ecsite.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.entity.Product;
import com.example.ecsite.entity.StockMovement;
import com.example.ecsite.exception.ProductNotFoundException;
import com.example.ecsite.repository.ProductRepository;
import com.example.ecsite.repository.StockMovementRepository;

@Service
@Transactional
public class InventoryService {

    private final ProductRepository productRepository;
    private final StockMovementRepository stockMovementRepository;

    public InventoryService(
            ProductRepository productRepository,
            StockMovementRepository stockMovementRepository) {

        this.productRepository = productRepository;
        this.stockMovementRepository = stockMovementRepository;
    }

    public void adjustByAdmin(
            Long productId,
            int quantity,
            Long adminAccountId,
            String adminUsername,
            String reason) {

        Product product = productRepository
                .findByIdForUpdate(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));

        int stockBefore = product.getStock();

        product.adjustStock(quantity);

        int stockAfter = product.getStock();

        StockMovement movement = StockMovement.createAdminAdjustment(
                product,
                stockBefore,
                stockAfter,
                quantity,
                adminAccountId,
                adminUsername,
                reason);

        stockMovementRepository.save(movement);
    }

    public void decreaseForOrder(
            Long productId,
            int quantity,
            Long orderId) {

        Product product = productRepository
                .findByIdForUpdate(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));

        int stockBefore = product.getStock();

        product.adjustStock(-quantity);

        int stockAfter = product.getStock();

        StockMovement movement = StockMovement.createOrderPlacement(
                product,
                stockBefore,
                stockAfter,
                -quantity,
                orderId);

        stockMovementRepository.save(movement);
    }

    public void restoreForOrderCancellation(
            Long productId,
            int quantity,
            Long orderId) {

        Product product = productRepository
                .findByIdForUpdateIncludingInactive(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));

        int stockBefore = product.getStock();

        product.adjustStock(quantity);

        int stockAfter = product.getStock();

        StockMovement movement = StockMovement.createOrderCancellation(
                product,
                stockBefore,
                stockAfter,
                quantity,
                orderId);

        stockMovementRepository.save(movement);
    }

}