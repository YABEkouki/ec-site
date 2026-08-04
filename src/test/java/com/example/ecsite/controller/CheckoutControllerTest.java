package com.example.ecsite.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import com.example.ecsite.cart.Cart;
import com.example.ecsite.cart.CartItem;
import com.example.ecsite.entity.Order;
import com.example.ecsite.exception.OrderValidationException;
import com.example.ecsite.security.CustomUserDetails;
import com.example.ecsite.service.OrderService;

@ExtendWith(MockitoExtension.class)
class CheckoutControllerTest {

    @Mock
    private OrderService orderService;

    private CheckoutController controller;
    private CustomUserDetails loginUser;

    @BeforeEach
    void setUp() {

        controller = new CheckoutController(orderService);

        loginUser = new CustomUserDetails(
                10L,
                "user1",
                "password",
                true,
                List.of());
    }

    @Test
    void placeOrderClearsCartAfterSuccess() {

        Cart cart = createCart();
        Order order = new Order(10L, 1000);

        when(orderService.createOrder(10L, cart))
                .thenReturn(order);

        RedirectAttributes redirectAttributes =
                new RedirectAttributesModelMap();

        String view = controller.placeOrder(
                cart,
                loginUser,
                redirectAttributes);

        assertEquals(
                "redirect:/checkout/complete",
                view);

        assertTrue(cart.getItems().isEmpty());

        assertTrue(
                redirectAttributes
                        .getFlashAttributes()
                        .containsKey("orderId"));

        verify(orderService).createOrder(10L, cart);
    }

    @Test
    void placeOrderKeepsCartAfterValidationError() {

        Cart cart = createCart();

        doThrow(new OrderValidationException(
                "在庫が不足しています。"))
                .when(orderService)
                .createOrder(10L, cart);

        RedirectAttributes redirectAttributes =
                new RedirectAttributesModelMap();

        String view = controller.placeOrder(
                cart,
                loginUser,
                redirectAttributes);

        assertEquals("redirect:/cart", view);
        assertEquals(1, cart.getItems().size());

        assertEquals(
                "在庫が不足しています。",
                redirectAttributes
                        .getFlashAttributes()
                        .get("errorMessage"));

        verify(orderService).createOrder(10L, cart);
    }

    private Cart createCart() {

        Cart cart = new Cart();

        cart.addItem(new CartItem(
                1L,
                "テスト商品",
                1000,
                1));

        return cart;
    }
}