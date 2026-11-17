package com.example.ecsite.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Controller;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.OrderStatus;
import com.example.ecsite.service.*;
import com.example.ecsite.service.payment.*;
import com.example.ecsite.service.warehouse.OrderShipmentTrackingReader;
import com.example.ecsite.service.warehouse.OrderShipmentTrackingReader.Tracking;
import com.example.ecsite.service.warehouse.WarehouseShipmentImportLineState;

/** Feature132-C: the actual order detail templates show the warehouse carrier and tracking number. */
class OrderShipmentTrackingViewTest {
    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 17, 15, 30);
    private static final Tracking TRACKING = new Tracking("ヤマト運輸<b>", "1234-5678", LocalDate.of(2026, 11, 17), 3,
            WarehouseShipmentImportLineState.CAPTURE_FAILED);

    static ThymeleafViewResolver views() {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/"); resolver.setSuffix(".html");
        resolver.setTemplateMode("HTML"); resolver.setCharacterEncoding("UTF-8");
        var engine = new SpringTemplateEngine(); engine.setTemplateResolver(resolver);
        var views = new ThymeleafViewResolver(); views.setTemplateEngine(engine);
        views.setCharacterEncoding("UTF-8"); views.setContentType("text/html;charset=UTF-8");
        return views;
    }

    static Order shippedByImport() {
        var order = new Order(1L, 3000, AT.minusDays(1), AT.minusDays(1).withHour(14));
        ReflectionTestUtils.setField(order, "id", 42L);
        ReflectionTestUtils.setField(order, "status", OrderStatus.SHIPPED);
        ReflectionTestUtils.setField(order, "shippingPreparationStartedAt", AT.minusHours(20));
        ReflectionTestUtils.setField(order, "shippingPreparationStartedByUsername", Order.WAREHOUSE_EXPORT_PREPARER);
        ReflectionTestUtils.setField(order, "shippedAt", AT);
        ReflectionTestUtils.setField(order, "shippedByUsername", Order.WAREHOUSE_SHIPMENT_IMPORTER);
        return order;
    }

    @Controller static class CustomerView {
        final Order order; final Tracking tracking;
        CustomerView(Order order, Tracking tracking) { this.order = order; this.tracking = tracking; }
        @GetMapping("/test-order") String view(Model model) {
            model.addAttribute("order", order); model.addAttribute("statusHistories", List.of());
            model.addAttribute("shippingAddressHistories", List.of());
            model.addAttribute("shipmentTracking", tracking);
            model.addAttribute("canResumePayment", false); model.addAttribute("requiresPaymentConfirmation", false);
            return "orders/detail";
        }
    }

    String customerHtml(Tracking tracking) throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new CustomerView(shippedByImport(), tracking)).setViewResolvers(views()).build();
        return mvc.perform(get("/test-order")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test void theCustomerSeesCarrierTrackingNumberAndShipDateButNoInternalState() throws Exception {
        String html = customerHtml(TRACKING);
        assertThat(html).contains("配送業者", "ヤマト運輸&lt;b&gt;", "追跡番号", "1234-5678", "倉庫からの出荷日", "2026/11/17")
                .doesNotContain("ヤマト運輸<b>", WarehouseShipmentImportLineState.CAPTURE_FAILED.label(), "WAREHOUSE_SHIPMENT_IMPORT",
                        "/admin/warehouse-shipments");
        assertThat(customerHtml(null)).doesNotContain("追跡番号", "倉庫からの出荷日");
    }

    @Test void theAdministratorSeesTheSystemShipperTrackingAndTheImportOutcome() throws Exception {
        var order = shippedByImport();
        var orders = mock(OrderService.class);
        when(orders.findOrderWithItems(42L)).thenReturn(order);
        var paymentViewReader = mock(OrderPaymentViewReadService.class);
        when(paymentViewReader.read(order)).thenReturn(OrderPaymentViewFactory.unavailable());
        var controller = new AdminOrderController(orders, mock(OrderCsvService.class),
                mock(OrderStatusHistoryService.class), mock(OrderNoteService.class),
                mock(OrderHandlingStatusHistoryService.class), mock(OrderShippingAddressHistoryService.class),
                mock(OrderAssigneeHistoryService.class), mock(OrderContentChangeHistoryService.class),
                mock(AdminAccountService.class), mock(PaymentService.class), mock(PaymentCaptureService.class),
                mock(AdminPaymentCancellationService.class),
                new OrderShippingPreparationViewFactory(new OrderModificationPolicy(), java.time.Clock.systemDefaultZone()),
                paymentViewReader, mock(com.example.ecsite.service.orderchange.AdminOldCancelRecoveryViewService.class),
                mock(com.example.ecsite.service.orderchange.AdminOrderHoldViewService.class));
        var reader = mock(OrderShipmentTrackingReader.class);
        when(reader.find(42L)).thenReturn(Optional.of(TRACKING));
        controller.setShipmentTracking(reader);
        var mvc = MockMvcBuilders.standaloneSetup(controller).setViewResolvers(views()).build();

        String html = mvc.perform(get("/admin/orders/42")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(html).contains("発送者", "倉庫出荷取込み", "配送業者・追跡番号", "ヤマト運輸&lt;b&gt;", "1234-5678",
                "/admin/warehouse-shipments/3", WarehouseShipmentImportLineState.CAPTURE_FAILED.label());
    }
}
