package com.example.ecsite.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.OrderNote;
import com.example.ecsite.entity.OrderStatus;
import com.example.ecsite.entity.OrderStatusHistory;
import com.example.ecsite.exception.InvalidOrderStatusException;
import com.example.ecsite.form.AdminOrderNoteForm;
import com.example.ecsite.form.AdminOrderSearchForm;
import com.example.ecsite.form.AdminOrderStatusChangeForm;
import com.example.ecsite.security.CustomUserDetails;
import com.example.ecsite.service.OrderCsvService;
import com.example.ecsite.service.OrderNoteService;
import com.example.ecsite.service.OrderService;
import com.example.ecsite.service.OrderStatusHistoryService;

@ExtendWith(MockitoExtension.class)
class AdminOrderControllerTest {

    @Mock
    private OrderService orderService;

    @Mock
    private Model model;

    @Mock
    private OrderCsvService orderCsvService;

    @Mock
    private OrderStatusHistoryService orderStatusHistoryService;

    @Mock
    private OrderNoteService orderNoteService;

    @Mock
    private CustomUserDetails loginUser;

    private AdminOrderController adminOrderController;

    private static final Long ADMIN_ID = 20L;
    private static final String ADMIN_USERNAME = "admin";

    @BeforeEach
    void setUp() {
        adminOrderController = new AdminOrderController(
                orderService,
                orderCsvService,
                orderStatusHistoryService,
                orderNoteService);
    }

    @Test
    void listDisplaysOrdersUsingSearchForm() {

        AdminOrderSearchForm searchForm = new AdminOrderSearchForm();

        Order order = new Order(10L, 2000);
        Page<Order> orderPage = new PageImpl<>(List.of(order));

        when(orderService.searchOrders(
                searchForm,
                0,
                10))
                .thenReturn(orderPage);

        String viewName = adminOrderController.list(
                searchForm,
                0,
                10,
                model);

        assertEquals(
                "admin/orders/list",
                viewName);

        verify(orderService).searchOrders(
                searchForm,
                0,
                10);

        verify(model).addAttribute(
                "orders",
                orderPage.getContent());

        verify(model).addAttribute(
                "orderPage",
                orderPage);

        verify(model).addAttribute(
                "statuses",
                OrderStatus.values());
    }

    @Test
    void listFiltersOrdersByStatus() {

        AdminOrderSearchForm searchForm = new AdminOrderSearchForm();
        searchForm.setStatus(OrderStatus.PAID);

        Order order = new Order(10L, 2000);
        Page<Order> orderPage = new PageImpl<>(List.of(order));

        when(orderService.searchOrders(
                searchForm,
                0,
                10))
                .thenReturn(orderPage);

        String viewName = adminOrderController.list(
                searchForm,
                0,
                10,
                model);

        assertEquals(
                "admin/orders/list",
                viewName);

        verify(orderService).searchOrders(
                searchForm,
                0,
                10);

        verify(model).addAttribute(
                "statuses",
                OrderStatus.values());
    }

    @Test
    void detailDisplaysOrderWithItemsAndNotes() {

        Long orderId = 1L;

        Order order = new Order(10L, 2000);
        OrderStatusHistory history = mock(OrderStatusHistory.class);
        OrderNote orderNote = mock(OrderNote.class);

        List<OrderStatusHistory> statusHistories = List.of(history);
        List<OrderNote> orderNotes = List.of(orderNote);

        when(orderService.findOrderWithItems(orderId))
                .thenReturn(order);

        when(orderStatusHistoryService.findByOrderId(orderId))
                .thenReturn(statusHistories);

        when(orderNoteService.findByOrderId(orderId))
                .thenReturn(orderNotes);

        String viewName = adminOrderController.detail(
                orderId,
                model);

        assertEquals(
                "admin/orders/detail",
                viewName);

        verify(orderService)
                .findOrderWithItems(orderId);

        verify(orderStatusHistoryService)
                .findByOrderId(orderId);

        verify(orderNoteService)
                .findByOrderId(orderId);

        verify(model)
                .addAttribute(
                        "order",
                        order);

        verify(model)
                .addAttribute(
                        "statusHistories",
                        statusHistories);

        verify(model)
                .addAttribute(
                        "orderNotes",
                        orderNotes);

        verify(model)
                .addAttribute(
                        org.mockito.ArgumentMatchers.eq("orderNoteForm"),
                        org.mockito.ArgumentMatchers.any(AdminOrderNoteForm.class));
    }

    @Test
    void markAsShippedChangesOrderStatusAndRedirectsToDetail() {

        when(loginUser.getId())
                .thenReturn(ADMIN_ID);

        when(loginUser.getUsername())
                .thenReturn(ADMIN_USERNAME);

        Long orderId = 1L;

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        AdminOrderStatusChangeForm form = new AdminOrderStatusChangeForm();

        BindingResult bindingResult = mock(BindingResult.class);

        String viewName = adminOrderController.markAsShipped(
                orderId,
                form,
                bindingResult,
                loginUser,
                redirectAttributes);

        assertEquals(
                "redirect:/admin/orders/" + orderId,
                viewName);

        verify(orderService)
                .markAsShipped(
                        orderId,
                        ADMIN_ID,
                        ADMIN_USERNAME,
                        null);

        verify(redirectAttributes)
                .addFlashAttribute(
                        "successMessage",
                        "注文を発送済みに変更しました。");
    }

    @Test
    void cancelCancelsOrderAndRedirectsToDetail() {

        when(loginUser.getId())
                .thenReturn(ADMIN_ID);

        when(loginUser.getUsername())
                .thenReturn(ADMIN_USERNAME);

        Long orderId = 1L;

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        AdminOrderStatusChangeForm form = new AdminOrderStatusChangeForm();

        BindingResult bindingResult = mock(BindingResult.class);

        String viewName = adminOrderController.cancel(
                orderId,
                form,
                bindingResult,
                loginUser,
                redirectAttributes);

        assertEquals(
                "redirect:/admin/orders/" + orderId,
                viewName);

        verify(orderService)
                .cancelOrder(
                        orderId,
                        ADMIN_ID,
                        ADMIN_USERNAME,
                        null);

        verify(redirectAttributes)
                .addFlashAttribute(
                        "successMessage",
                        "注文をキャンセルしました。");
    }

    @Test
    void markAsPaidPassesInternalNoteToOrderService() {

        when(loginUser.getId())
                .thenReturn(ADMIN_ID);

        when(loginUser.getUsername())
                .thenReturn(ADMIN_USERNAME);

        Long orderId = 1L;

        AdminOrderStatusChangeForm form = new AdminOrderStatusChangeForm();
        form.setInternalNote("入金を確認したため");

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        BindingResult bindingResult = mock(BindingResult.class);

        String viewName = adminOrderController.markAsPaid(
                orderId,
                form,
                bindingResult,
                loginUser,
                redirectAttributes);

        assertEquals(
                "redirect:/admin/orders/" + orderId,
                viewName);

        verify(orderService)
                .markAsPaid(
                        orderId,
                        ADMIN_ID,
                        ADMIN_USERNAME,
                        "入金を確認したため");

        verify(redirectAttributes)
                .addFlashAttribute(
                        "successMessage",
                        "注文を支払済みに変更しました。");
    }

    @Test
    void markAsPaidDisplaysErrorWhenStatusIsInvalid() {

        when(loginUser.getId())
                .thenReturn(ADMIN_ID);

        when(loginUser.getUsername())
                .thenReturn(ADMIN_USERNAME);

        Long orderId = 1L;

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        AdminOrderStatusChangeForm form = new AdminOrderStatusChangeForm();

        BindingResult bindingResult = mock(BindingResult.class);

        InvalidOrderStatusException exception = new InvalidOrderStatusException(
                "注文受付中の注文だけを支払済みに変更できます。");

        doThrow(exception)
                .when(orderService)
                .markAsPaid(
                        orderId,
                        ADMIN_ID,
                        ADMIN_USERNAME,
                        null);

        String viewName = adminOrderController.markAsPaid(
                orderId,
                form,
                bindingResult,
                loginUser,
                redirectAttributes);

        assertEquals(
                "redirect:/admin/orders/" + orderId,
                viewName);

        verify(redirectAttributes)
                .addFlashAttribute(
                        "errorMessage",
                        exception.getMessage());
    }

    @Test
    void markAsShippedDisplaysErrorWhenStatusIsInvalid() {

        when(loginUser.getId())
                .thenReturn(ADMIN_ID);

        when(loginUser.getUsername())
                .thenReturn(ADMIN_USERNAME);

        Long orderId = 1L;

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        AdminOrderStatusChangeForm form = new AdminOrderStatusChangeForm();

        BindingResult bindingResult = mock(BindingResult.class);

        InvalidOrderStatusException exception = new InvalidOrderStatusException(
                "支払済みの注文だけを発送済みに変更できます。");

        doThrow(exception)
                .when(orderService)
                .markAsShipped(
                        orderId,
                        ADMIN_ID,
                        ADMIN_USERNAME,
                        null);

        String viewName = adminOrderController.markAsShipped(
                orderId,
                form,
                bindingResult,
                loginUser,
                redirectAttributes);

        assertEquals(
                "redirect:/admin/orders/" + orderId,
                viewName);

        verify(redirectAttributes)
                .addFlashAttribute(
                        "errorMessage",
                        exception.getMessage());
    }

    @Test
    void cancelDisplaysErrorWhenStatusIsInvalid() {

        when(loginUser.getId())
                .thenReturn(ADMIN_ID);

        when(loginUser.getUsername())
                .thenReturn(ADMIN_USERNAME);

        Long orderId = 1L;

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        AdminOrderStatusChangeForm form = new AdminOrderStatusChangeForm();

        BindingResult bindingResult = mock(BindingResult.class);

        InvalidOrderStatusException exception = new InvalidOrderStatusException(
                "注文受付中の注文だけをキャンセルできます。");

        doThrow(exception)
                .when(orderService)
                .cancelOrder(
                        orderId,
                        ADMIN_ID,
                        ADMIN_USERNAME,
                        null);

        String viewName = adminOrderController.cancel(
                orderId,
                form,
                bindingResult,
                loginUser,
                redirectAttributes);

        assertEquals(
                "redirect:/admin/orders/" + orderId,
                viewName);

        verify(redirectAttributes)
                .addFlashAttribute(
                        "errorMessage",
                        exception.getMessage());
    }

    @Test
    void listSanitizesPageAndSize() {

        AdminOrderSearchForm searchForm = new AdminOrderSearchForm();

        Page<Order> orderPage = new PageImpl<>(List.of());

        when(orderService.searchOrders(
                searchForm,
                0,
                100))
                .thenReturn(orderPage);

        String viewName = adminOrderController.list(
                searchForm,
                -1,
                999,
                model);

        assertEquals(
                "admin/orders/list",
                viewName);

        verify(orderService).searchOrders(
                searchForm,
                0,
                100);
    }

    @Test
    void csvExportsAllOrdersMatchingSearchConditions() {

        AdminOrderSearchForm searchForm = new AdminOrderSearchForm();

        searchForm.setUserId(10L);
        searchForm.setStatus(OrderStatus.PAID);

        Order firstOrder = new Order(10L, 1000);
        Order secondOrder = new Order(10L, 2000);

        List<Order> orders = List.of(firstOrder, secondOrder);

        byte[] csvBytes = "csv-data".getBytes(
                StandardCharsets.UTF_8);

        when(orderService.searchAllOrders(searchForm))
                .thenReturn(orders);

        when(orderCsvService.createCsv(orders))
                .thenReturn(csvBytes);

        ResponseEntity<byte[]> response = adminOrderController.csv(searchForm);

        assertEquals(
                HttpStatus.OK,
                response.getStatusCode());

        assertEquals(
                "text/csv;charset=UTF-8",
                response.getHeaders()
                        .getFirst(
                                HttpHeaders.CONTENT_TYPE));

        assertEquals(
                "attachment; filename=\"orders.csv\"",
                response.getHeaders()
                        .getFirst(
                                HttpHeaders.CONTENT_DISPOSITION));

        assertArrayEquals(
                csvBytes,
                response.getBody());

        verify(orderService)
                .searchAllOrders(searchForm);

        verify(orderCsvService)
                .createCsv(orders);
    }

    @Test
    void markAsPaidDoesNotCallServiceWhenFormHasValidationErrors() {

        Long orderId = 1L;

        AdminOrderStatusChangeForm form = new AdminOrderStatusChangeForm();

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        BindingResult bindingResult = mock(BindingResult.class);

        when(bindingResult.hasErrors())
                .thenReturn(true);

        String viewName = adminOrderController.markAsPaid(
                orderId,
                form,
                bindingResult,
                loginUser,
                redirectAttributes);

        assertEquals(
                "redirect:/admin/orders/" + orderId,
                viewName);

        verify(orderService, org.mockito.Mockito.never())
                .markAsPaid(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());

        verify(redirectAttributes)
                .addFlashAttribute(
                        "errorMessage",
                        "変更理由・備考は500文字以内で入力してください。");
    }

    @Test
    void markAsShippedDoesNotCallServiceWhenFormHasValidationErrors() {

        Long orderId = 1L;

        AdminOrderStatusChangeForm form = new AdminOrderStatusChangeForm();

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        BindingResult bindingResult = mock(BindingResult.class);

        when(bindingResult.hasErrors())
                .thenReturn(true);

        String viewName = adminOrderController.markAsShipped(
                orderId,
                form,
                bindingResult,
                loginUser,
                redirectAttributes);

        assertEquals(
                "redirect:/admin/orders/" + orderId,
                viewName);

        verify(orderService, org.mockito.Mockito.never())
                .markAsShipped(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());

        verify(redirectAttributes)
                .addFlashAttribute(
                        "errorMessage",
                        "変更理由・備考は500文字以内で入力してください。");
    }

    @Test
    void cancelDoesNotCallServiceWhenFormHasValidationErrors() {

        Long orderId = 1L;

        AdminOrderStatusChangeForm form = new AdminOrderStatusChangeForm();

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        BindingResult bindingResult = mock(BindingResult.class);

        when(bindingResult.hasErrors())
                .thenReturn(true);

        String viewName = adminOrderController.cancel(
                orderId,
                form,
                bindingResult,
                loginUser,
                redirectAttributes);

        assertEquals(
                "redirect:/admin/orders/" + orderId,
                viewName);

        verify(orderService, org.mockito.Mockito.never())
                .cancelOrder(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());

        verify(redirectAttributes)
                .addFlashAttribute(
                        "errorMessage",
                        "変更理由・備考は500文字以内で入力してください。");
    }

    @Test
    void addNoteRegistersOrderNoteAndRedirectsToDetail() {

        when(loginUser.getId())
                .thenReturn(ADMIN_ID);

        when(loginUser.getUsername())
                .thenReturn(ADMIN_USERNAME);

        Long orderId = 1L;

        AdminOrderNoteForm form = new AdminOrderNoteForm();
        form.setNote("配送前に住所確認");

        BindingResult bindingResult = mock(BindingResult.class);
        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        String viewName = adminOrderController.addNote(
                orderId,
                form,
                bindingResult,
                loginUser,
                redirectAttributes);

        assertEquals(
                "redirect:/admin/orders/" + orderId,
                viewName);

        verify(orderNoteService)
                .addNote(
                        orderId,
                        "配送前に住所確認",
                        ADMIN_ID,
                        ADMIN_USERNAME);

        verify(redirectAttributes)
                .addFlashAttribute(
                        "successMessage",
                        "注文メモを登録しました。");
    }

    @Test
    void addNoteDoesNotCallServiceWhenFormHasValidationErrors() {

        Long orderId = 1L;

        AdminOrderNoteForm form = new AdminOrderNoteForm();

        BindingResult bindingResult = mock(BindingResult.class);
        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        FieldError fieldError = new FieldError(
                "adminOrderNoteForm",
                "note",
                "メモを入力してください。");

        when(bindingResult.hasErrors())
                .thenReturn(true);

        when(bindingResult.getFieldError("note"))
                .thenReturn(fieldError);

        String viewName = adminOrderController.addNote(
                orderId,
                form,
                bindingResult,
                loginUser,
                redirectAttributes);

        assertEquals(
                "redirect:/admin/orders/" + orderId,
                viewName);

        verify(orderNoteService, org.mockito.Mockito.never())
                .addNote(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());

        verify(redirectAttributes)
                .addFlashAttribute(
                        "errorMessage",
                        "メモを入力してください。");
    }

}
