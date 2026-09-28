package com.example.ecsite.service;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.OrderNote;
import com.example.ecsite.repository.OrderNoteRepository;
import com.example.ecsite.repository.OrderRepository;

@ExtendWith(MockitoExtension.class)
class OrderNoteServiceTest {

    @Mock
    private OrderNoteRepository orderNoteRepository;

    @Mock
    private OrderRepository orderRepository;

    private OrderNoteService orderNoteService;

    @BeforeEach
    void setUp() {
        orderNoteService = new OrderNoteService(
                orderNoteRepository,
                orderRepository);
    }

    @Test
    void addNoteSavesOrderNote() {

        Order order = new Order(
                10L,
                1000,
                LocalDateTime.of(2026, 9, 28, 10, 0),
                LocalDateTime.of(2026, 9, 28, 14, 0));

        when(orderRepository.findById(order.getId()))
                .thenReturn(java.util.Optional.of(order));

        orderNoteService.addNote(
                order.getId(),
                "配送前に住所確認",
                20L,
                "admin");

        verify(orderNoteRepository).save(argThat(
                note -> note.getOrder() == order
                        && "配送前に住所確認".equals(note.getNote())
                        && Long.valueOf(20L).equals(note.getCreatedByAccountId())
                        && "admin".equals(note.getCreatedByUsername())));
    }

    @Test
    void addNoteSavesTrimmedNote() {

        Order order = new Order(
                10L,
                1000,
                LocalDateTime.of(2026, 9, 28, 10, 0),
                LocalDateTime.of(2026, 9, 28, 14, 0));

        when(orderRepository.findById(order.getId()))
                .thenReturn(java.util.Optional.of(order));

        orderNoteService.addNote(
                order.getId(),
                "  配送前に住所確認  ",
                20L,
                "admin");

        verify(orderNoteRepository).save(argThat(
                note -> "配送前に住所確認".equals(note.getNote())));
    }

    @Test
    void findByOrderIdReturnsNotesInRepositoryOrder() {

        Long orderId = 10L;

        OrderNote first = mock(OrderNote.class);
        OrderNote second = mock(OrderNote.class);

        List<OrderNote> expected = List.of(first, second);

        when(orderNoteRepository
                .findByOrderIdOrderByCreatedAtDescIdDesc(orderId))
                .thenReturn(expected);

        List<OrderNote> actual = orderNoteService.findByOrderId(orderId);

        assertSame(expected, actual);

        verify(orderNoteRepository)
                .findByOrderIdOrderByCreatedAtDescIdDesc(orderId);
    }
}
