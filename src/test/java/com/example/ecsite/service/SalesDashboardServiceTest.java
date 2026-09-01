package com.example.ecsite.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.example.ecsite.dto.DailySalesSummary;
import com.example.ecsite.dto.SalesSummary;
import com.example.ecsite.entity.OrderStatus;
import com.example.ecsite.form.SalesDashboardForm;
import com.example.ecsite.repository.OrderRepository;
import com.example.ecsite.repository.projection.DailySalesProjection;

class SalesDashboardServiceTest {

    @Mock
    private OrderRepository orderRepository;

    private SalesDashboardService salesDashboardService;

    @BeforeEach
    void setUp() {

        MockitoAnnotations.openMocks(this);

        salesDashboardService = new SalesDashboardService(orderRepository);
    }

    @Test
    void getSummaryReturnsSalesAndStatusCounts() {

        SalesDashboardForm form = new SalesDashboardForm();
        form.setFrom(LocalDate.of(2026, 8, 1));
        form.setTo(LocalDate.of(2026, 8, 31));

        LocalDateTime from = LocalDateTime.of(2026, 8, 1, 0, 0);
        LocalDateTime toExclusive = LocalDateTime.of(2026, 9, 1, 0, 0);

        when(orderRepository.countSalesOrders(from, toExclusive))
                .thenReturn(7L);

        when(orderRepository.sumSalesAmount(from, toExclusive))
                .thenReturn(25000L);

        when(orderRepository.countByStatusAndOrderedAtRange(
                OrderStatus.ORDERED,
                from,
                toExclusive))
                .thenReturn(2L);

        when(orderRepository.countByStatusAndOrderedAtRange(
                OrderStatus.PAID,
                from,
                toExclusive))
                .thenReturn(3L);

        when(orderRepository.countByStatusAndOrderedAtRange(
                OrderStatus.SHIPPED,
                from,
                toExclusive))
                .thenReturn(4L);

        when(orderRepository.countByStatusAndOrderedAtRange(
                OrderStatus.CANCELLED,
                from,
                toExclusive))
                .thenReturn(1L);

        SalesSummary result = salesDashboardService.getSummary(form);

        assertEquals(10, result.totalOrderCount());
        assertEquals(7, result.salesOrderCount());
        assertEquals(25000, result.salesAmount());
        assertEquals(2, result.orderedCount());
        assertEquals(3, result.paidCount());
        assertEquals(4, result.shippedCount());
        assertEquals(1, result.cancelledCount());
    }

    @Test
    void getDailySalesConvertsProjectionToDto() {

        SalesDashboardForm form = new SalesDashboardForm();
        form.setFrom(LocalDate.of(2026, 8, 1));
        form.setTo(LocalDate.of(2026, 8, 31));

        LocalDateTime from = LocalDateTime.of(2026, 8, 1, 0, 0);
        LocalDateTime toExclusive = LocalDateTime.of(2026, 9, 1, 0, 0);

        DailySalesProjection first = mock(DailySalesProjection.class);

        when(first.getDate()).thenReturn(LocalDate.of(2026, 8, 10));
        when(first.getOrderCount()).thenReturn(3L);
        when(first.getSalesOrderCount()).thenReturn(2L);
        when(first.getSalesAmount()).thenReturn(5000L);

        DailySalesProjection second = mock(DailySalesProjection.class);
        when(second.getDate()).thenReturn(LocalDate.of(2026, 8, 11));
        when(second.getOrderCount()).thenReturn(4L);
        when(second.getSalesOrderCount()).thenReturn(3L);
        when(second.getSalesAmount()).thenReturn(8000L);

        when(orderRepository.findDailySales(from, toExclusive))
                .thenReturn(List.of(first, second));

        List<DailySalesSummary> result = salesDashboardService.getDailySales(form);

        assertEquals(2, result.size());

        assertEquals(
                new DailySalesSummary(
                        LocalDate.of(2026, 8, 10),
                        3,
                        2,
                        5000),
                result.get(0));

        assertEquals(
                new DailySalesSummary(
                        LocalDate.of(2026, 8, 11),
                        4,
                        3,
                        8000),
                result.get(1));
    }

    @Test
    void getSummaryConvertsFormDatesToHalfOpenDateTimeRange() {

        SalesDashboardForm form = new SalesDashboardForm();
        form.setFrom(LocalDate.of(2026, 8, 1));
        form.setTo(LocalDate.of(2026, 8, 31));

        LocalDateTime expectedFrom = LocalDateTime.of(2026, 8, 1, 0, 0);

        LocalDateTime expectedToExclusive = LocalDateTime.of(2026, 9, 1, 0, 0);

        when(orderRepository.countSalesOrders(
                expectedFrom,
                expectedToExclusive))
                .thenReturn(0L);

        when(orderRepository.sumSalesAmount(
                expectedFrom,
                expectedToExclusive))
                .thenReturn(0L);

        for (OrderStatus status : OrderStatus.values()) {

            when(orderRepository.countByStatusAndOrderedAtRange(
                    status,
                    expectedFrom,
                    expectedToExclusive))
                    .thenReturn(0L);
        }

        salesDashboardService.getSummary(form);

        verify(orderRepository).countSalesOrders(
                expectedFrom,
                expectedToExclusive);

        verify(orderRepository).sumSalesAmount(
                expectedFrom,
                expectedToExclusive);

        for (OrderStatus status : OrderStatus.values()) {

            verify(orderRepository).countByStatusAndOrderedAtRange(
                    status,
                    expectedFrom,
                    expectedToExclusive);
        }
    }

    @Test
    void initializePeriodSetsCurrentMonthStartAndToday() {

        Clock clock = Clock.fixed(
                Instant.parse("2026-09-15T03:00:00Z"),
                ZoneId.of("Asia/Tokyo"));

        salesDashboardService = new SalesDashboardService(
                orderRepository,
                clock);

        SalesDashboardForm form = new SalesDashboardForm();

        salesDashboardService.initializePeriod(form);

        assertEquals(
                LocalDate.of(2026, 9, 1),
                form.getFrom());

        assertEquals(
                LocalDate.of(2026, 9, 15),
                form.getTo());
    }

    @Test
    void getSummaryRejectsPeriodWhenFromIsAfterTo() {

        SalesDashboardForm form = new SalesDashboardForm();
        form.setFrom(LocalDate.of(2026, 8, 31));
        form.setTo(LocalDate.of(2026, 8, 1));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> salesDashboardService.getSummary(form));

        assertEquals(
                "開始日は終了日以前を指定してください。",
                exception.getMessage());
    }

    @Test
    void getDailySalesConvertsFormDatesToHalfOpenDateTimeRange() {

        SalesDashboardForm form = new SalesDashboardForm();
        form.setFrom(LocalDate.of(2026, 8, 1));
        form.setTo(LocalDate.of(2026, 8, 31));

        LocalDateTime expectedFrom = LocalDateTime.of(2026, 8, 1, 0, 0);

        LocalDateTime expectedToExclusive = LocalDateTime.of(2026, 9, 1, 0, 0);

        when(orderRepository.findDailySales(
                expectedFrom,
                expectedToExclusive))
                .thenReturn(List.of());

        salesDashboardService.getDailySales(form);

        verify(orderRepository).findDailySales(
                expectedFrom,
                expectedToExclusive);
    }

}