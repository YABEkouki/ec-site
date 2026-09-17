package com.example.ecsite.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

import com.example.ecsite.exception.AnnouncementNotFoundException;
import com.example.ecsite.exception.CustomerNotFoundException;
import com.example.ecsite.exception.OrderNotFoundException;
import com.example.ecsite.exception.ProductNotFoundException;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleProductNotFoundReturnsProductNotFoundView() {

        ProductNotFoundException exception = new ProductNotFoundException(999L);

        Model model = new ConcurrentModel();

        String view = handler.handleProductNotFound(
                exception,
                model);

        assertEquals(
                "error/product_not_found",
                view);
        assertEquals(
                exception.getMessage(),
                model.getAttribute("message"));
    }

    @Test
    void handleOrderNotFoundReturnsOrderNotFoundView() {

        OrderNotFoundException exception = new OrderNotFoundException(999L);

        Model model = new ConcurrentModel();

        String view = handler.handleOrderNotFound(
                exception,
                model);

        assertEquals(
                "error/order_not_found",
                view);
        assertEquals(
                exception.getMessage(),
                model.getAttribute("message"));
    }

    @Test
    void handleCustomerNotFoundReturnsCustomerNotFoundView() {

        CustomerNotFoundException exception = new CustomerNotFoundException(999L);

        Model model = new ConcurrentModel();

        String view = handler.handleCustomerNotFound(
                exception,
                model);

        assertEquals(
                "error/customer_not_found",
                view);
        assertEquals(
                exception.getMessage(),
                model.getAttribute("message"));
    }

    @Test
    void handleAnnouncementNotFoundReturnsGenericNotFoundView() {

        AnnouncementNotFoundException exception = new AnnouncementNotFoundException(999L);

        String view = handler.handleAnnouncementNotFound(exception);

        assertEquals(
                "error/not_found",
                view);
    }

    @Test
    void handleExceptionReturnsInternalServerErrorView() {

        Exception exception = new RuntimeException("test error");

        Model model = new ConcurrentModel();

        String view = handler.handleException(
                exception,
                model);

        assertEquals(
                "error/internal-server-error",
                view);

        assertEquals(
                "システムエラーが発生しました。しばらくしてから再度お試しください。",
                model.getAttribute("message"));
    }
}
