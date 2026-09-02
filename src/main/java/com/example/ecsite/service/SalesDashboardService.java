package com.example.ecsite.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.example.ecsite.dto.CategorySalesRanking;
import com.example.ecsite.dto.CustomerSalesRanking;
import com.example.ecsite.dto.DailySalesSummary;
import com.example.ecsite.dto.ProductSalesRanking;
import com.example.ecsite.dto.SalesDashboardSummary;
import com.example.ecsite.dto.SalesMetricComparison;
import com.example.ecsite.dto.SalesSummary;
import com.example.ecsite.entity.OrderStatus;
import com.example.ecsite.form.SalesDashboardForm;
import com.example.ecsite.repository.OrderRepository;

@Service
public class SalesDashboardService {

    private final OrderRepository orderRepository;
    private final Clock clock;

    @Autowired
    public SalesDashboardService(
            OrderRepository orderRepository) {

        this(
                orderRepository,
                Clock.system(ZoneId.of("Asia/Tokyo")));
    }

    SalesDashboardService(
            OrderRepository orderRepository,
            Clock clock) {

        this.orderRepository = orderRepository;
        this.clock = clock;
    }

    public void initializePeriod(
            SalesDashboardForm form) {

        LocalDate today = LocalDate.now(clock);

        form.setFrom(
                today.withDayOfMonth(1));

        form.setTo(today);
    }

    public SalesSummary getSummary(
            SalesDashboardForm form) {

        DateTimeRange range = resolveDateTimeRange(form);

        return getSummary(
                range.from(),
                range.toExclusive());
    }

    public List<DailySalesSummary> getDailySales(
            SalesDashboardForm form) {

        DateTimeRange range = resolveDateTimeRange(form);

        return getDailySales(
                range.from(),
                range.toExclusive());
    }

    public List<ProductSalesRanking> getProductSalesRanking(
            SalesDashboardForm form) {

        DateTimeRange range = resolveDateTimeRange(form);

        return orderRepository.findProductSalesRanking(
                range.from(),
                range.toExclusive(),
                PageRequest.of(0, 10))
                .stream()
                .map(projection -> new ProductSalesRanking(
                        projection.getProductId(),
                        projection.getProductName(),
                        projection.getQuantity(),
                        projection.getOrderCount(),
                        projection.getSalesAmount()))
                .toList();
    }

    public List<CustomerSalesRanking> getCustomerSalesRanking(
            SalesDashboardForm form) {

        DateTimeRange range = resolveDateTimeRange(form);

        return orderRepository.findCustomerSalesRanking(
                range.from(),
                range.toExclusive(),
                PageRequest.of(0, 10))
                .stream()
                .map(projection -> new CustomerSalesRanking(
                        projection.getUserId(),
                        projection.getUsername(),
                        projection.getOrderCount(),
                        projection.getQuantity(),
                        projection.getSalesAmount()))
                .toList();
    }

    public List<CategorySalesRanking> getCategorySalesRanking(
            SalesDashboardForm form) {

        DateTimeRange range = resolveDateTimeRange(form);

        return orderRepository.findCategorySalesRanking(
                range.from(),
                range.toExclusive(),
                PageRequest.of(0, 10))
                .stream()
                .map(projection -> new CategorySalesRanking(
                        projection.getCategoryId(),
                        projection.getCategoryName(),
                        projection.getQuantity(),
                        projection.getOrderCount(),
                        projection.getSalesAmount()))
                .toList();
    }

    private SalesSummary getSummary(
            LocalDateTime from,
            LocalDateTime toExclusive) {

        long salesOrderCount = orderRepository.countSalesOrders(
                from,
                toExclusive);

        long salesAmount = orderRepository.sumSalesAmount(
                from,
                toExclusive);

        long orderedCount = orderRepository.countByStatusAndOrderedAtRange(
                OrderStatus.ORDERED,
                from,
                toExclusive);

        long paidCount = orderRepository.countByStatusAndOrderedAtRange(
                OrderStatus.PAID,
                from,
                toExclusive);

        long shippedCount = orderRepository.countByStatusAndOrderedAtRange(
                OrderStatus.SHIPPED,
                from,
                toExclusive);

        long cancelledCount = orderRepository.countByStatusAndOrderedAtRange(
                OrderStatus.CANCELLED,
                from,
                toExclusive);

        long totalOrderCount = orderedCount
                + paidCount
                + shippedCount
                + cancelledCount;

        return new SalesSummary(
                totalOrderCount,
                salesOrderCount,
                salesAmount,
                orderedCount,
                paidCount,
                shippedCount,
                cancelledCount);
    }

    public SalesDashboardSummary getDashboardSummary(
            SalesDashboardForm form) {

        DateTimeRange currentRange = resolveDateTimeRange(form);

        long days = ChronoUnit.DAYS.between(
                form.getFrom(),
                form.getTo()) + 1;

        LocalDate comparisonTo = form.getFrom().minusDays(1);

        LocalDate comparisonFrom = comparisonTo.minusDays(days - 1);

        DateTimeRange comparisonRange = new DateTimeRange(
                comparisonFrom.atStartOfDay(),
                comparisonTo.plusDays(1).atStartOfDay());

        SalesSummary current = getSummary(
                currentRange.from(),
                currentRange.toExclusive());

        SalesSummary previous = getSummary(
                comparisonRange.from(),
                comparisonRange.toExclusive());

        return new SalesDashboardSummary(
                current,
                comparisonFrom,
                comparisonTo,
                createComparison(
                        current.totalOrderCount(),
                        previous.totalOrderCount()),
                createComparison(
                        current.salesOrderCount(),
                        previous.salesOrderCount()),
                createComparison(
                        current.salesAmount(),
                        previous.salesAmount()));
    }

    private List<DailySalesSummary> getDailySales(
            LocalDateTime from,
            LocalDateTime toExclusive) {

        return orderRepository.findDailySales(
                from,
                toExclusive)
                .stream()
                .map(projection -> new DailySalesSummary(
                        projection.getDate(),
                        projection.getOrderCount(),
                        projection.getSalesOrderCount(),
                        projection.getSalesAmount()))
                .toList();
    }

    private DateTimeRange resolveDateTimeRange(
            SalesDashboardForm form) {

        if (form.getFrom().isAfter(form.getTo())) {
            throw new IllegalArgumentException(
                    "開始日は終了日以前を指定してください。");
        }

        LocalDateTime from = form.getFrom().atStartOfDay();

        LocalDateTime toExclusive = form.getTo().plusDays(1).atStartOfDay();

        return new DateTimeRange(
                from,
                toExclusive);
    }

    private record DateTimeRange(
            LocalDateTime from,
            LocalDateTime toExclusive) {
    }

    private SalesMetricComparison createComparison(
            long currentValue,
            long previousValue) {

        BigDecimal changeRate = null;

        if (previousValue != 0) {
            changeRate = BigDecimal.valueOf(
                    currentValue - previousValue)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(
                            BigDecimal.valueOf(previousValue),
                            1,
                            RoundingMode.HALF_UP);
        }

        return new SalesMetricComparison(
                currentValue,
                previousValue,
                currentValue - previousValue,
                changeRate);
    }

}
