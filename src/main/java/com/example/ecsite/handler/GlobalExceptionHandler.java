package com.example.ecsite.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

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

        return "error/not-found";
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