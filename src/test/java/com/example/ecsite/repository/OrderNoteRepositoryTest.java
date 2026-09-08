package com.example.ecsite.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.OrderNote;
import com.example.ecsite.entity.User;

import jakarta.persistence.EntityManager;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OrderNoteRepositoryTest {

    @Autowired
    private OrderNoteRepository orderNoteRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void findByOrderIdReturnsNotesNewestFirst() {

        User user = createUser("order-note-test-user");

        Order order = orderRepository.save(
                new Order(user.getId(), 1000));

        OrderNote first = OrderNote.create(
                order,
                "最初のメモ",
                user.getId(),
                "admin1");

        orderNoteRepository.save(first);
        entityManager.flush();

        OrderNote second = OrderNote.create(
                order,
                "次のメモ",
                user.getId(),
                "admin2");

        orderNoteRepository.save(second);
        entityManager.flush();

        List<OrderNote> notes = orderNoteRepository
                .findByOrderIdOrderByCreatedAtDescIdDesc(order.getId());

        assertEquals(2, notes.size());

        assertEquals("次のメモ", notes.get(0).getNote());
        assertEquals("admin2", notes.get(0).getCreatedByUsername());

        assertEquals("最初のメモ", notes.get(1).getNote());
        assertEquals("admin1", notes.get(1).getCreatedByUsername());
    }

    @Test
    void findByOrderIdDoesNotReturnNotesForOtherOrders() {

        User user = createUser("order-note-other-order-user");

        Order targetOrder = orderRepository.save(
                new Order(user.getId(), 1000));

        Order otherOrder = orderRepository.save(
                new Order(user.getId(), 2000));

        orderNoteRepository.save(OrderNote.create(
                targetOrder,
                "対象注文のメモ",
                user.getId(),
                "admin"));

        orderNoteRepository.save(OrderNote.create(
                otherOrder,
                "別注文のメモ",
                user.getId(),
                "admin"));

        entityManager.flush();

        List<OrderNote> notes = orderNoteRepository
                .findByOrderIdOrderByCreatedAtDescIdDesc(targetOrder.getId());

        assertEquals(1, notes.size());
        assertEquals("対象注文のメモ", notes.get(0).getNote());
        assertEquals(targetOrder.getId(), notes.get(0).getOrder().getId());
    }

    private User createUser(String username) {

        User user = new User();
        user.setUsername(username);
        user.setPassword("password");
        user.setRole("ROLE_ADMIN");
        user.setEnabled(true);

        return userRepository.save(user);
    }
}
