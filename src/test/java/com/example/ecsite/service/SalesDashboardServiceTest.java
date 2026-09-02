package com.example.ecsite.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
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
import org.springframework.data.domain.PageRequest;

import com.example.ecsite.dto.CategorySalesRanking;
import com.example.ecsite.dto.CustomerSalesRanking;
import com.example.ecsite.dto.DailySalesSummary;
import com.example.ecsite.dto.ProductSalesRanking;
import com.example.ecsite.dto.SalesDashboardSummary;
import com.example.ecsite.dto.SalesSummary;
import com.example.ecsite.entity.OrderStatus;
import com.example.ecsite.form.SalesDashboardForm;
import com.example.ecsite.repository.OrderRepository;
import com.example.ecsite.repository.projection.CategorySalesRankingProjection;
import com.example.ecsite.repository.projection.CustomerSalesRankingProjection;
import com.example.ecsite.repository.projection.DailySalesProjection;
import com.example.ecsite.repository.projection.ProductSalesRankingProjection;

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
    void getProductSalesRankingConvertsProjectionToDto() {

        SalesDashboardForm form = new SalesDashboardForm();
        form.setFrom(LocalDate.of(2026, 8, 1));
        form.setTo(LocalDate.of(2026, 8, 31));

        LocalDateTime from = LocalDateTime.of(2026, 8, 1, 0, 0);

        LocalDateTime toExclusive = LocalDateTime.of(2026, 9, 1, 0, 0);

        ProductSalesRankingProjection first = mock(ProductSalesRankingProjection.class);

        when(first.getProductId()).thenReturn(10L);
        when(first.getProductName()).thenReturn("商品A");
        when(first.getQuantity()).thenReturn(8L);
        when(first.getOrderCount()).thenReturn(3L);
        when(first.getSalesAmount()).thenReturn(12000L);

        ProductSalesRankingProjection second = mock(ProductSalesRankingProjection.class);

        when(second.getProductId()).thenReturn(20L);
        when(second.getProductName()).thenReturn("商品B");
        when(second.getQuantity()).thenReturn(5L);
        when(second.getOrderCount()).thenReturn(2L);
        when(second.getSalesAmount()).thenReturn(8000L);

        when(orderRepository.findProductSalesRanking(
                from,
                toExclusive,
                PageRequest.of(0, 10)))
                .thenReturn(List.of(first, second));

        List<ProductSalesRanking> result = salesDashboardService.getProductSalesRanking(form);

        assertEquals(2, result.size());

        assertEquals(
                new ProductSalesRanking(
                        10L,
                        "商品A",
                        8,
                        3,
                        12000),
                result.get(0));

        assertEquals(
                new ProductSalesRanking(
                        20L,
                        "商品B",
                        5,
                        2,
                        8000),
                result.get(1));

        verify(orderRepository).findProductSalesRanking(
                from,
                toExclusive,
                PageRequest.of(0, 10));
    }

    @Test
    void getCustomerSalesRankingConvertsProjectionToDto() {

        SalesDashboardForm form = new SalesDashboardForm();
        form.setFrom(LocalDate.of(2026, 8, 1));
        form.setTo(LocalDate.of(2026, 8, 31));

        LocalDateTime from = LocalDateTime.of(2026, 8, 1, 0, 0);

        LocalDateTime toExclusive = LocalDateTime.of(2026, 9, 1, 0, 0);

        CustomerSalesRankingProjection first = mock(CustomerSalesRankingProjection.class);

        when(first.getUserId()).thenReturn(10L);
        when(first.getUsername()).thenReturn("user-a");
        when(first.getOrderCount()).thenReturn(3L);
        when(first.getQuantity()).thenReturn(8L);
        when(first.getSalesAmount()).thenReturn(12000L);

        CustomerSalesRankingProjection second = mock(CustomerSalesRankingProjection.class);

        when(second.getUserId()).thenReturn(20L);
        when(second.getUsername()).thenReturn("user-b");
        when(second.getOrderCount()).thenReturn(2L);
        when(second.getQuantity()).thenReturn(5L);
        when(second.getSalesAmount()).thenReturn(8000L);

        when(orderRepository.findCustomerSalesRanking(
                from,
                toExclusive,
                PageRequest.of(0, 10)))
                .thenReturn(List.of(first, second));

        List<CustomerSalesRanking> result = salesDashboardService
                .getCustomerSalesRanking(form);

        assertEquals(2, result.size());

        CustomerSalesRanking firstRanking = result.get(0);

        assertEquals(10L, firstRanking.userId());
        assertEquals("user-a", firstRanking.username());
        assertEquals(3L, firstRanking.orderCount());
        assertEquals(8L, firstRanking.quantity());
        assertEquals(12000L, firstRanking.salesAmount());

        CustomerSalesRanking secondRanking = result.get(1);

        assertEquals(20L, secondRanking.userId());
        assertEquals("user-b", secondRanking.username());
        assertEquals(2L, secondRanking.orderCount());
        assertEquals(5L, secondRanking.quantity());
        assertEquals(8000L, secondRanking.salesAmount());

        verify(orderRepository)
                .findCustomerSalesRanking(
                        from,
                        toExclusive,
                        PageRequest.of(0, 10));
    }

    @Test
    void getCategorySalesRankingConvertsProjectionToDto() {

        SalesDashboardForm form = new SalesDashboardForm();
        form.setFrom(LocalDate.of(2026, 8, 1));
        form.setTo(LocalDate.of(2026, 8, 31));

        LocalDateTime from = LocalDateTime.of(
                2026, 8, 1, 0, 0);

        LocalDateTime toExclusive = LocalDateTime.of(
                2026, 9, 1, 0, 0);

        CategorySalesRankingProjection first = mock(CategorySalesRankingProjection.class);

        when(first.getCategoryId()).thenReturn(10L);
        when(first.getCategoryName()).thenReturn("食品");
        when(first.getQuantity()).thenReturn(8L);
        when(first.getOrderCount()).thenReturn(3L);
        when(first.getSalesAmount()).thenReturn(12000L);

        CategorySalesRankingProjection second = mock(CategorySalesRankingProjection.class);

        when(second.getCategoryId()).thenReturn(20L);
        when(second.getCategoryName()).thenReturn("日用品");
        when(second.getQuantity()).thenReturn(5L);
        when(second.getOrderCount()).thenReturn(2L);
        when(second.getSalesAmount()).thenReturn(8000L);

        when(orderRepository.findCategorySalesRanking(
                from,
                toExclusive,
                PageRequest.of(0, 10)))
                .thenReturn(List.of(first, second));

        List<CategorySalesRanking> result = salesDashboardService.getCategorySalesRanking(form);

        assertEquals(2, result.size());

        assertEquals(
                new CategorySalesRanking(
                        10L,
                        "食品",
                        8,
                        3,
                        12000),
                result.get(0));

        assertEquals(
                new CategorySalesRanking(
                        20L,
                        "日用品",
                        5,
                        2,
                        8000),
                result.get(1));

        verify(orderRepository).findCategorySalesRanking(
                from,
                toExclusive,
                PageRequest.of(0, 10));
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

    @Test
    void getDashboardSummaryUsesImmediatelyPreviousSameLengthPeriod() {
        SalesDashboardForm form = new SalesDashboardForm();
        form.setFrom(LocalDate.of(2026, 8, 10));
        form.setTo(LocalDate.of(2026, 8, 20));

        SalesDashboardSummary dashboard = salesDashboardService.getDashboardSummary(form);

        assertEquals(
                LocalDate.of(2026, 7, 30),
                dashboard.comparisonFrom());

        assertEquals(
                LocalDate.of(2026, 8, 9),
                dashboard.comparisonTo());
    }

    @Test
    void getDashboardSummaryReturnsComparisonValues() {
        SalesDashboardForm form = new SalesDashboardForm();
        form.setFrom(LocalDate.of(2026, 8, 10));
        form.setTo(LocalDate.of(2026, 8, 20));

        LocalDateTime currentFrom = LocalDateTime.of(2026, 8, 10, 0, 0);
        LocalDateTime currentToExclusive = LocalDateTime.of(2026, 8, 21, 0, 0);

        LocalDateTime previousFrom = LocalDateTime.of(2026, 7, 30, 0, 0);
        LocalDateTime previousToExclusive = LocalDateTime.of(2026, 8, 10, 0, 0);

        when(orderRepository.countSalesOrders(
                currentFrom,
                currentToExclusive))
                .thenReturn(8L);

        when(orderRepository.sumSalesAmount(
                currentFrom,
                currentToExclusive))
                .thenReturn(30000L);

        when(orderRepository.countByStatusAndOrderedAtRange(
                OrderStatus.ORDERED,
                currentFrom,
                currentToExclusive))
                .thenReturn(2L);

        when(orderRepository.countByStatusAndOrderedAtRange(
                OrderStatus.PAID,
                currentFrom,
                currentToExclusive))
                .thenReturn(3L);

        when(orderRepository.countByStatusAndOrderedAtRange(
                OrderStatus.SHIPPED,
                currentFrom,
                currentToExclusive))
                .thenReturn(5L);

        when(orderRepository.countByStatusAndOrderedAtRange(
                OrderStatus.CANCELLED,
                currentFrom,
                currentToExclusive))
                .thenReturn(2L);

        when(orderRepository.countSalesOrders(
                previousFrom,
                previousToExclusive))
                .thenReturn(5L);

        when(orderRepository.sumSalesAmount(
                previousFrom,
                previousToExclusive))
                .thenReturn(20000L);

        when(orderRepository.countByStatusAndOrderedAtRange(
                OrderStatus.ORDERED,
                previousFrom,
                previousToExclusive))
                .thenReturn(1L);

        when(orderRepository.countByStatusAndOrderedAtRange(
                OrderStatus.PAID,
                previousFrom,
                previousToExclusive))
                .thenReturn(2L);

        when(orderRepository.countByStatusAndOrderedAtRange(
                OrderStatus.SHIPPED,
                previousFrom,
                previousToExclusive))
                .thenReturn(3L);

        when(orderRepository.countByStatusAndOrderedAtRange(
                OrderStatus.CANCELLED,
                previousFrom,
                previousToExclusive))
                .thenReturn(1L);

        SalesDashboardSummary dashboard = salesDashboardService.getDashboardSummary(form);

        assertEquals(12L,
                dashboard.totalOrderCount().currentValue());
        assertEquals(7L,
                dashboard.totalOrderCount().previousValue());
        assertEquals(5L,
                dashboard.totalOrderCount().difference());

        assertEquals(8L,
                dashboard.salesOrderCount().currentValue());
        assertEquals(5L,
                dashboard.salesOrderCount().previousValue());
        assertEquals(3L,
                dashboard.salesOrderCount().difference());

        assertEquals(30000L,
                dashboard.salesAmount().currentValue());
        assertEquals(20000L,
                dashboard.salesAmount().previousValue());
        assertEquals(10000L,
                dashboard.salesAmount().difference());
    }

    @Test
    void getDashboardSummaryCalculatesChangeRate() {
        SalesDashboardForm form = new SalesDashboardForm();
        form.setFrom(LocalDate.of(2026, 8, 10));
        form.setTo(LocalDate.of(2026, 8, 20));

        LocalDateTime currentFrom = LocalDateTime.of(2026, 8, 10, 0, 0);
        LocalDateTime currentToExclusive = LocalDateTime.of(2026, 8, 21, 0, 0);

        LocalDateTime previousFrom = LocalDateTime.of(2026, 7, 30, 0, 0);
        LocalDateTime previousToExclusive = LocalDateTime.of(2026, 8, 10, 0, 0);

        when(orderRepository.countSalesOrders(
                currentFrom,
                currentToExclusive))
                .thenReturn(8L);

        when(orderRepository.sumSalesAmount(
                currentFrom,
                currentToExclusive))
                .thenReturn(30000L);

        when(orderRepository.countSalesOrders(
                previousFrom,
                previousToExclusive))
                .thenReturn(5L);

        when(orderRepository.sumSalesAmount(
                previousFrom,
                previousToExclusive))
                .thenReturn(20000L);

        when(orderRepository.countByStatusAndOrderedAtRange(
                OrderStatus.ORDERED,
                currentFrom,
                currentToExclusive))
                .thenReturn(2L);
        when(orderRepository.countByStatusAndOrderedAtRange(
                OrderStatus.PAID,
                currentFrom,
                currentToExclusive))
                .thenReturn(3L);
        when(orderRepository.countByStatusAndOrderedAtRange(
                OrderStatus.SHIPPED,
                currentFrom,
                currentToExclusive))
                .thenReturn(5L);
        when(orderRepository.countByStatusAndOrderedAtRange(
                OrderStatus.CANCELLED,
                currentFrom,
                currentToExclusive))
                .thenReturn(2L);

        when(orderRepository.countByStatusAndOrderedAtRange(
                OrderStatus.ORDERED,
                previousFrom,
                previousToExclusive))
                .thenReturn(1L);
        when(orderRepository.countByStatusAndOrderedAtRange(
                OrderStatus.PAID,
                previousFrom,
                previousToExclusive))
                .thenReturn(2L);
        when(orderRepository.countByStatusAndOrderedAtRange(
                OrderStatus.SHIPPED,
                previousFrom,
                previousToExclusive))
                .thenReturn(3L);
        when(orderRepository.countByStatusAndOrderedAtRange(
                OrderStatus.CANCELLED,
                previousFrom,
                previousToExclusive))
                .thenReturn(1L);

        SalesDashboardSummary dashboard = salesDashboardService.getDashboardSummary(form);

        assertEquals(
                new BigDecimal("71.4"),
                dashboard.totalOrderCount().changeRate());

        assertEquals(
                new BigDecimal("60.0"),
                dashboard.salesOrderCount().changeRate());

        assertEquals(
                new BigDecimal("50.0"),
                dashboard.salesAmount().changeRate());
    }

    @Test
    void getDashboardSummaryReturnsNullChangeRateWhenPreviousValueIsZero() {
        SalesDashboardForm form = new SalesDashboardForm();
        form.setFrom(LocalDate.of(2026, 8, 10));
        form.setTo(LocalDate.of(2026, 8, 10));

        LocalDateTime currentFrom = LocalDateTime.of(2026, 8, 10, 0, 0);
        LocalDateTime currentToExclusive = LocalDateTime.of(2026, 8, 11, 0, 0);

        LocalDateTime previousFrom = LocalDateTime.of(2026, 8, 9, 0, 0);
        LocalDateTime previousToExclusive = LocalDateTime.of(2026, 8, 10, 0, 0);

        when(orderRepository.countSalesOrders(
                currentFrom,
                currentToExclusive))
                .thenReturn(2L);

        when(orderRepository.sumSalesAmount(
                currentFrom,
                currentToExclusive))
                .thenReturn(5000L);

        when(orderRepository.countByStatusAndOrderedAtRange(
                OrderStatus.ORDERED,
                currentFrom,
                currentToExclusive))
                .thenReturn(1L);

        when(orderRepository.countByStatusAndOrderedAtRange(
                OrderStatus.PAID,
                currentFrom,
                currentToExclusive))
                .thenReturn(1L);

        when(orderRepository.countByStatusAndOrderedAtRange(
                OrderStatus.SHIPPED,
                currentFrom,
                currentToExclusive))
                .thenReturn(1L);

        when(orderRepository.countByStatusAndOrderedAtRange(
                OrderStatus.CANCELLED,
                currentFrom,
                currentToExclusive))
                .thenReturn(0L);

        when(orderRepository.countSalesOrders(
                previousFrom,
                previousToExclusive))
                .thenReturn(0L);

        when(orderRepository.sumSalesAmount(
                previousFrom,
                previousToExclusive))
                .thenReturn(0L);

        for (OrderStatus status : OrderStatus.values()) {
            when(orderRepository.countByStatusAndOrderedAtRange(
                    status,
                    previousFrom,
                    previousToExclusive))
                    .thenReturn(0L);
        }

        SalesDashboardSummary dashboard = salesDashboardService.getDashboardSummary(form);

        assertEquals(
                3L,
                dashboard.totalOrderCount().difference());

        assertEquals(
                2L,
                dashboard.salesOrderCount().difference());

        assertEquals(
                5000L,
                dashboard.salesAmount().difference());

        assertEquals(
                null,
                dashboard.totalOrderCount().changeRate());

        assertEquals(
                null,
                dashboard.salesOrderCount().changeRate());

        assertEquals(
                null,
                dashboard.salesAmount().changeRate());
    }

}
