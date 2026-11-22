package com.example.ecsite.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.example.ecsite.payment.savedcard.SavedCardGatewayException;
import com.example.ecsite.security.CustomUserDetails;
import com.example.ecsite.service.savedcard.SavedCardOperationStore;
import com.example.ecsite.service.savedcard.SavedCardService;

/** Feature139-A: saved cards on the my page (list, register through a Setup Flow, delete, choose the default). */
@Controller
@RequestMapping("/mypage/cards")
public class SavedCardController {

    static final String UNAVAILABLE = "現在、カードの登録はご利用いただけません。";
    static final String PROVIDER_FAILED = "カード情報を取得・更新できませんでした。時間をおいて、カード一覧で結果をご確認ください。";
    static final String BUSY = "前回の操作の結果を確認しています。しばらくしてから、もう一度お試しください。";
    static final String LIMIT = "登録できるカードは " + SavedCardService.MAX_CARDS + " 枚までです。";
    static final String NOT_FOUND = "指定されたカードが見つかりません。";

    private static final Logger log = LoggerFactory.getLogger(SavedCardController.class);

    private final SavedCardService cards;

    public SavedCardController(SavedCardService cards) {
        this.cards = cards;
    }

    @GetMapping
    public String list(@AuthenticationPrincipal CustomUserDetails user, Model model) {
        model.addAttribute("maxCards", SavedCardService.MAX_CARDS);
        if (!cards.enabled()) {
            model.addAttribute("unavailableMessage", UNAVAILABLE);
            return "mypage/cards";
        }
        try {
            model.addAttribute("view", cards.cards(user.getId()));
        } catch (SavedCardGatewayException failure) {
            log.warn("Saved cards could not be read: {}", failure.getMessage());
            model.addAttribute("unavailableMessage", PROVIDER_FAILED);
        }
        return "mypage/cards";
    }

    /** Starts a Setup Flow and shows the payments.js registration form. The client secret is never stored. */
    @PostMapping("/new")
    public String register(@AuthenticationPrincipal CustomUserDetails user, Model model, RedirectAttributes redirect) {
        try {
            var start = cards.startRegistration(user.getId());
            model.addAttribute("card", new RegistrationCard(cards.publicKey(), start.clientSecret()));
            return "mypage/card-new";
        } catch (RuntimeException failure) {
            redirect.addFlashAttribute("errorMessage", message(failure));
            return "redirect:/mypage/cards";
        }
    }

    /** Return URL of {@code widgets.confirmSetup}. Shows a page that submits the result check by POST. */
    @GetMapping("/return")
    public String returned(@RequestParam(name = "setup_flow_id", required = false) String setupFlowId, Model model) {
        model.addAttribute("setupFlowId", setupFlowId);
        return "mypage/card-return";
    }

    @PostMapping("/complete")
    public String complete(@AuthenticationPrincipal CustomUserDetails user,
            @RequestParam(name = "setupFlowId", required = false) String setupFlowId, RedirectAttributes redirect) {
        try {
            switch (cards.completeRegistration(user.getId(), setupFlowId)) {
                case REGISTERED -> redirect.addFlashAttribute("successMessage", "カードを登録しました。");
                case DUPLICATE -> redirect.addFlashAttribute("errorMessage", "このカードはすでに登録されています。");
                case LIMIT -> redirect.addFlashAttribute("errorMessage", LIMIT);
                case NOT_COMPLETED -> redirect.addFlashAttribute("errorMessage",
                        "カードの登録が完了していません。もう一度、最初から登録してください。");
                case UNKNOWN_FLOW -> redirect.addFlashAttribute("errorMessage", "登録の手続きが見つかりません。");
            }
        } catch (RuntimeException failure) {
            redirect.addFlashAttribute("errorMessage", message(failure));
        }
        return "redirect:/mypage/cards";
    }

    @PostMapping("/{paymentMethodId}/delete")
    public String delete(@AuthenticationPrincipal CustomUserDetails user, @PathVariable String paymentMethodId,
            RedirectAttributes redirect) {
        try {
            cards.delete(user.getId(), paymentMethodId);
            redirect.addFlashAttribute("successMessage", "カードを削除しました。");
        } catch (RuntimeException failure) {
            redirect.addFlashAttribute("errorMessage", message(failure));
        }
        return "redirect:/mypage/cards";
    }

    @PostMapping("/{paymentMethodId}/default")
    public String makeDefault(@AuthenticationPrincipal CustomUserDetails user, @PathVariable String paymentMethodId,
            RedirectAttributes redirect) {
        try {
            cards.setDefault(user.getId(), paymentMethodId);
            redirect.addFlashAttribute("successMessage", "既定のカードを変更しました。");
        } catch (RuntimeException failure) {
            redirect.addFlashAttribute("errorMessage", message(failure));
        }
        return "redirect:/mypage/cards";
    }

    private static String message(RuntimeException failure) {
        if (failure instanceof SavedCardService.UnavailableException) return UNAVAILABLE;
        if (failure instanceof SavedCardOperationStore.BusyException) return BUSY;
        if (failure instanceof SavedCardService.LimitException) return LIMIT;
        if (failure instanceof SavedCardService.NotOwnedException) return NOT_FOUND;
        if (failure instanceof SavedCardGatewayException gateway) {
            log.warn("Saved card call failed: {}", gateway.getMessage());
            return PROVIDER_FAILED;
        }
        throw failure;
    }

    public record RegistrationCard(String publicKey, String clientSecret) {
        @Override public String toString() { return "RegistrationCard[redacted]"; }
    }
}
