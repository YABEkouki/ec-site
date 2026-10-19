package com.example.ecsite.controller;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;
import com.example.ecsite.exception.InvalidOrderStatusException;
import com.example.ecsite.exception.OrderNotFoundException;
import com.example.ecsite.handler.GlobalExceptionHandler;
import com.example.ecsite.service.OrderShippingPreparationService;
import com.example.ecsite.service.ShippingPreparationStartResult;
import com.example.ecsite.service.ShippingPreparationStartResult.Status;

class AdminOrderShippingPreparationControllerTest {
    private OrderShippingPreparationService service;
    private AdminOrderShippingPreparationController controller;
    private MockMvc mvc;
    private static final String URL = "/admin/orders/42/start-shipping-preparation";
    private static final String DETAIL = "/admin/orders/42?returnUrl=%2Fadmin%2Forders";
    private static final String FAILURE = "発送準備を開始できません。変更期限・注文状態・決済状況をご確認ください。";

    @BeforeEach
    void setUp() {
        service = mock(OrderShippingPreparationService.class);
        controller = new AdminOrderShippingPreparationController(service);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    private ShippingPreparationStartResult result(Status status) {
        return new ShippingPreparationStartResult(status, LocalDateTime.of(2026, 10, 16, 14, 0), 7L, "admin");
    }

    @Test
    void startedRedirectsWithSuccessAndOnlyPassesOrderId() throws Exception {
        when(service.start(42L)).thenReturn(result(Status.STARTED));
        mvc.perform(post(URL).param("actorId", "999").param("username", "forged").param("reason", "ignored"))
                .andExpect(status().isFound()).andExpect(redirectedUrl(DETAIL))
                .andExpect(flash().attribute("successMessage", "発送準備を開始しました。"));
        verify(service).start(42L);
        verifyNoMoreInteractions(service);
    }

    @Test
    void duplicatePostsReportAlreadyStarted() throws Exception {
        when(service.start(42L)).thenReturn(result(Status.STARTED), result(Status.ALREADY_STARTED));
        mvc.perform(post(URL)).andExpect(flash().attribute("successMessage", "発送準備を開始しました。"));
        mvc.perform(post(URL)).andExpect(status().isFound()).andExpect(redirectedUrl(DETAIL))
                .andExpect(flash().attribute("successMessage", "この注文は既に発送準備を開始しています。"));
        verify(service, times(2)).start(42L);
        verifyNoMoreInteractions(service);
    }

    @Test
    void illegalStateUsesFixedMessageWithoutInternalDetails() throws Exception {
        when(service.start(42L)).thenThrow(new IllegalStateException("secret payment data"));
        assertBusinessFailure();
    }

    @Test
    void invalidStatusUsesFixedMessage() throws Exception {
        when(service.start(42L)).thenThrow(new InvalidOrderStatusException("secret status"));
        assertBusinessFailure();
    }

    private void assertBusinessFailure() throws Exception {
        mvc.perform(post(URL)).andExpect(status().isFound()).andExpect(redirectedUrl(DETAIL))
                .andExpect(flash().attribute("errorMessage", FAILURE)).andExpect(flash().attributeCount(1));
        verify(service).start(42L);
    }

    @Test
    void accessDeniedIs403EvenWithGlobalCatchAllAdvice() throws Exception {
        when(service.start(42L)).thenThrow(new AccessDeniedException("secret"));
        mvc.perform(post(URL)).andExpect(status().isForbidden()).andExpect(content().string(""))
                .andExpect(flash().attributeCount(0));
    }

    @Test
    void unexpectedExceptionReachesExistingSafe500Handler() throws Exception {
        when(service.start(42L)).thenThrow(new UnsupportedOperationException("secret"));
        mvc.perform(post(URL)).andExpect(status().isInternalServerError())
                .andExpect(view().name("error/internal-server-error"))
                .andExpect(model().attribute("message", "システムエラーが発生しました。しばらくしてから再度お試しください。"))
                .andExpect(flash().attributeCount(0));
        assertThrows(UnsupportedOperationException.class,
                () -> controller.startShippingPreparation(42L, null, new RedirectAttributesModelMap()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "9223372036854775808"})
    void invalidIdIs400WithoutServiceCall(String id) throws Exception {
        mvc.perform(post("/admin/orders/" + id + "/start-shipping-preparation"))
                .andExpect(status().isBadRequest()).andExpect(content().string(""));
        verifyNoInteractions(service);
    }

    @Test
    void missingOrderUsesExisting404Handler() throws Exception {
        when(service.start(42L)).thenThrow(new OrderNotFoundException(42L));
        mvc.perform(post(URL)).andExpect(status().isNotFound()).andExpect(view().name("error/order_not_found"));
    }

    @Test
    void preservesSearchPagingAndSortInEncodedReturnUrl() throws Exception {
        when(service.start(42L)).thenReturn(result(Status.STARTED));
        mvc.perform(post(URL).param("returnUrl", "/admin/orders?status=ORDERED&page=2&size=20&sort=id,desc"))
                .andExpect(redirectedUrl("/admin/orders/42?returnUrl=%2Fadmin%2Forders%3Fstatus%3DORDERED%26page%3D2%26size%3D20%26sort%3Did%2Cdesc"));
    }

    @Test
    void preservesPaymentDiscrepancyReturnUrl() throws Exception {
        when(service.start(42L)).thenReturn(result(Status.STARTED));
        mvc.perform(post(URL).param("returnUrl", "/admin/payment-discrepancies?page=3"))
                .andExpect(redirectedUrl("/admin/orders/42?returnUrl=%2Fadmin%2Fpayment-discrepancies%3Fpage%3D3"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://evil.example/", "//evil.example", "/orders", "/admin/orders/42", ""})
    void invalidReturnUrlFallsBackToOrderList(String returnUrl) throws Exception {
        when(service.start(42L)).thenReturn(result(Status.STARTED));
        mvc.perform(post(URL).param("returnUrl", returnUrl)).andExpect(redirectedUrl(DETAIL));
    }

    @Test
    void getDoesNotInvokeService() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(service);
    }
}
