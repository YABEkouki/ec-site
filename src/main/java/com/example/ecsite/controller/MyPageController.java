package com.example.ecsite.controller;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.example.ecsite.exception.EmailAlreadyExistsException;
import com.example.ecsite.exception.IncorrectCurrentPasswordException;
import com.example.ecsite.exception.SameAsCurrentPasswordException;
import com.example.ecsite.exception.UsernameAlreadyExistsException;
import com.example.ecsite.form.PasswordChangeForm;
import com.example.ecsite.form.ShippingAddressForm;
import com.example.ecsite.form.UserAccountEditForm;
import com.example.ecsite.form.UserProfileForm;
import com.example.ecsite.security.CustomUserDetails;
import com.example.ecsite.service.ShippingAddressService;
import com.example.ecsite.service.UserProfileService;
import com.example.ecsite.service.UserService;

import jakarta.validation.Valid;

@Controller
public class MyPageController {

    private final UserProfileService userProfileService;
    private final ShippingAddressService shippingAddressService;
    private final UserService userService;

    public MyPageController(
            UserProfileService userProfileService,
            ShippingAddressService shippingAddressService,
            UserService userService) {

        this.userProfileService = userProfileService;
        this.shippingAddressService = shippingAddressService;
        this.userService = userService;
    }

    @GetMapping("/mypage")
    public String index(
            @AuthenticationPrincipal CustomUserDetails loginUser,
            Model model) {

        Long userId = loginUser.getId();

        model.addAttribute(
                "account",
                userService.getAccountInfo(userId));

        model.addAttribute(
                "profile",
                userProfileService.findByUserId(userId));

        model.addAttribute(
                "addresses",
                shippingAddressService.findAllByUserId(userId));

        return "mypage/index";
    }

    @GetMapping("/mypage/account/edit")
    public String editAccount(
            @AuthenticationPrincipal CustomUserDetails loginUser,
            Model model) {

        model.addAttribute(
                "userAccountEditForm",
                userService.createAccountEditForm(loginUser.getId()));

        return "mypage/account-form";
    }

    @PostMapping("/mypage/account")
    public String updateAccount(
            @Valid @ModelAttribute("userAccountEditForm") UserAccountEditForm userAccountEditForm,
            BindingResult bindingResult,
            @AuthenticationPrincipal CustomUserDetails loginUser,
            RedirectAttributes redirectAttributes) {

        if (bindingResult.hasErrors()) {
            return "mypage/account-form";
        }

        String updatedUsername;

        try {
            updatedUsername = userService.updateAccount(
                    loginUser.getId(),
                    userAccountEditForm.getUsername(),
                    userAccountEditForm.getEmail());
        } catch (UsernameAlreadyExistsException e) {

            bindingResult.rejectValue(
                    "username",
                    "duplicate",
                    "ユーザー名は既に使用されています。");
            return "mypage/account-form";
        } catch (EmailAlreadyExistsException e) {

            bindingResult.rejectValue(
                    "email",
                    "duplicate",
                    "メールアドレスは既に使用されています。");
            return "mypage/account-form";
        }

        refreshAuthenticationUsername(loginUser, updatedUsername);

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "アカウント情報を更新しました。");

        return "redirect:/mypage";
    }

    @GetMapping("/mypage/password/edit")
    public String editPassword(Model model) {

        model.addAttribute(
                "passwordChangeForm",
                new PasswordChangeForm());

        return "mypage/password-form";
    }

    @PostMapping("/mypage/password")
    public String changePassword(
            @Valid @ModelAttribute("passwordChangeForm") PasswordChangeForm passwordChangeForm,
            BindingResult bindingResult,
            @AuthenticationPrincipal CustomUserDetails loginUser,
            RedirectAttributes redirectAttributes) {

        if (bindingResult.hasErrors()) {
            return "mypage/password-form";
        }

        if (!passwordChangeForm.getNewPassword()
                .equals(passwordChangeForm.getConfirmPassword())) {

            bindingResult.rejectValue(
                    "confirmPassword",
                    "mismatch",
                    "新しいパスワードと確認用パスワードが一致しません。");

            return "mypage/password-form";
        }

        Long userId = loginUser.getId();

        try {

            userService.changePassword(
                    userId,
                    passwordChangeForm.getCurrentPassword(),
                    passwordChangeForm.getNewPassword());

        } catch (IncorrectCurrentPasswordException e) {

            bindingResult.rejectValue(
                    "currentPassword",
                    "incorrect",
                    e.getMessage());

            return "mypage/password-form";

        } catch (SameAsCurrentPasswordException e) {

            bindingResult.rejectValue(
                    "newPassword",
                    "sameAsCurrent",
                    e.getMessage());

            return "mypage/password-form";
        }

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "パスワードを変更しました。");

        return "redirect:/mypage";

    }

    @GetMapping("/mypage/profile/edit")
    public String editProfile(
            @AuthenticationPrincipal CustomUserDetails loginUser,
            Model model) {

        model.addAttribute(
                "userProfileForm",
                userProfileService.createForm(loginUser.getId()));

        return "mypage/profile-form";
    }

    @PostMapping("/mypage/profile")
    public String updateProfile(
            @Valid @ModelAttribute("userProfileForm") UserProfileForm userProfileForm,
            BindingResult bindingResult,
            @AuthenticationPrincipal CustomUserDetails loginUser,
            RedirectAttributes redirectAttributes) {

        if (bindingResult.hasErrors()) {
            return "mypage/profile-form";
        }

        userProfileService.save(
                loginUser.getId(),
                userProfileForm);

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "会員情報を更新しました。");

        return "redirect:/mypage";
    }

    @GetMapping("/mypage/addresses/new")
    public String newAddress(@AuthenticationPrincipal CustomUserDetails loginUser, Model model) {

        model.addAttribute(
                "shippingAddressForm",
                new ShippingAddressForm());

        model.addAttribute(
                "hasAddress",
                shippingAddressService.hasAddress(
                        loginUser.getId()));

        return "mypage/address-form";
    }

    @PostMapping("/mypage/addresses")
    public String createAddress(
            @Valid @ModelAttribute("shippingAddressForm") ShippingAddressForm shippingAddressForm,
            BindingResult bindingResult,
            @AuthenticationPrincipal CustomUserDetails loginUser,
            RedirectAttributes redirectAttributes) {

        if (bindingResult.hasErrors()) {
            return "mypage/address-form";
        }

        shippingAddressService.create(
                loginUser.getId(),
                shippingAddressForm);

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "配送先を登録しました。");

        return "redirect:/mypage";
    }

    @GetMapping("/mypage/addresses/{addressId}/edit")
    public String editAddress(
            @PathVariable Long addressId,
            @AuthenticationPrincipal CustomUserDetails loginUser,
            Model model) {

        model.addAttribute(
                "shippingAddressForm",
                shippingAddressService.createForm(
                        addressId,
                        loginUser.getId()));

        model.addAttribute(
                "addressId",
                addressId);

        return "mypage/address-form";
    }

    @PostMapping("/mypage/addresses/{addressId}")
    public String updateAddress(
            @PathVariable Long addressId,
            @Valid @ModelAttribute("shippingAddressForm") ShippingAddressForm shippingAddressForm,
            BindingResult bindingResult,
            @AuthenticationPrincipal CustomUserDetails loginUser,
            RedirectAttributes redirectAttributes) {

        if (bindingResult.hasErrors()) {
            return "mypage/address-form";
        }

        shippingAddressService.update(
                addressId,
                loginUser.getId(),
                shippingAddressForm);

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "配送先を更新しました。");

        return "redirect:/mypage";
    }

    private void refreshAuthenticationUsername(
            CustomUserDetails loginUser,
            String updatedUsername) {

        if (loginUser.getUsername().equals(updatedUsername)) {
            return;
        }

        Authentication currentAuthentication = SecurityContextHolder.getContext().getAuthentication();

        CustomUserDetails updatedPrincipal = new CustomUserDetails(
                loginUser.getId(),
                updatedUsername,
                loginUser.getPassword(),
                loginUser.isEnabled(),
                loginUser.getAuthorities());

        UsernamePasswordAuthenticationToken updatedAuthentication = new UsernamePasswordAuthenticationToken(
                updatedPrincipal,
                currentAuthentication.getCredentials(),
                updatedPrincipal.getAuthorities());

        updatedAuthentication.setDetails(currentAuthentication.getDetails());

        SecurityContextHolder.getContext()
                .setAuthentication(updatedAuthentication);
    }

}
