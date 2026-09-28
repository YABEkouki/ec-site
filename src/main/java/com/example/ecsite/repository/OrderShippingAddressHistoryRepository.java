package com.example.ecsite.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.ecsite.entity.OrderShippingAddressHistory;

public interface OrderShippingAddressHistoryRepository
        extends JpaRepository<OrderShippingAddressHistory, Long> {

    List<OrderShippingAddressHistory> findByOrderIdOrderByChangedAtAscIdAsc(Long orderId);
}
