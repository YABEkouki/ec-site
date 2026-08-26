package com.example.ecsite.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.example.ecsite.form.ShippingAddressForm;
import com.example.ecsite.form.UserProfileForm;
import com.example.ecsite.security.CustomUserDetails;
import com.example.ecsite.service.ShippingAddressService;
import com.example.ecsite.service.UserProfileService;

import jakarta.validation.Valid;

@Controller
public class MyPageController {

        private final UserProfileService userProfileService;
        private final ShippingAddressService shippingAddressService;

        public MyPageController(
                        UserProfileService userProfileService,
                        ShippingAddressService shippingAddressService) {

                this.userProfileService = userProfileService;
                this.shippingAddressService = shippingAddressService;
        }

        @GetMapping("/mypage")
        public String index(
                        @AuthenticationPrincipal CustomUserDetails loginUser,
                        Model model) {

                Long userId = loginUser.getId();

                model.addAttribute(
                                "profile",
                                userProfileService.findByUserId(userId));

                model.addAttribute(
                                "addresses",
                                shippingAddressService.findAllByUserId(userId));

                return "mypage/index";
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
}