package com.example.ecsite.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
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
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.Validator;
import org.springframework.web.bind.support.SessionStatus;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import com.example.ecsite.cart.Cart;
import com.example.ecsite.cart.CartItem;
import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.ShippingAddress;
import com.example.ecsite.exception.OrderValidationException;
import com.example.ecsite.exception.ShippingAddressNotFoundException;
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

        @Mock
        private Validator validator;

        private static final String CHECKOUT_TOKEN = "test-checkout-token";

        @BeforeEach
        void setUp() {

                controller = new CheckoutController(
                                orderService,
                                shippingAddressService,
                                validator);

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
        void inputSelectsDefaultAddressWithoutCopyingAddressValues() {

                Cart cart = createCart();
                CheckoutForm checkoutForm = new CheckoutForm();

                ShippingAddress address = mock(ShippingAddress.class);

                when(address.getId())
                                .thenReturn(25L);

                when(shippingAddressService.findDefaultAddress(10L))
                                .thenReturn(Optional.of(address));

                Model model = new ConcurrentModel();

                RedirectAttributes redirectAttributes = new RedirectAttributesModelMap();

                String view = controller.input(
                                cart,
                                checkoutForm,
                                loginUser,
                                false,
                                model,
                                redirectAttributes);

                assertEquals(
                                "checkout/input",
                                view);

                assertEquals(
                                25L,
                                checkoutForm.getShippingAddressId());

                assertEquals(
                                CheckoutForm.SHIPPING_ADDRESS_MODE_REGISTERED,
                                checkoutForm.getShippingAddressMode());

                assertNull(checkoutForm.getShippingName());
                assertNull(checkoutForm.getShippingPostalCode());
                assertNull(checkoutForm.getShippingPrefecture());
                assertNull(checkoutForm.getShippingCity());
                assertNull(checkoutForm.getShippingAddressLine());
                assertNull(checkoutForm.getShippingPhone());

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

                Model model = new ConcurrentModel();

                String view = controller.input(
                                cart,
                                checkoutForm,
                                loginUser,
                                false,
                                model,
                                redirectAttributes);

                assertEquals("checkout/input", view);

                assertEquals(
                                null,
                                checkoutForm.getShippingName());
        }

        @Test
        void inputDoesNotOverwriteExistingCheckoutFormWhenReturningFromConfirm() {

                Cart cart = createCart();

                CheckoutForm checkoutForm = createCheckoutForm();

                ShippingAddress address = new ShippingAddress();
                address.setRecipientName("別の氏名");
                address.setPostalCode("999-9999");

                RedirectAttributes redirectAttributes = new RedirectAttributesModelMap();

                Model model = new ConcurrentModel();

                String view = controller.input(
                                cart,
                                checkoutForm,
                                loginUser,
                                true,
                                model,
                                redirectAttributes);

                assertEquals("checkout/input", view);

                assertEquals(
                                "山田 太郎",
                                checkoutForm.getShippingName());

                assertEquals(
                                "123-4567",
                                checkoutForm.getShippingPostalCode());

                verify(shippingAddressService, never())
                                .findDefaultAddress(10L);
        }

        @Test
        void inputAddsUsersShippingAddressesToModel() {

                Cart cart = createCart();
                CheckoutForm checkoutForm = createCheckoutForm();

                ShippingAddress home = new ShippingAddress();
                home.setName("自宅");

                ShippingAddress office = new ShippingAddress();
                office.setName("勤務先");

                List<ShippingAddress> addresses = List.of(home, office);

                when(shippingAddressService.findAllByUserId(10L))
                                .thenReturn(addresses);

                Model model = new ConcurrentModel();

                RedirectAttributes redirectAttributes = new RedirectAttributesModelMap();

                String view = controller.input(
                                cart,
                                checkoutForm,
                                loginUser,
                                false,
                                model,
                                redirectAttributes);

                assertEquals(
                                "checkout/input",
                                view);

                assertSame(
                                addresses,
                                model.getAttribute("shippingAddresses"));

                verify(shippingAddressService)
                                .findAllByUserId(10L);
        }

        @Test
        void confirmCopiesSelectedShippingAddressToCheckoutForm() {

                Cart cart = createCart();
                CheckoutForm checkoutForm = createCheckoutForm();

                checkoutForm.setShippingAddressMode(
                                CheckoutForm.SHIPPING_ADDRESS_MODE_REGISTERED);

                checkoutForm.setShippingAddressId(25L);

                ShippingAddress address = new ShippingAddress();

                address.setRecipientName("鈴木 花子");
                address.setPostalCode("987-6543");
                address.setPrefecture("神奈川県");
                address.setCity("横浜市");
                address.setAddressLine("中区1-2-3");
                address.setPhone("080-1111-2222");

                when(shippingAddressService.findByIdAndUserId(
                                25L,
                                10L))
                                .thenReturn(address);

                BindingResult bindingResult = new BeanPropertyBindingResult(
                                checkoutForm,
                                "checkoutForm");

                RedirectAttributes redirectAttributes = new RedirectAttributesModelMap();

                Model model = new ConcurrentModel();

                String view = controller.confirm(
                                checkoutForm,
                                bindingResult,
                                cart,
                                loginUser,
                                redirectAttributes,
                                model,
                                session);

                assertEquals(
                                "checkout/confirm",
                                view);

                assertEquals(
                                "鈴木 花子",
                                checkoutForm.getShippingName());

                assertEquals(
                                "987-6543",
                                checkoutForm.getShippingPostalCode());

                assertEquals(
                                "神奈川県",
                                checkoutForm.getShippingPrefecture());

                assertEquals(
                                "横浜市",
                                checkoutForm.getShippingCity());

                assertEquals(
                                "中区1-2-3",
                                checkoutForm.getShippingAddressLine());

                assertEquals(
                                "080-1111-2222",
                                checkoutForm.getShippingPhone());

                verify(shippingAddressService)
                                .findByIdAndUserId(
                                                25L,
                                                10L);
        }

        @Test
        void confirmUsesRegisteredAddressBeforeValidation() {

                Cart cart = createCart();

                CheckoutForm checkoutForm = new CheckoutForm();

                checkoutForm.setShippingAddressMode(
                                CheckoutForm.SHIPPING_ADDRESS_MODE_REGISTERED);

                checkoutForm.setShippingAddressId(25L);

                ShippingAddress address = new ShippingAddress();

                address.setRecipientName("鈴木 花子");
                address.setPostalCode("987-6543");
                address.setPrefecture("神奈川県");
                address.setCity("横浜市");
                address.setAddressLine("中区1-2-3");
                address.setPhone("080-1111-2222");

                when(shippingAddressService.findByIdAndUserId(
                                25L,
                                10L))
                                .thenReturn(address);

                BindingResult bindingResult = new BeanPropertyBindingResult(
                                checkoutForm,
                                "checkoutForm");

                doAnswer(invocation -> {

                        CheckoutForm validatedForm = invocation.getArgument(0);

                        assertEquals(
                                        "鈴木 花子",
                                        validatedForm.getShippingName());

                        assertEquals(
                                        "987-6543",
                                        validatedForm.getShippingPostalCode());

                        return null;

                }).when(validator)
                                .validate(
                                                org.mockito.ArgumentMatchers.eq(checkoutForm),
                                                org.mockito.ArgumentMatchers.eq(bindingResult));

                RedirectAttributes redirectAttributes = new RedirectAttributesModelMap();

                Model model = new ConcurrentModel();

                String view = controller.confirm(
                                checkoutForm,
                                bindingResult,
                                cart,
                                loginUser,
                                redirectAttributes,
                                model,
                                session);

                assertEquals(
                                "checkout/confirm",
                                view);

                assertEquals(
                                "鈴木 花子",
                                checkoutForm.getShippingName());

                verify(orderService)
                                .validateCart(cart);

                verify(validator)
                                .validate(
                                                checkoutForm,
                                                bindingResult);
        }

        @Test
        void confirmUsesDirectInputWithoutLoadingRegisteredAddress() {

                Cart cart = createCart();
                CheckoutForm checkoutForm = createCheckoutForm();

                checkoutForm.setShippingAddressMode(
                                CheckoutForm.SHIPPING_ADDRESS_MODE_DIRECT);

                // 不正・不要なIDが送信されてもDIRECTでは使用しない
                checkoutForm.setShippingAddressId(999L);

                checkoutForm.setShippingName("佐藤 次郎");
                checkoutForm.setShippingPostalCode("111-2222");
                checkoutForm.setShippingPrefecture("埼玉県");
                checkoutForm.setShippingCity("さいたま市");
                checkoutForm.setShippingAddressLine("大宮区1-2-3");
                checkoutForm.setShippingPhone("070-1111-2222");

                BindingResult bindingResult = new BeanPropertyBindingResult(
                                checkoutForm,
                                "checkoutForm");

                RedirectAttributes redirectAttributes = new RedirectAttributesModelMap();

                Model model = new ConcurrentModel();

                String view = controller.confirm(
                                checkoutForm,
                                bindingResult,
                                cart,
                                loginUser,
                                redirectAttributes,
                                model,
                                session);

                assertEquals(
                                "checkout/confirm",
                                view);

                assertNull(
                                checkoutForm.getShippingAddressId());

                assertEquals(
                                "佐藤 次郎",
                                checkoutForm.getShippingName());

                assertEquals(
                                "111-2222",
                                checkoutForm.getShippingPostalCode());

                verify(shippingAddressService, never())
                                .findByIdAndUserId(
                                                org.mockito.ArgumentMatchers.anyLong(),
                                                org.mockito.ArgumentMatchers.anyLong());

                verify(validator)
                                .validate(
                                                checkoutForm,
                                                bindingResult);

                verify(orderService)
                                .validateCart(cart);
        }

        @Test
        void confirmReturnsInputWhenRegisteredAddressIdIsMissing() {

                Cart cart = createCart();
                CheckoutForm checkoutForm = createCheckoutForm();

                checkoutForm.setShippingAddressMode(
                                CheckoutForm.SHIPPING_ADDRESS_MODE_REGISTERED);

                checkoutForm.setShippingAddressId(null);

                BindingResult bindingResult = new BeanPropertyBindingResult(
                                checkoutForm,
                                "checkoutForm");

                RedirectAttributes redirectAttributes = new RedirectAttributesModelMap();

                Model model = new ConcurrentModel();

                when(shippingAddressService.findAllByUserId(10L))
                                .thenReturn(List.of());

                String view = controller.confirm(
                                checkoutForm,
                                bindingResult,
                                cart,
                                loginUser,
                                redirectAttributes,
                                model,
                                session);

                assertEquals(
                                "checkout/input",
                                view);

                assertTrue(
                                bindingResult.hasFieldErrors(
                                                "shippingAddressId"));

                verify(shippingAddressService, never())
                                .findByIdAndUserId(
                                                org.mockito.ArgumentMatchers.anyLong(),
                                                org.mockito.ArgumentMatchers.anyLong());

                verify(orderService, never())
                                .validateCart(cart);

                verify(shippingAddressService)
                                .findAllByUserId(10L);
        }

        @Test
        void confirmRejectsShippingAddressOwnedByAnotherUser() {

                Cart cart = createCart();
                CheckoutForm checkoutForm = createCheckoutForm();

                checkoutForm.setShippingAddressMode(
                                CheckoutForm.SHIPPING_ADDRESS_MODE_REGISTERED);

                checkoutForm.setShippingAddressId(99L);

                when(shippingAddressService.findByIdAndUserId(
                                99L,
                                10L))
                                .thenThrow(
                                                new ShippingAddressNotFoundException(
                                                                99L));

                BindingResult bindingResult = new BeanPropertyBindingResult(
                                checkoutForm,
                                "checkoutForm");

                RedirectAttributes redirectAttributes = new RedirectAttributesModelMap();

                Model model = new ConcurrentModel();

                assertThrows(
                                ShippingAddressNotFoundException.class,
                                () -> controller.confirm(
                                                checkoutForm,
                                                bindingResult,
                                                cart,
                                                loginUser,
                                                redirectAttributes,
                                                model,
                                                session));

                verify(shippingAddressService)
                                .findByIdAndUserId(
                                                99L,
                                                10L);

                verify(orderService, never())
                                .validateCart(cart);

                verify(validator, never())
                                .validate(
                                                checkoutForm,
                                                bindingResult);
        }

        @Test
        void confirmReturnsInputWhenShippingAddressModeIsInvalid() {

                Cart cart = createCart();
                CheckoutForm checkoutForm = createCheckoutForm();

                checkoutForm.setShippingAddressMode("INVALID");
                checkoutForm.setShippingAddressId(25L);

                BindingResult bindingResult = new BeanPropertyBindingResult(
                                checkoutForm,
                                "checkoutForm");

                RedirectAttributes redirectAttributes = new RedirectAttributesModelMap();

                Model model = new ConcurrentModel();

                when(shippingAddressService.findAllByUserId(10L))
                                .thenReturn(List.of());

                String view = controller.confirm(
                                checkoutForm,
                                bindingResult,
                                cart,
                                loginUser,
                                redirectAttributes,
                                model,
                                session);

                assertEquals(
                                "checkout/input",
                                view);

                assertTrue(
                                bindingResult.hasFieldErrors(
                                                "shippingAddressMode"));

                verify(shippingAddressService, never())
                                .findByIdAndUserId(
                                                org.mockito.ArgumentMatchers.anyLong(),
                                                org.mockito.ArgumentMatchers.anyLong());

                verify(orderService, never())
                                .validateCart(cart);

                verify(shippingAddressService)
                                .findAllByUserId(10L);
        }

        @Test
        void inputRefreshesDefaultAddressSelectionWhenStartingCheckoutAgain() {

                Cart cart = createCart();
                CheckoutForm checkoutForm = createCheckoutForm();

                checkoutForm.setShippingAddressMode(
                                CheckoutForm.SHIPPING_ADDRESS_MODE_REGISTERED);

                // 前回CheckoutではID=25を使用していた
                checkoutForm.setShippingAddressId(25L);

                ShippingAddress currentDefault = mock(ShippingAddress.class);

                // マイページ操作などにより現在のデフォルトはID=30
                when(currentDefault.getId())
                                .thenReturn(30L);

                when(shippingAddressService.findDefaultAddress(10L))
                                .thenReturn(Optional.of(currentDefault));

                Model model = new ConcurrentModel();

                RedirectAttributes redirectAttributes = new RedirectAttributesModelMap();

                String view = controller.input(
                                cart,
                                checkoutForm,
                                loginUser,
                                false,
                                model,
                                redirectAttributes);

                assertEquals(
                                "checkout/input",
                                view);

                assertEquals(
                                CheckoutForm.SHIPPING_ADDRESS_MODE_REGISTERED,
                                checkoutForm.getShippingAddressMode());

                assertEquals(
                                30L,
                                checkoutForm.getShippingAddressId());

                verify(shippingAddressService)
                                .findDefaultAddress(10L);
        }

        @Test
        void inputClearsOldShippingStateWhenStartingCheckoutWithoutDefaultAddress() {

                Cart cart = createCart();

                CheckoutForm checkoutForm = createCheckoutForm();

                checkoutForm.setShippingAddressMode(
                                CheckoutForm.SHIPPING_ADDRESS_MODE_REGISTERED);

                checkoutForm.setShippingAddressId(25L);

                when(shippingAddressService.findDefaultAddress(10L))
                                .thenReturn(Optional.empty());

                Model model = new ConcurrentModel();

                RedirectAttributes redirectAttributes = new RedirectAttributesModelMap();

                String view = controller.input(
                                cart,
                                checkoutForm,
                                loginUser,
                                false,
                                model,
                                redirectAttributes);

                assertEquals(
                                "checkout/input",
                                view);

                assertNull(
                                checkoutForm.getShippingAddressMode());

                assertNull(
                                checkoutForm.getShippingAddressId());

                assertNull(
                                checkoutForm.getShippingName());

                assertNull(
                                checkoutForm.getShippingPostalCode());

                assertNull(
                                checkoutForm.getShippingPrefecture());

                assertNull(
                                checkoutForm.getShippingCity());

                assertNull(
                                checkoutForm.getShippingAddressLine());

                assertNull(
                                checkoutForm.getShippingPhone());
        }
}
