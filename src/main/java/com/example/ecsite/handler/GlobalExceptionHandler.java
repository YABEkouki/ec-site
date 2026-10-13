package com.example.ecsite.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

import com.example.ecsite.exception.AnnouncementNotFoundException;
import com.example.ecsite.exception.CustomerNotFoundException;
import com.example.ecsite.exception.OrderNotFoundException;
import com.example.ecsite.exception.ProductNotFoundException;

@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ProductNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleProductNotFound(
            ProductNotFoundException e,
            Model model) {

        model.addAttribute("message", e.getMessage());

        return "error/product_not_found";
    }

    @ExceptionHandler(OrderNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleOrderNotFound(
            OrderNotFoundException e,
            Model model) {

        model.addAttribute(
                "message",
                e.getMessage());

        return "error/order_not_found";
    }

    @ExceptionHandler(CustomerNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleCustomerNotFound(
            CustomerNotFoundException e,
            Model model) {

        model.addAttribute(
                "message",
                e.getMessage());

        return "error/customer_not_found";
    }

    @ExceptionHandler(AnnouncementNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleAnnouncementNotFound(AnnouncementNotFoundException e) {

        return "error/not_found";
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public String handleMethodNotSupported(
            HttpRequestMethodNotSupportedException e,
            HttpServletResponse response) {

        // Spring derives Allow from the matching controller's supported methods.
        e.getHeaders().forEach((name, values) -> response.setHeader(name, String.join(", ", values)));
        return "error/method-not-allowed";
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public String handleException(
            Exception e,
            Model model) {

        logger.error("予期しないエラーが発生しました。", e);

        model.addAttribute(
                "message",
                "システムエラーが発生しました。しばらくしてから再度お試しください。");

        return "error/internal-server-error";
    }
}
