package com.example.ecsite.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.support.SessionStatus;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import com.example.ecsite.cart.Cart;
import com.example.ecsite.cart.CartItem;
import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.ShippingAddress;
import com.example.ecsite.exception.OrderValidationException;
import com.example.ecsite.form.CheckoutForm;
import com.example.ecsite.security.CustomUserDetails;
import com.example.ecsite.service.OrderService;
import com.example.ecsite.service.ShippingAddressService;

import jakarta.servlet.http.HttpSession;

@ExtendWith(MockitoExtension.class)
class CheckoutControllerTest {

        @Mock
        private OrderService orderService;

        @Mock
        private ShippingAddressService shippingAddressService;

        private CheckoutController controller;
        private CustomUserDetails loginUser;

        @Mock
        private HttpSession session;

        @Mock
        private SessionStatus sessionStatus;

        private static final String CHECKOUT_TOKEN = "test-checkout-token";

        @BeforeEach
        void setUp() {

                controller = new CheckoutController(
                                orderService,
                                shippingAddressService);

                loginUser = new CustomUserDetails(
                                10L,
                                "user1",
                                "password",
                                true,
                                List.of());
        }

        @Test
        void placeOrderClearsSessionAfterSuccess() {

                Cart cart = createCart();
                CheckoutForm checkoutForm = createCheckoutForm();
                Order order = new Order(10L, 1000);

                when(session.getAttribute("checkoutToken"))
                                .thenReturn(CHECKOUT_TOKEN);

                when(orderService.createOrder(10L, cart, checkoutForm))
                                .thenReturn(order);

                BindingResult bindingResult = new BeanPropertyBindingResult(
                                checkoutForm,
                                "checkoutForm");

                RedirectAttributes redirectAttributes = new RedirectAttributesModelMap();

                String view = controller.placeOrder(
                                checkoutForm,
                                bindingResult,
                                cart,
                                loginUser,
                                CHECKOUT_TOKEN,
                                redirectAttributes,
                                session,
                                sessionStatus);

                assertEquals(
                                "redirect:/checkout/complete",
                                view);

                assertTrue(
                                redirectAttributes
                                                .getFlashAttributes()
                                                .containsKey("orderId"));

                verify(session)
                                .removeAttribute("checkoutToken");

                verify(sessionStatus)
                                .setComplete();

                verify(orderService)
                                .createOrder(
                                                10L,
                                                cart,
                                                checkoutForm);
        }

        @Test
        void placeOrderKeepsCartAfterValidationError() {

                Cart cart = createCart();
                CheckoutForm checkoutForm = createCheckoutForm();

                when(session.getAttribute("checkoutToken"))
                                .thenReturn(CHECKOUT_TOKEN);

                doThrow(new OrderValidationException(
                                "在庫が不足しています。"))
                                .when(orderService)
                                .createOrder(10L, cart, checkoutForm);

                BindingResult bindingResult = new BeanPropertyBindingResult(
                                checkoutForm,
                                "checkoutForm");

                RedirectAttributes redirectAttributes = new RedirectAttributesModelMap();

                String view = controller.placeOrder(
                                checkoutForm,
                                bindingResult,
                                cart,
                                loginUser,
                                CHECKOUT_TOKEN,
                                redirectAttributes,
                                session,
                                sessionStatus);

                assertEquals("redirect:/cart", view);
                assertEquals(1, cart.getItems().size());

                assertEquals(
                                "在庫が不足しています。",
                                redirectAttributes
                                                .getFlashAttributes()
                                                .get("errorMessage"));

                verify(session)
                                .removeAttribute("checkoutToken");

                verify(sessionStatus, never())
                                .setComplete();

                verify(orderService).createOrder(10L, cart, checkoutForm);
        }

        @Test
        void placeOrderReturnsInputWhenCheckoutFormHasErrors() {

                Cart cart = createCart();
                CheckoutForm checkoutForm = createCheckoutForm();

                when(session.getAttribute("checkoutToken"))
                                .thenReturn(CHECKOUT_TOKEN);

                BindingResult bindingResult = new BeanPropertyBindingResult(
                                checkoutForm,
                                "checkoutForm");

                bindingResult.rejectValue(
                                "shippingPostalCode",
                                "invalid",
                                "郵便番号の形式が正しくありません。");

                RedirectAttributes redirectAttributes = new RedirectAttributesModelMap();

                String view = controller.placeOrder(
                                checkoutForm,
                                bindingResult,
                                cart,
                                loginUser,
                                CHECKOUT_TOKEN,
                                redirectAttributes,
                                session,
                                sessionStatus);

                assertEquals("checkout/input", view);
                assertEquals(1, cart.getItems().size());

                verify(session)
                                .removeAttribute("checkoutToken");

                verify(sessionStatus, never())
                                .setComplete();

                verifyNoInteractions(orderService);
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

        private CheckoutForm createCheckoutForm() {

                CheckoutForm form = new CheckoutForm();

                form.setShippingName("山田 太郎");
                form.setShippingPostalCode("123-4567");
                form.setShippingPrefecture("東京都");
                form.setShippingCity("千代田区");
                form.setShippingAddressLine("1-2-3");
                form.setShippingPhone("090-1234-5678");

                return form;
        }

        @Test
        void placeOrderRejectsInvalidCheckoutToken() {

                Cart cart = createCart();
                CheckoutForm checkoutForm = createCheckoutForm();

                when(session.getAttribute("checkoutToken"))
                                .thenReturn(null);

                BindingResult bindingResult = new BeanPropertyBindingResult(
                                checkoutForm,
                                "checkoutForm");

                RedirectAttributes redirectAttributes = new RedirectAttributesModelMap();

                String view = controller.placeOrder(
                                checkoutForm,
                                bindingResult,
                                cart,
                                loginUser,
                                CHECKOUT_TOKEN,
                                redirectAttributes,
                                session,
                                sessionStatus);

                assertEquals(
                                "redirect:/cart",
                                view);

                assertEquals(
                                "注文処理が既に実行されたか、"
                                                + "確認画面の有効期限が切れています。",
                                redirectAttributes
                                                .getFlashAttributes()
                                                .get("errorMessage"));

                assertEquals(
                                1,
                                cart.getItems().size());

                verifyNoInteractions(orderService);

                verify(sessionStatus, never())
                                .setComplete();
        }

        @Test
        void inputPrefillsCheckoutFormFromDefaultAddress() {

                Cart cart = createCart();
                CheckoutForm checkoutForm = new CheckoutForm();

                ShippingAddress address = new ShippingAddress();
                address.setRecipientName("山田 太郎");
                address.setPostalCode("123-4567");
                address.setPrefecture("東京都");
                address.setCity("新宿区");
                address.setAddressLine("西新宿1-1-1");
                address.setPhone("090-1234-5678");

                when(shippingAddressService.findDefaultAddress(10L))
                                .thenReturn(Optional.of(address));

                RedirectAttributes redirectAttributes = new RedirectAttributesModelMap();

                String view = controller.input(
                                cart,
                                checkoutForm,
                                loginUser,
                                redirectAttributes);

                assertEquals("checkout/input", view);

                assertEquals(
                                "山田 太郎",
                                checkoutForm.getShippingName());

                assertEquals(
                                "123-4567",
                                checkoutForm.getShippingPostalCode());

                assertEquals(
                                "東京都",
                                checkoutForm.getShippingPrefecture());

                assertEquals(
                                "新宿区",
                                checkoutForm.getShippingCity());

                assertEquals(
                                "西新宿1-1-1",
                                checkoutForm.getShippingAddressLine());

                assertEquals(
                                "090-1234-5678",
                                checkoutForm.getShippingPhone());

                verify(shippingAddressService)
                                .findDefaultAddress(10L);
        }

        @Test
        void inputLeavesCheckoutFormEmptyWhenDefaultAddressDoesNotExist() {

                Cart cart = createCart();
                CheckoutForm checkoutForm = new CheckoutForm();

                when(shippingAddressService.findDefaultAddress(10L))
                                .thenReturn(Optional.empty());

                RedirectAttributes redirectAttributes = new RedirectAttributesModelMap();

                String view = controller.input(
                                cart,
                                checkoutForm,
                                loginUser,
                                redirectAttributes);

                assertEquals("checkout/input", view);

                assertEquals(
                                null,
                                checkoutForm.getShippingName());
        }

        @Test
        void inputDoesNotOverwriteExistingCheckoutForm() {

                Cart cart = createCart();

                CheckoutForm checkoutForm = createCheckoutForm();

                ShippingAddress address = new ShippingAddress();
                address.setRecipientName("別の氏名");
                address.setPostalCode("999-9999");

                RedirectAttributes redirectAttributes = new RedirectAttributesModelMap();

                String view = controller.input(
                                cart,
                                checkoutForm,
                                loginUser,
                                redirectAttributes);

                assertEquals("checkout/input", view);

                assertEquals(
                                "山田 太郎",
                                checkoutForm.getShippingName());

                assertEquals(
                                "123-4567",
                                checkoutForm.getShippingPostalCode());

                verifyNoInteractions(shippingAddressService);
        }
}