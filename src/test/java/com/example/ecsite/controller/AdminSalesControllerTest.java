package com.example.ecsite.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.ui.Model;

import com.example.ecsite.dto.DailySalesSummary;
import com.example.ecsite.dto.ProductSalesRanking;
import com.example.ecsite.dto.SalesDashboardSummary;
import com.example.ecsite.dto.SalesMetricComparison;
import com.example.ecsite.dto.SalesSummary;
import com.example.ecsite.form.SalesDashboardForm;
import com.example.ecsite.service.SalesDashboardService;

class AdminSalesControllerTest {

    @Mock
    private SalesDashboardService salesDashboardService;

    @Mock
    private Model model;

    private AdminSalesController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        controller = new AdminSalesController(salesDashboardService);
    }

    @Test
    void indexInitializesPeriodAndAddsDashboardDataToModel() {

        SalesDashboardForm form = new SalesDashboardForm();

        SalesSummary summary = new SalesSummary(
                10,
                7,
                25_000,
                2,
                3,
                4,
                1);

        SalesDashboardSummary dashboard = new SalesDashboardSummary(
                summary,
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 31),
                null,
                null,
                null);

        List<DailySalesSummary> dailySales = List.of(
                new DailySalesSummary(
                        LocalDate.of(2026, 8, 10),
                        3,
                        2,
                        5_000));

        when(salesDashboardService.getDashboardSummary(form))
                .thenReturn(dashboard);

        when(salesDashboardService.getDailySales(form))
                .thenReturn(dailySales);

        String view = controller.index(
                form,
                false,
                model);

        assertEquals(
                "admin/sales/index",
                view);

        verify(salesDashboardService)
                .initializePeriod(form);

        verify(model).addAttribute(
                "comparison",
                dashboard);

        verify(salesDashboardService)
                .getDailySales(form);

        verify(model)
                .addAttribute(
                        "summary",
                        summary);

        verify(model)
                .addAttribute(
                        "dailySales",
                        dailySales);
    }

    @Test
    void indexAddsProductSalesRankingToModel() {

        SalesDashboardForm form = new SalesDashboardForm();

        SalesSummary summary = new SalesSummary(
                10,
                7,
                25_000,
                2,
                3,
                4,
                1);

        SalesDashboardSummary dashboard = new SalesDashboardSummary(
                summary,
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 31),
                null,
                null,
                null);

        List<ProductSalesRanking> productSalesRanking = List.of(
                new ProductSalesRanking(
                        10L,
                        "商品A",
                        8,
                        3,
                        12_000),
                new ProductSalesRanking(
                        20L,
                        "商品B",
                        5,
                        2,
                        8_000));

        when(salesDashboardService.getDashboardSummary(form))
                .thenReturn(dashboard);

        when(salesDashboardService.getDailySales(form))
                .thenReturn(List.of());

        when(salesDashboardService.getProductSalesRanking(form))
                .thenReturn(productSalesRanking);

        String view = controller.index(
                form,
                true,
                model);

        assertEquals(
                "admin/sales/index",
                view);

        verify(salesDashboardService)
                .getProductSalesRanking(form);

        verify(model)
                .addAttribute(
                        "productSalesRanking",
                        productSalesRanking);
    }

    @Test
    void indexDoesNotInitializePeriodWhenSearchIsRequested() {

        SalesDashboardForm form = new SalesDashboardForm();

        form.setFrom(
                LocalDate.of(2026, 8, 1));

        form.setTo(
                LocalDate.of(2026, 8, 31));

        SalesSummary summary = new SalesSummary(
                0,
                0,
                0,
                0,
                0,
                0,
                0);

        SalesDashboardSummary dashboard = new SalesDashboardSummary(
                summary,
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 31),
                null,
                null,
                null);

        when(salesDashboardService.getDashboardSummary(form))
                .thenReturn(dashboard);

        when(salesDashboardService.getDailySales(form))
                .thenReturn(List.of());

        String view = controller.index(
                form,
                true,
                model);

        assertEquals(
                "admin/sales/index",
                view);

        verify(salesDashboardService,
                org.mockito.Mockito.never())
                .initializePeriod(form);

        verify(salesDashboardService)
                .getDashboardSummary(form);

        verify(model).addAttribute(
                "comparison",
                dashboard);

        verify(salesDashboardService)
                .getDailySales(form);
    }

    @Test
    void indexShowsErrorWhenPeriodIsInvalid() {

        SalesDashboardForm form = new SalesDashboardForm();

        form.setFrom(
                LocalDate.of(2026, 8, 31));

        form.setTo(
                LocalDate.of(2026, 8, 1));

        when(salesDashboardService.getDashboardSummary(form))
                .thenThrow(new IllegalArgumentException(
                        "開始日は終了日以前を指定してください。"));

        String view = controller.index(
                form,
                true,
                model);

        assertEquals(
                "admin/sales/index",
                view);

        verify(model)
                .addAttribute(
                        "errorMessage",
                        "開始日は終了日以前を指定してください。");

        verify(salesDashboardService)
                .getDashboardSummary(form);

        verify(salesDashboardService, never())
                .getDailySales(form);
    }

    @Test
    void indexAddsSalesComparisonToModel() {
        SalesDashboardForm form = new SalesDashboardForm();

        SalesSummary current = new SalesSummary(
                12,
                8,
                30_000,
                2,
                3,
                5,
                2);

        SalesDashboardSummary dashboard = new SalesDashboardSummary(
                current,
                LocalDate.of(2026, 7, 30),
                LocalDate.of(2026, 8, 9),
                new SalesMetricComparison(
                        12,
                        7,
                        5,
                        new BigDecimal("71.4")),
                new SalesMetricComparison(
                        8,
                        5,
                        3,
                        new BigDecimal("60.0")),
                new SalesMetricComparison(
                        30000,
                        20000,
                        10000,
                        new BigDecimal("50.0")));

        when(salesDashboardService.getDashboardSummary(form))
                .thenReturn(dashboard);

        when(salesDashboardService.getDailySales(form))
                .thenReturn(List.of());

        String view = controller.index(
                form,
                true,
                model);

        assertEquals(
                "admin/sales/index",
                view);

        verify(model).addAttribute(
                "summary",
                current);

        verify(model).addAttribute(
                "comparison",
                dashboard);
    }

}
