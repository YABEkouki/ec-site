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

    private static final String REASON = "顧客の要望で至急発送";

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req() {
        return post(URL).param("reason", REASON).param("expectedContentRevision", "3");
    }

    private ShippingPreparationStartResult result(Status status) {
        return new ShippingPreparationStartResult(status, LocalDateTime.of(2026, 10, 16, 14, 0), 7L, "admin");
    }

    @Test
    void startedRedirectsWithSuccessAndPassesOnlyOrderReasonAndRevision() throws Exception {
        when(service.start(42L, REASON, 3)).thenReturn(result(Status.STARTED));
        mvc.perform(req().param("actorId", "999").param("username", "forged"))
                .andExpect(status().isFound()).andExpect(redirectedUrl(DETAIL))
                .andExpect(flash().attribute("successMessage", "緊急出荷として発送準備を開始しました。"));
        verify(service).start(42L, REASON, 3);
        verifyNoMoreInteractions(service);
    }

    @Test
    void duplicatePostsReportAlreadyStarted() throws Exception {
        when(service.start(42L, REASON, 3)).thenReturn(result(Status.STARTED), result(Status.ALREADY_STARTED));
        mvc.perform(req()).andExpect(flash().attribute("successMessage", "緊急出荷として発送準備を開始しました。"));
        mvc.perform(req()).andExpect(status().isFound()).andExpect(redirectedUrl(DETAIL))
                .andExpect(flash().attribute("successMessage", "この注文は既に発送準備を開始しています。"));
        verify(service, times(2)).start(42L, REASON, 3);
        verifyNoMoreInteractions(service);
    }

    @Test
    void illegalStateUsesFixedMessageWithoutInternalDetails() throws Exception {
        when(service.start(42L, REASON, 3)).thenThrow(new IllegalStateException("secret payment data"));
        assertBusinessFailure();
    }

    @Test
    void invalidStatusUsesFixedMessage() throws Exception {
        when(service.start(42L, REASON, 3)).thenThrow(new InvalidOrderStatusException("secret status"));
        assertBusinessFailure();
    }

    private void assertBusinessFailure() throws Exception {
        mvc.perform(req()).andExpect(status().isFound()).andExpect(redirectedUrl(DETAIL))
                .andExpect(flash().attribute("errorMessage", FAILURE)).andExpect(flash().attributeCount(1));
        verify(service).start(42L, REASON, 3);
    }

    @Test
    void accessDeniedIs403EvenWithGlobalCatchAllAdvice() throws Exception {
        when(service.start(42L, REASON, 3)).thenThrow(new AccessDeniedException("secret"));
        mvc.perform(req()).andExpect(status().isForbidden()).andExpect(content().string(""))
                .andExpect(flash().attributeCount(0));
    }

    @Test
    void unexpectedExceptionReachesExistingSafe500Handler() throws Exception {
        when(service.start(42L, REASON, 3)).thenThrow(new UnsupportedOperationException("secret"));
        mvc.perform(req()).andExpect(status().isInternalServerError())
                .andExpect(view().name("error/internal-server-error"))
                .andExpect(model().attribute("message", "システムエラーが発生しました。しばらくしてから再度お試しください。"))
                .andExpect(flash().attributeCount(0));
        assertThrows(UnsupportedOperationException.class,
                () -> controller.startShippingPreparation(42L, REASON, 3, null, new RedirectAttributesModelMap()));
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
        when(service.start(42L, REASON, 3)).thenThrow(new OrderNotFoundException(42L));
        mvc.perform(req()).andExpect(status().isNotFound()).andExpect(view().name("error/order_not_found"));
    }

    @Test
    void preservesSearchPagingAndSortInEncodedReturnUrl() throws Exception {
        when(service.start(42L, REASON, 3)).thenReturn(result(Status.STARTED));
        mvc.perform(req().param("returnUrl", "/admin/orders?status=ORDERED&page=2&size=20&sort=id,desc"))
                .andExpect(redirectedUrl("/admin/orders/42?returnUrl=%2Fadmin%2Forders%3Fstatus%3DORDERED%26page%3D2%26size%3D20%26sort%3Did%2Cdesc"));
    }

    @Test
    void preservesPaymentDiscrepancyReturnUrl() throws Exception {
        when(service.start(42L, REASON, 3)).thenReturn(result(Status.STARTED));
        mvc.perform(req().param("returnUrl", "/admin/payment-discrepancies?page=3"))
                .andExpect(redirectedUrl("/admin/orders/42?returnUrl=%2Fadmin%2Fpayment-discrepancies%3Fpage%3D3"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://evil.example/", "//evil.example", "/orders", "/admin/orders/42", ""})
    void invalidReturnUrlFallsBackToOrderList(String returnUrl) throws Exception {
        when(service.start(42L, REASON, 3)).thenReturn(result(Status.STARTED));
        mvc.perform(req().param("returnUrl", returnUrl)).andExpect(redirectedUrl(DETAIL));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "-1", "abc"})
    void missingOrInvalidConfirmedRevisionIs400WithoutServiceCall(String revision) throws Exception {
        mvc.perform(post(URL).param("reason", REASON).param("expectedContentRevision", revision))
                .andExpect(status().isBadRequest()).andExpect(content().string(""));
        mvc.perform(post(URL).param("reason", REASON)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void emergencyRejectionShowsItsOwnMessage() throws Exception {
        when(service.start(42L, "", 3)).thenThrow(
                new com.example.ecsite.service.emergency.EmergencyShippingRejectedException("緊急出荷の理由を入力してください。"));
        mvc.perform(post(URL).param("reason", "").param("expectedContentRevision", "3"))
                .andExpect(status().isFound()).andExpect(redirectedUrl(DETAIL))
                .andExpect(flash().attribute("errorMessage", "緊急出荷の理由を入力してください。"));
    }

    @Test
    void getDoesNotInvokeService() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(service);
    }
}
