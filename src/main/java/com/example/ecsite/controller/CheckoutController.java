package com.example.ecsite.controller;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.Validator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.SessionAttributes;
import org.springframework.web.bind.support.SessionStatus;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.example.ecsite.cart.Cart;
import com.example.ecsite.config.PayJpProperties;
import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.ShippingAddress;
import com.example.ecsite.exception.OrderValidationException;
import com.example.ecsite.form.CheckoutForm;
import com.example.ecsite.payment.AuthorizationResult;
import com.example.ecsite.payment.AuthorizationResultStatus;
import com.example.ecsite.payment.PaymentGatewayException;
import com.example.ecsite.security.CustomUserDetails;
import com.example.ecsite.service.OrderService;
import com.example.ecsite.service.ShippingAddressService;
import com.example.ecsite.service.payment.PaymentAuthorizationPreparation;
import com.example.ecsite.service.payment.PaymentAuthorizationService;
import com.example.ecsite.service.pricing.OrderAmount;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;

@Controller
@RequestMapping("/checkout")
@SessionAttributes({ "cart", "checkoutForm" })
public class CheckoutController {

    private final OrderService orderService;
    private final ShippingAddressService shippingAddressService;
    private final Validator validator;
    private final PaymentAuthorizationService paymentAuthorizationService;
    private final PayJpProperties payJpProperties;

    public CheckoutController(
            OrderService orderService,
            ShippingAddressService shippingAddressService,
            Validator validator,
            PaymentAuthorizationService paymentAuthorizationService,
            PayJpProperties payJpProperties) {

        this.orderService = orderService;
        this.shippingAddressService = shippingAddressService;
        this.validator = validator;
        this.paymentAuthorizationService = paymentAuthorizationService;
        this.payJpProperties = payJpProperties;
    }

    @ModelAttribute("cart")
    public Cart cart() {
        return new Cart();
    }

    @ModelAttribute("checkoutForm")
    public CheckoutForm checkoutForm() {
        return new CheckoutForm();
    }

    @GetMapping
    public String input(
            @ModelAttribute("cart") Cart cart,
            @ModelAttribute("checkoutForm") CheckoutForm checkoutForm,
            @AuthenticationPrincipal CustomUserDetails loginUser,
            @RequestParam(defaultValue = "false") boolean back,
            Model model,
            RedirectAttributes redirectAttributes) {

        try {
            orderService.validateCart(cart);

        } catch (OrderValidationException e) {

            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    e.getMessage());

            return "redirect:/cart";
        }

        model.addAttribute(
                "shippingAddresses",
                shippingAddressService.findAllByUserId(
                        loginUser.getId()));

        if (!back) {

            clearCheckoutShipping(checkoutForm);

            shippingAddressService.findDefaultAddress(
                    loginUser.getId())
                    .ifPresent(address -> selectShippingAddress(
                            address,
                            checkoutForm));

        } else if (isCheckoutFormEmpty(checkoutForm)) {

            shippingAddressService.findDefaultAddress(
                    loginUser.getId())
                    .ifPresent(address -> selectShippingAddress(
                            address,
                            checkoutForm));
        }

        return "checkout/input";
    }

    @PostMapping("/confirm")
    public String confirm(
            @ModelAttribute("checkoutForm") CheckoutForm checkoutForm,
            BindingResult bindingResult,
            @ModelAttribute("cart") Cart cart,
            @AuthenticationPrincipal CustomUserDetails loginUser,
            RedirectAttributes redirectAttributes,
            Model model,
            HttpSession session) {

        if (CheckoutForm.SHIPPING_ADDRESS_MODE_REGISTERED
                .equals(checkoutForm.getShippingAddressMode())) {

            if (checkoutForm.getShippingAddressId() == null) {

                bindingResult.rejectValue(
                        "shippingAddressId",
                        "required",
                        "配送先を選択してください。");

            } else {

                ShippingAddress address = shippingAddressService.findByIdAndUserId(
                        checkoutForm.getShippingAddressId(),
                        loginUser.getId());

                copyShippingAddressToCheckoutForm(
                        address,
                        checkoutForm);
            }
        } else if (CheckoutForm.SHIPPING_ADDRESS_MODE_DIRECT
                .equals(checkoutForm.getShippingAddressMode())) {

            checkoutForm.setShippingAddressId(null);

        } else {

            bindingResult.rejectValue(
                    "shippingAddressMode",
                    "invalid",
                    "配送先の指定が正しくありません。");
        }

        validator.validate(
                checkoutForm,
                bindingResult);

        if (bindingResult.hasErrors()) {

            model.addAttribute(
                    "shippingAddresses",
                    shippingAddressService.findAllByUserId(
                            loginUser.getId()));

            return "checkout/input";
        }

        try {
            orderService.validateCart(cart);

        } catch (OrderValidationException e) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    e.getMessage());

            return "redirect:/cart";
        }

        OrderAmount orderAmount = orderService.calculateOrderAmount(cart);

        model.addAttribute(
                "orderAmount",
                orderAmount);

        String checkoutToken = UUID.randomUUID().toString();

        session.setAttribute(
                "checkoutToken",
                checkoutToken);

        model.addAttribute(
                "checkoutToken",
                checkoutToken);

        return "checkout/confirm";
    }

    @PostMapping("/order")
    public String placeOrder(
            @Valid @ModelAttribute("checkoutForm") CheckoutForm checkoutForm,
            BindingResult bindingResult,
            @ModelAttribute("cart") Cart cart,
            @AuthenticationPrincipal CustomUserDetails loginUser,
            @RequestParam(name = "checkoutToken", required = false) String checkoutToken,
            RedirectAttributes redirectAttributes,
            HttpSession session,
            SessionStatus sessionStatus) {

        if (!consumeCheckoutToken(
                session,
                checkoutToken)) {

            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "注文処理が既に実行されたか、"
                            + "確認画面の有効期限が切れています。");

            return "redirect:/cart";
        }

        if (bindingResult.hasErrors()) {
            return "checkout/input";
        }

        try {
            Order order = orderService.createOrder(
                    loginUser.getId(),
                    loginUser.getUsername(),
                    cart,
                    checkoutForm);

            PaymentAuthorizationPreparation preparation = paymentAuthorizationService.prepareAuthorization(order);

            session.setAttribute(
                    "checkoutOrderId",
                    order.getId());

            session.setAttribute(
                    "checkoutPaymentId",
                    preparation.paymentId());

            session.setAttribute(
                    "checkoutPaymentClientSecret",
                    preparation.clientSecret());

            return "redirect:/checkout/payment";

        } catch (OrderValidationException e) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    e.getMessage());

            return "redirect:/cart";
        }
    }

    @GetMapping("/payment")
    public String payment(
            HttpSession session,
            Model model) {

        Object orderId = session.getAttribute("checkoutOrderId");

        Object paymentId = session.getAttribute("checkoutPaymentId");

        Object clientSecret = session.getAttribute("checkoutPaymentClientSecret");

        if (!(orderId instanceof Long)
                || !(paymentId instanceof Long)
                || !(clientSecret instanceof String secret)
                || secret.isBlank()) {

            return "redirect:/";
        }

        model.addAttribute(
                "orderId",
                orderId);

        model.addAttribute(
                "clientSecret",
                secret);

        if (payJpProperties.publicKey() == null
                || payJpProperties.publicKey().isBlank()) {
            throw new IllegalStateException(
                    "PAY.JP公開鍵が設定されていません。");
        }

        model.addAttribute(
                "payjpPublicKey",
                payJpProperties.publicKey());

        return "checkout/payment";
    }

    @GetMapping("/payment/return")
    public String paymentReturn(
            HttpSession session,
            SessionStatus sessionStatus,
            RedirectAttributes redirectAttributes) {

        Object orderIdValue = session.getAttribute(
                "checkoutOrderId");

        Object paymentIdValue = session.getAttribute(
                "checkoutPaymentId");

        if (!(orderIdValue instanceof Long orderId)
                || !(paymentIdValue instanceof Long paymentId)) {

            return "redirect:/";
        }

        final AuthorizationResult result;

        try {
            result = paymentAuthorizationService
                    .refreshAuthorization(
                            orderId,
                            paymentId);

        } catch (PaymentGatewayException e) {

            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "決済状況を確認できませんでした。"
                            + "しばらくしてからもう一度お試しください。");

            return "redirect:/checkout/payment";
        }

        if (result.status() == AuthorizationResultStatus.AUTHORIZED) {

            clearCheckoutPaymentSession(session);

            sessionStatus.setComplete();

            redirectAttributes.addFlashAttribute(
                    "orderId",
                    orderId);

            return "redirect:/checkout/complete";
        }

        if (result.status() == AuthorizationResultStatus.FAILED) {

            clearCheckoutPaymentSession(session);

            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "カードの与信に失敗しました。"
                            + "カード情報をご確認のうえ、"
                            + "もう一度注文してください。");

            return "redirect:/cart";
        }

        if (result.status() == AuthorizationResultStatus.REQUIRES_ACTION) {

            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "カード認証が完了していません。"
                            + "認証を完了してください。");

            return "redirect:/checkout/payment";
        }

        redirectAttributes.addFlashAttribute(
                "errorMessage",
                "決済処理を確認中です。"
                        + "しばらくしてからもう一度お試しください。");

        return "redirect:/checkout/payment";
    }

    @GetMapping("/complete")
    public String complete(Model model) {

        if (!model.containsAttribute("orderId")) {
            return "redirect:/";
        }

        return "checkout/complete";
    }

    @GetMapping("/confirm")
    public String confirmByGet() {

        return "redirect:/checkout";
    }

    private boolean isCheckoutFormEmpty(
            CheckoutForm checkoutForm) {

        return isBlank(checkoutForm.getShippingName())
                && isBlank(checkoutForm.getShippingPostalCode())
                && isBlank(checkoutForm.getShippingPrefecture())
                && isBlank(checkoutForm.getShippingCity())
                && isBlank(checkoutForm.getShippingAddressLine())
                && isBlank(checkoutForm.getShippingPhone());
    }

    private boolean isBlank(String value) {

        return value == null || value.isBlank();
    }

    private void copyShippingAddressToCheckoutForm(
            ShippingAddress address,
            CheckoutForm checkoutForm) {

        checkoutForm.setShippingAddressId(
                address.getId());

        checkoutForm.setShippingAddressMode(
                CheckoutForm.SHIPPING_ADDRESS_MODE_REGISTERED);

        checkoutForm.setShippingName(
                address.getRecipientName());

        checkoutForm.setShippingPostalCode(
                address.getPostalCode());

        checkoutForm.setShippingPrefecture(
                address.getPrefecture());

        checkoutForm.setShippingCity(
                address.getCity());

        checkoutForm.setShippingAddressLine(
                address.getAddressLine());

        checkoutForm.setShippingPhone(
                address.getPhone());
    }

    private boolean consumeCheckoutToken(
            HttpSession session,
            String submittedToken) {

        if (submittedToken == null) {
            return false;
        }

        synchronized (session) {

            Object sessionToken = session.getAttribute(
                    "checkoutToken");

            if (!(sessionToken instanceof String)
                    || !sessionToken.equals(
                            submittedToken)) {

                return false;
            }

            session.removeAttribute(
                    "checkoutToken");

            return true;
        }
    }

    private void selectShippingAddress(
            ShippingAddress address,
            CheckoutForm checkoutForm) {

        checkoutForm.setShippingAddressMode(
                CheckoutForm.SHIPPING_ADDRESS_MODE_REGISTERED);

        checkoutForm.setShippingAddressId(
                address.getId());

        checkoutForm.setShippingName(null);
        checkoutForm.setShippingPostalCode(null);
        checkoutForm.setShippingPrefecture(null);
        checkoutForm.setShippingCity(null);
        checkoutForm.setShippingAddressLine(null);
        checkoutForm.setShippingPhone(null);
    }

    private void clearCheckoutShipping(
            CheckoutForm checkoutForm) {

        checkoutForm.setShippingAddressMode(null);
        checkoutForm.setShippingAddressId(null);

        checkoutForm.setShippingName(null);
        checkoutForm.setShippingPostalCode(null);
        checkoutForm.setShippingPrefecture(null);
        checkoutForm.setShippingCity(null);
        checkoutForm.setShippingAddressLine(null);
        checkoutForm.setShippingPhone(null);
    }

    private void clearCheckoutPaymentSession(
            HttpSession session) {

        session.removeAttribute(
                "checkoutOrderId");

        session.removeAttribute(
                "checkoutPaymentId");

        session.removeAttribute(
                "checkoutPaymentClientSecret");
    }

}
