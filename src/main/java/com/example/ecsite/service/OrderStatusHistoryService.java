package com.example.ecsite.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.OrderStatus;
import com.example.ecsite.entity.OrderStatusHistory;
import com.example.ecsite.entity.OrderStatusHistoryActorType;
import com.example.ecsite.form.AdminOrderStatusHistorySearchForm;
import com.example.ecsite.repository.OrderStatusHistoryRepository;

@Service
public class OrderStatusHistoryService {

    private final OrderStatusHistoryRepository orderStatusHistoryRepository;

    public OrderStatusHistoryService(
            OrderStatusHistoryRepository orderStatusHistoryRepository) {

        this.orderStatusHistoryRepository = orderStatusHistoryRepository;
    }

    @Transactional
    public void record(
            Order order,
            OrderStatus fromStatus,
            OrderStatus toStatus,
            OrderStatusHistoryActorType changedByType,
            Long changedByAccountId,
            String changedByUsername) {

        record(
                order,
                fromStatus,
                toStatus,
                changedByType,
                changedByAccountId,
                changedByUsername,
                null);
    }

    public void record(
            Order order,
            OrderStatus fromStatus,
            OrderStatus toStatus,
            OrderStatusHistoryActorType changedByType,
            Long changedByAccountId,
            String changedByUsername,
            String internalNote) {

        OrderStatusHistory history = OrderStatusHistory.create(
                order,
                fromStatus,
                toStatus,
                changedByType,
                changedByAccountId,
                changedByUsername);

        history.setInternalNote(normalizeInternalNote(internalNote));

        orderStatusHistoryRepository.save(history);
    }

    private String normalizeInternalNote(String internalNote) {

        if (internalNote == null) {
            return null;
        }

        String trimmed = internalNote.trim();

        return trimmed.isEmpty() ? null : trimmed;
    }

    @Transactional(readOnly = true)
    public List<OrderStatusHistory> findByOrderId(Long orderId) {

        return orderStatusHistoryRepository
                .findByOrderIdOrderByChangedAtAscIdAsc(orderId);
    }

    @Transactional(readOnly = true)
    public Page<OrderStatusHistory> search(
            AdminOrderStatusHistorySearchForm form,
            int page,
            int size) {

        return search(
                form,
                PageRequest.of(page, size));
    }

    @Transactional(readOnly = true)
    public List<OrderStatusHistory> searchAll(
            AdminOrderStatusHistorySearchForm form) {

        return search(
                form,
                Pageable.unpaged())
                .getContent();
    }

    private Page<OrderStatusHistory> search(
            AdminOrderStatusHistorySearchForm form,
            Pageable pageable) {

        LocalDateTime from = form.getFrom() != null
                ? form.getFrom().atStartOfDay()
                : LocalDate.of(2000, 1, 1).atStartOfDay();

        LocalDateTime toExclusive = form.getTo() != null
                ? form.getTo().plusDays(1).atStartOfDay()
                : LocalDate.of(2100, 1, 1).atStartOfDay();

        String changedByUsername = form.getChangedByUsername();

        if (changedByUsername != null) {
            changedByUsername = changedByUsername.trim();

            if (changedByUsername.isEmpty()) {
                changedByUsername = null;
            }
        }

        return orderStatusHistoryRepository.search(
                form.getOrderId(),
                form.getFromStatus(),
                form.getToStatus(),
                form.getChangedByType(),
                changedByUsername,
                from,
                toExclusive,
                pageable);
    }

}
