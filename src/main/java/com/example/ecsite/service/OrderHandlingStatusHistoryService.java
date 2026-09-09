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
import com.example.ecsite.entity.OrderHandlingStatus;
import com.example.ecsite.entity.OrderHandlingStatusHistory;
import com.example.ecsite.form.AdminOrderHandlingStatusHistorySearchForm;
import com.example.ecsite.repository.OrderHandlingStatusHistoryRepository;

@Service
public class OrderHandlingStatusHistoryService {

    private final OrderHandlingStatusHistoryRepository repository;

    public OrderHandlingStatusHistoryService(
            OrderHandlingStatusHistoryRepository repository) {
        this.repository = repository;
    }

    public void record(
            Order order,
            OrderHandlingStatus fromStatus,
            OrderHandlingStatus toStatus,
            Long changedByAccountId,
            String changedByUsername) {

        OrderHandlingStatusHistory history = OrderHandlingStatusHistory.create(
                order,
                fromStatus,
                toStatus,
                changedByAccountId,
                changedByUsername);

        repository.save(history);
    }

    public List<OrderHandlingStatusHistory> findByOrderId(Long orderId) {
        return repository.findByOrderIdOrderByChangedAtAscIdAsc(orderId);
    }

    @Transactional(readOnly = true)
    public Page<OrderHandlingStatusHistory> search(
            AdminOrderHandlingStatusHistorySearchForm form,
            int page,
            int size) {

        return search(
                form,
                PageRequest.of(page, size));
    }

    @Transactional(readOnly = true)
    public List<OrderHandlingStatusHistory> searchAll(
            AdminOrderHandlingStatusHistorySearchForm form) {

        return search(
                form,
                Pageable.unpaged())
                .getContent();
    }

    private Page<OrderHandlingStatusHistory> search(
            AdminOrderHandlingStatusHistorySearchForm form,
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

        return repository.search(
                form.getOrderId(),
                form.getFromStatus(),
                form.getToStatus(),
                changedByUsername,
                from,
                toExclusive,
                pageable);
    }

}
