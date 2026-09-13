package com.example.ecsite.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.OrderAssigneeHistory;
import com.example.ecsite.form.AdminOrderAssigneeHistoryFilter;
import com.example.ecsite.form.AdminOrderAssigneeHistorySearchForm;
import com.example.ecsite.repository.OrderAssigneeHistoryRepository;

@Service
public class OrderAssigneeHistoryService {

    private final OrderAssigneeHistoryRepository repository;

    public OrderAssigneeHistoryService(
            OrderAssigneeHistoryRepository repository) {
        this.repository = repository;
    }

    public void record(
            Order order,
            Long fromAdminAccountId,
            String fromAdminUsername,
            Long toAdminAccountId,
            String toAdminUsername,
            Long changedByAccountId,
            String changedByUsername,
            UUID changeEventId) {

        OrderAssigneeHistory history = OrderAssigneeHistory.create(
                order,
                fromAdminAccountId,
                fromAdminUsername,
                toAdminAccountId,
                toAdminUsername,
                changedByAccountId,
                changedByUsername,
                changeEventId);

        repository.save(history);
    }

    public List<OrderAssigneeHistory> findByOrderId(Long orderId) {
        return repository.findByOrderIdOrderByChangedAtAscIdAsc(orderId);
    }

    public Page<OrderAssigneeHistory> search(
            AdminOrderAssigneeHistorySearchForm form,
            int page,
            int size) {

        Boolean fromUnassigned = toUnassignedFlag(
                form.getFromAssigneeFilter());

        Long fromAdminAccountId = form.getFromAssigneeFilter() == AdminOrderAssigneeHistoryFilter.SPECIFIC
                ? form.getFromAdminAccountId()
                : null;

        Boolean toUnassigned = toUnassignedFlag(
                form.getToAssigneeFilter());

        Long toAdminAccountId = form.getToAssigneeFilter() == AdminOrderAssigneeHistoryFilter.SPECIFIC
                ? form.getToAdminAccountId()
                : null;

        String changedByUsername = normalizeUsername(
                form.getChangedByUsername());

        LocalDateTime from = form.getFrom() == null
                ? LocalDateTime.of(2000, 1, 1, 0, 0)
                : form.getFrom().atStartOfDay();

        LocalDateTime toExclusive = form.getTo() == null
                ? LocalDateTime.of(2100, 1, 1, 0, 0)
                : form.getTo().plusDays(1).atStartOfDay();

        return repository.search(
                form.getOrderId(),
                fromUnassigned,
                fromAdminAccountId,
                toUnassigned,
                toAdminAccountId,
                changedByUsername,
                from,
                toExclusive,
                PageRequest.of(page, size));
    }

    private Boolean toUnassignedFlag(
            AdminOrderAssigneeHistoryFilter filter) {

        if (filter == null
                || filter == AdminOrderAssigneeHistoryFilter.ALL) {
            return null;
        }

        if (filter == AdminOrderAssigneeHistoryFilter.UNASSIGNED) {
            return true;
        }

        return false;
    }

    private String normalizeUsername(String username) {

        if (username == null) {
            return null;
        }

        String trimmed = username.trim();

        return trimmed.isEmpty()
                ? null
                : trimmed;
    }

}
