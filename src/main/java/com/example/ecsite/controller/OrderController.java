package com.example.ecsite.controller;

import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.Validator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.example.ecsite.dto.OrderItemChangePreview;
import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.ShippingAddress;
import com.example.ecsite.exception.InvalidOrderStatusException;
import com.example.ecsite.form.OrderItemChangeForm;
import com.example.ecsite.form.OrderShippingAddressForm;
import com.example.ecsite.security.CustomUserDetails;
import com.example.ecsite.service.OrderContentChangeHistoryService;
import com.example.ecsite.service.OrderService;
import com.example.ecsite.service.OrderShippingAddressHistoryService;
import com.example.ecsite.service.OrderStatusHistoryService;
import com.example.ecsite.service.ShippingAddressService;

@Controller
@RequestMapping("/orders")
public class OrderController {

    private final OrderService orderService;
    private final OrderStatusHistoryService orderStatusHistoryService;
    private final ShippingAddressService shippingAddressService;
    private final OrderShippingAddressHistoryService orderShippingAddressHistoryService;
    private final OrderContentChangeHistoryService orderContentChangeHistoryService;
    private final Validator validator;

    public OrderController(
            OrderService orderService,
            OrderStatusHistoryService orderStatusHistoryService,
            ShippingAddressService shippingAddressService,
            OrderShippingAddressHistoryService orderShippingAddressHistoryService,
            OrderContentChangeHistoryService orderContentChangeHistoryService,
            Validator validator) {

        this.orderService = orderService;
        this.orderStatusHistoryService = orderStatusHistoryService;
        this.shippingAddressService = shippingAddressService;
        this.orderShippingAddressHistoryService = orderShippingAddressHistoryService;
        this.orderContentChangeHistoryService = orderContentChangeHistoryService;
        this.validator = validator;
    }

    @GetMapping
    public String list(
            @AuthenticationPrincipal CustomUserDetails loginUser,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            Model model) {

        int safePage = Math.max(page, 0);
        int safeSize = Math.clamp(size, 1, 100);

        Page<Order> orderPage = orderService.findOrdersByUserId(
                loginUser.getId(),
                safePage,
                safeSize);

        model.addAttribute(
                "orders", orderPage.getContent());

        model.addAttribute(
                "orderPage", orderPage);

        return "orders/list";
    }

    @GetMapping("/{id}")
    public String detail(
            @PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails loginUser,
            Model model) {

        Order order = orderService.findOrderByIdAndUserId(
                id,
                loginUser.getId());

        model.addAttribute("order", order);

        model.addAttribute(
                "withinModificationPeriod",
                orderService.isWithinModificationPeriod(order));

        model.addAttribute(
                "canCancelByUser",
                orderService.canCancelByUser(order));

        model.addAttribute(
                "canChangeItemsByUser",
                orderService.canChangeItemsByUser(order));

        model.addAttribute(
                "statusHistories",
                orderStatusHistoryService.findByOrderId(id));

        model.addAttribute(
                "shippingAddressHistories",
                orderShippingAddressHistoryService.findByOrderId(id));

        model.addAttribute(
                "canChangeShippingAddress",
                orderService.canChangeShippingAddress(order));

        return "orders/detail";
    }

    @GetMapping("/{id}/content-changes")
    public String contentChangeHistory(
            @PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails loginUser,
            Model model) {

        Order order = orderService.findOrderByIdAndUserId(
                id,
                loginUser.getId());

        model.addAttribute(
                "order",
                order);

        model.addAttribute(
                "contentChangeHistories",
                orderContentChangeHistoryService.findByOrderId(id));

        return "orders/content-change-history";
    }

    @GetMapping("/{id}/cancel")
    public String cancelConfirm(
            @PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails loginUser,
            Model model,
            RedirectAttributes redirectAttributes) {

        Order order = orderService.findOrderByIdAndUserId(
                id,
                loginUser.getId());

        if (!orderService.canCancelByUser(order)) {

            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "現在、この注文はキャンセルできません。");

            return "redirect:/orders/" + id;
        }

        model.addAttribute(
                "order",
                order);

        return "orders/cancel-confirm";
    }

    @PostMapping("/{id}/cancel")
    public String cancel(
            @PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails loginUser,
            RedirectAttributes redirectAttributes) {

        try {
            orderService.cancelOrderForUser(
                    id,
                    loginUser.getId(),
                    loginUser.getUsername());
            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "注文をキャンセルしました。");

        } catch (InvalidOrderStatusException e) {

            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    e.getMessage());
        }

        return "redirect:/orders/" + id;
    }

    @GetMapping("/{id}/items")
    public String itemChangeInput(
            @PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails loginUser,
            Model model,
            RedirectAttributes redirectAttributes) {

        Order order = orderService.findOrderByIdAndUserId(
                id,
                loginUser.getId());

        if (!orderService.canChangeItemsByUser(order)) {

            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "現在、この注文の商品内容は変更できません。");

            model.addAttribute(
                    "canCancelByUser",
                    orderService.canCancelByUser(order));

            return "redirect:/orders/" + id;
        }

        OrderItemChangeForm form = createOrderItemChangeForm(order);

        model.addAttribute(
                "order",
                order);

        model.addAttribute(
                "orderItemChangeForm",
                form);

        return "orders/item-change";
    }

    @PostMapping("/{id}/items/confirm")
    public String itemChangeConfirm(
            @PathVariable Long id,
            @ModelAttribute("orderItemChangeForm") OrderItemChangeForm form,
            BindingResult bindingResult,
            @AuthenticationPrincipal CustomUserDetails loginUser,
            Model model,
            RedirectAttributes redirectAttributes) {

        Order order = orderService.findOrderByIdAndUserId(
                id,
                loginUser.getId());

        if (!orderService.canChangeItemsByUser(order)) {

            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "現在、この注文の商品内容は変更できません。");

            model.addAttribute(
                    "canCancelByUser",
                    orderService.canCancelByUser(order));

            return "redirect:/orders/" + id;
        }

        validator.validate(
                form,
                bindingResult);

        if (bindingResult.hasErrors()) {

            model.addAttribute(
                    "order",
                    order);

            model.addAttribute(
                    "canCancelByUser",
                    orderService.canCancelByUser(order));

            return "orders/item-change";
        }

        try {

            OrderItemChangePreview preview = orderService.previewItemChangeForUser(
                    id,
                    loginUser.getId(),
                    form);

            if (!preview.isChanged()) {

                bindingResult.reject(
                        "unchanged",
                        "注文内容に変更がありません。");

                model.addAttribute(
                        "order",
                        order);

                return "orders/item-change";
            }

            model.addAttribute(
                    "order",
                    order);

            model.addAttribute(
                    "preview",
                    preview);

            return "orders/item-change-confirm";

        } catch (IllegalArgumentException
                | IllegalStateException
                | InvalidOrderStatusException e) {

            bindingResult.reject(
                    "itemChange",
                    e.getMessage());

            model.addAttribute(
                    "order",
                    order);

            model.addAttribute(
                    "canCancelByUser",
                    orderService.canCancelByUser(order));

            return "orders/item-change";
        }
    }

    @PostMapping("/{id}/items/back")
    public String itemChangeBack(
            @PathVariable Long id,
            @ModelAttribute("orderItemChangeForm") OrderItemChangeForm form,
            @AuthenticationPrincipal CustomUserDetails loginUser,
            Model model,
            RedirectAttributes redirectAttributes) {

        Order order = orderService.findOrderByIdAndUserId(
                id,
                loginUser.getId());

        if (!orderService.canChangeItemsByUser(order)) {

            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "現在、この注文の商品内容は変更できません。");

            model.addAttribute(
                    "canCancelByUser",
                    orderService.canCancelByUser(order));

            return "redirect:/orders/" + id;
        }

        model.addAttribute(
                "order",
                order);

        model.addAttribute(
                "canCancelByUser",
                orderService.canCancelByUser(order));

        return "orders/item-change";
    }

    @PostMapping("/{id}/items")
    public String changeItems(
            @PathVariable Long id,
            @ModelAttribute("orderItemChangeForm") OrderItemChangeForm form,
            BindingResult bindingResult,
            @AuthenticationPrincipal CustomUserDetails loginUser,
            RedirectAttributes redirectAttributes) {

        validator.validate(
                form,
                bindingResult);

        if (bindingResult.hasErrors()) {

            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "注文内容が正しくありません。もう一度入力してください。");

            return "redirect:/orders/" + id + "/items";
        }

        try {

            boolean changed = orderService.changeItemsForUser(
                    id,
                    loginUser.getId(),
                    loginUser.getUsername(),
                    form);

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    changed
                            ? "注文内容を変更しました。"
                            : "注文内容に変更はありません。");

        } catch (IllegalArgumentException
                | IllegalStateException
                | InvalidOrderStatusException e) {

            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    e.getMessage());
        }

        return "redirect:/orders/" + id;
    }

    @GetMapping("/{id}/shipping-address")
    public String shippingAddressInput(
            @PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails loginUser,
            Model model,
            RedirectAttributes redirectAttributes) {

        Order order = orderService.findOrderByIdAndUserId(
                id,
                loginUser.getId());

        if (!orderService.canChangeShippingAddress(order)) {

            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "現在、この注文の配送先は変更できません。");

            return "redirect:/orders/" + id;
        }

        OrderShippingAddressForm form = new OrderShippingAddressForm();

        form.setShippingAddressMode(
                OrderShippingAddressForm.SHIPPING_ADDRESS_MODE_DIRECT);

        form.setShippingName(
                order.getShippingName());

        form.setShippingPostalCode(
                order.getShippingPostalCode());

        form.setShippingPrefecture(
                order.getShippingPrefecture());

        form.setShippingCity(
                order.getShippingCity());

        form.setShippingAddressLine(
                order.getShippingAddressLine());

        form.setShippingPhone(
                order.getShippingPhone());

        model.addAttribute(
                "order",
                order);

        model.addAttribute(
                "orderShippingAddressForm",
                form);

        model.addAttribute(
                "shippingAddresses",
                shippingAddressService.findAllByUserId(
                        loginUser.getId()));

        return "orders/shipping-address";
    }

    @PostMapping("/{id}/shipping-address/confirm")
    public String shippingAddressConfirm(
            @PathVariable Long id,
            @ModelAttribute("orderShippingAddressForm") OrderShippingAddressForm form,
            BindingResult bindingResult,
            @AuthenticationPrincipal CustomUserDetails loginUser,
            Model model,
            RedirectAttributes redirectAttributes) {

        Order order = orderService.findOrderByIdAndUserId(
                id,
                loginUser.getId());

        if (!orderService.canChangeShippingAddress(order)) {

            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "現在、この注文の配送先は変更できません。");

            return "redirect:/orders/" + id;
        }

        if (OrderShippingAddressForm.SHIPPING_ADDRESS_MODE_REGISTERED
                .equals(form.getShippingAddressMode())) {

            if (form.getShippingAddressId() == null) {

                bindingResult.rejectValue(
                        "shippingAddressId",
                        "required",
                        "配送先を選択してください。");

            } else {

                ShippingAddress address = shippingAddressService.findByIdAndUserId(
                        form.getShippingAddressId(),
                        loginUser.getId());

                copyShippingAddressToOrderForm(
                        address,
                        form);
            }

        } else if (OrderShippingAddressForm.SHIPPING_ADDRESS_MODE_DIRECT
                .equals(form.getShippingAddressMode())) {

            form.setShippingAddressId(null);

        } else {

            bindingResult.rejectValue(
                    "shippingAddressMode",
                    "invalid",
                    "配送先の指定が正しくありません。");
        }

        validator.validate(
                form,
                bindingResult);

        if (bindingResult.hasErrors()) {

            model.addAttribute(
                    "order",
                    order);

            model.addAttribute(
                    "shippingAddresses",
                    shippingAddressService.findAllByUserId(
                            loginUser.getId()));

            return "orders/shipping-address";
        }

        model.addAttribute(
                "order",
                order);

        return "orders/shipping-address-confirm";
    }

    @PostMapping("/{id}/shipping-address/back")
    public String shippingAddressBack(
            @PathVariable Long id,
            @ModelAttribute("orderShippingAddressForm") OrderShippingAddressForm form,
            @AuthenticationPrincipal CustomUserDetails loginUser,
            Model model,
            RedirectAttributes redirectAttributes) {

        Order order = orderService.findOrderByIdAndUserId(
                id,
                loginUser.getId());

        if (!orderService.canChangeShippingAddress(order)) {

            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "現在、この注文の配送先は変更できません。");

            return "redirect:/orders/" + id;
        }

        model.addAttribute(
                "order",
                order);

        model.addAttribute(
                "shippingAddresses",
                shippingAddressService.findAllByUserId(
                        loginUser.getId()));

        return "orders/shipping-address";
    }

    @PostMapping("/{id}/shipping-address")
    public String changeShippingAddress(
            @PathVariable Long id,
            @ModelAttribute("orderShippingAddressForm") OrderShippingAddressForm form,
            BindingResult bindingResult,
            @AuthenticationPrincipal CustomUserDetails loginUser,
            RedirectAttributes redirectAttributes) {

        validator.validate(
                form,
                bindingResult);

        if (bindingResult.hasErrors()) {

            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "配送先情報が正しくありません。もう一度入力してください。");

            return "redirect:/orders/" + id + "/shipping-address";
        }

        try {

            boolean changed = orderService.changeShippingAddressForUser(
                    id,
                    loginUser.getId(),
                    loginUser.getUsername(),
                    form);

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    changed
                            ? "配送先を変更しました。"
                            : "配送先に変更はありません。");

        } catch (InvalidOrderStatusException e) {

            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    e.getMessage());
        }

        return "redirect:/orders/" + id;
    }

    private void copyShippingAddressToOrderForm(
            ShippingAddress address,
            OrderShippingAddressForm form) {

        form.setShippingAddressId(
                address.getId());

        form.setShippingAddressMode(
                OrderShippingAddressForm.SHIPPING_ADDRESS_MODE_REGISTERED);

        form.setShippingName(
                address.getRecipientName());

        form.setShippingPostalCode(
                address.getPostalCode());

        form.setShippingPrefecture(
                address.getPrefecture());

        form.setShippingCity(
                address.getCity());

        form.setShippingAddressLine(
                address.getAddressLine());

        form.setShippingPhone(
                address.getPhone());
    }

    private OrderItemChangeForm createOrderItemChangeForm(
            Order order) {

        OrderItemChangeForm form = new OrderItemChangeForm();

        form.setContentRevision(
                order.getContentRevision());

        for (var orderItem : order.getItems()) {

            OrderItemChangeForm.Item item = new OrderItemChangeForm.Item();

            item.setOrderItemId(
                    orderItem.getId());

            item.setQuantity(
                    orderItem.getQuantity());

            form.getItems().add(item);
        }

        return form;
    }

}
