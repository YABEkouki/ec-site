package com.example.ecsite.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.ecsite.entity.User;
import com.example.ecsite.repository.projection.AdminCustomerListProjection;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);

    @Query(value = """
            SELECT
                u.id AS userId,
                u.username AS username,
                up.name AS name,
                u.enabled AS enabled
            FROM users u
            LEFT JOIN user_profiles up
                ON up.user_id = u.id
            WHERE (:userId IS NULL OR u.id = :userId)
              AND (:username IS NULL OR u.username LIKE CONCAT('%', :username, '%'))
              AND (:name IS NULL OR up.name LIKE CONCAT('%', :name, '%'))
              AND (:enabled IS NULL OR u.enabled = :enabled)
            """,
            countQuery = """
            SELECT COUNT(*)
            FROM users u
            LEFT JOIN user_profiles up
                ON up.user_id = u.id
            WHERE (:userId IS NULL OR u.id = :userId)
              AND (:username IS NULL OR u.username LIKE CONCAT('%', :username, '%'))
              AND (:name IS NULL OR up.name LIKE CONCAT('%', :name, '%'))
              AND (:enabled IS NULL OR u.enabled = :enabled)
            """,
            nativeQuery = true)
    Page<AdminCustomerListProjection> searchCustomers(
            @Param("userId") Long userId,
            @Param("username") String username,
            @Param("name") String name,
            @Param("enabled") Boolean enabled,
            Pageable pageable);
}
