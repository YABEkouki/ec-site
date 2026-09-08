package com.example.ecsite.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.OrderNote;
import com.example.ecsite.repository.OrderNoteRepository;
import com.example.ecsite.repository.OrderRepository;

@Service
public class OrderNoteService {

    private final OrderNoteRepository orderNoteRepository;
    private final OrderRepository orderRepository;

    public OrderNoteService(
            OrderNoteRepository orderNoteRepository,
            OrderRepository orderRepository) {

        this.orderNoteRepository = orderNoteRepository;
        this.orderRepository = orderRepository;
    }

    @Transactional
    public void addNote(
            Long orderId,
            String note,
            Long adminAccountId,
            String adminUsername) {

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "注文が見つかりません。"));

        String normalizedNote = note.strip();

        OrderNote orderNote = OrderNote.create(
                order,
                normalizedNote,
                adminAccountId,
                adminUsername);

        orderNoteRepository.save(orderNote);
    }

    @Transactional(readOnly = true)
    public List<OrderNote> findByOrderId(Long orderId) {

        return orderNoteRepository
                .findByOrderIdOrderByCreatedAtDescIdDesc(orderId);
    }
}
