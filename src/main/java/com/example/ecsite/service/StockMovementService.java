package com.example.ecsite.service;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.entity.StockMovement;
import com.example.ecsite.entity.StockMovementType;
import com.example.ecsite.repository.StockMovementRepository;

@Service
@Transactional(readOnly = true)
public class StockMovementService {

    private static final LocalDateTime DEFAULT_FROM =
            LocalDateTime.of(
                    1970, 1, 1, 0, 0);

    private static final LocalDateTime DEFAULT_TO =
            LocalDateTime.of(
                    9999, 12, 31, 0, 0);

    private final StockMovementRepository stockMovementRepository;

    public StockMovementService(
            StockMovementRepository stockMovementRepository) {

        this.stockMovementRepository =
                stockMovementRepository;
    }

    public Page<StockMovement> search(
            Long productId,
            LocalDate from,
            LocalDate to,
            String username,
            StockMovementType movementType,
            int page,
            int size) {

        LocalDateTime searchFrom =
                from == null
                        ? DEFAULT_FROM
                        : from.atStartOfDay();

        LocalDateTime searchTo =
                to == null
                        ? DEFAULT_TO
                        : to.plusDays(1).atStartOfDay();

        String normalizedUsername =
                username == null
                        ? ""
                        : username.trim();

        Pageable pageable =
                PageRequest.of(page, size);

        return stockMovementRepository.searchByProduct(
                productId,
                searchFrom,
                searchTo,
                normalizedUsername,
                movementType,
                pageable);
    }
}