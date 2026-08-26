package com.example.ecsite.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.ecsite.entity.ShippingAddress;

public interface ShippingAddressRepository
        extends JpaRepository<ShippingAddress, Long> {

    List<ShippingAddress> findByUserIdOrderByDefaultAddressDescCreatedAtAsc(
            Long userId);

    Optional<ShippingAddress> findByIdAndUserId(
            Long id,
            Long userId);

    Optional<ShippingAddress> findByUserIdAndDefaultAddressTrue(
            Long userId);

    boolean existsByUserId(Long userId);
}