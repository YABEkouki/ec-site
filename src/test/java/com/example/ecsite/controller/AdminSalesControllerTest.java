package com.example.ecsite.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.ui.Model;

import com.example.ecsite.dto.DailySalesSummary;
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

        List<DailySalesSummary> dailySales = List.of(
                new DailySalesSummary(
                        LocalDate.of(2026, 8, 10),
                        3,
                        2,
                        5_000));

        when(salesDashboardService.getSummary(form))
                .thenReturn(summary);

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

        verify(salesDashboardService)
                .getSummary(form);

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

        when(salesDashboardService.getSummary(form))
                .thenReturn(summary);

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
                .getSummary(form);

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

        when(salesDashboardService.getSummary(form))
                .thenThrow(
                        new IllegalArgumentException(
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
                .getSummary(form);

        verify(salesDashboardService, never())
                .getDailySales(form);
    }

}