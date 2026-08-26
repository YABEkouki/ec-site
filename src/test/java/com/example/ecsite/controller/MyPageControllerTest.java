package com.example.ecsite.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.example.ecsite.entity.ShippingAddress;
import com.example.ecsite.entity.UserProfile;
import com.example.ecsite.form.ShippingAddressForm;
import com.example.ecsite.form.UserProfileForm;
import com.example.ecsite.security.CustomUserDetails;
import com.example.ecsite.service.ShippingAddressService;
import com.example.ecsite.service.UserProfileService;

@ExtendWith(MockitoExtension.class)
class MyPageControllerTest {

        @Mock
        private UserProfileService userProfileService;

        @Mock
        private ShippingAddressService shippingAddressService;

        @Mock
        private Model model;

        private MyPageController controller;

        @BeforeEach
        void setUp() {

                controller = new MyPageController(
                                userProfileService,
                                shippingAddressService);
        }

        @Test
        void indexDisplaysProfileAndAddressesForLoggedInUser() {

                CustomUserDetails loginUser = mock(CustomUserDetails.class);

                when(loginUser.getId()).thenReturn(10L);

                UserProfile profile = new UserProfile();
                List<ShippingAddress> addresses = List.of(new ShippingAddress());

                when(userProfileService.findByUserId(10L))
                                .thenReturn(profile);

                when(shippingAddressService.findAllByUserId(10L))
                                .thenReturn(addresses);

                String viewName = controller.index(loginUser, model);

                assertEquals("mypage/index", viewName);

                verify(model).addAttribute("profile", profile);
                verify(model).addAttribute("addresses", addresses);
        }

        @Test
        void editProfileDisplaysCurrentProfileForm() {

                CustomUserDetails loginUser = mock(CustomUserDetails.class);

                when(loginUser.getId()).thenReturn(10L);

                UserProfileForm form = new UserProfileForm();

                when(userProfileService.createForm(10L))
                                .thenReturn(form);

                String viewName = controller.editProfile(loginUser, model);

                assertEquals(
                                "mypage/profile-form",
                                viewName);

                verify(model)
                                .addAttribute("userProfileForm", form);
        }

        @Test
        void updateProfileSavesLoggedInUsersProfile() {

                CustomUserDetails loginUser = mock(CustomUserDetails.class);

                when(loginUser.getId()).thenReturn(10L);

                UserProfileForm form = new UserProfileForm();

                BindingResult bindingResult = mock(BindingResult.class);

                when(bindingResult.hasErrors())
                                .thenReturn(false);

                RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

                String viewName = controller.updateProfile(
                                form,
                                bindingResult,
                                loginUser,
                                redirectAttributes);

                assertEquals(
                                "redirect:/mypage",
                                viewName);

                verify(userProfileService)
                                .save(10L, form);

                verify(redirectAttributes)
                                .addFlashAttribute(
                                                "successMessage",
                                                "会員情報を更新しました。");
        }

        @Test
        void updateProfileReturnsFormWhenValidationFails() {

                CustomUserDetails loginUser = mock(CustomUserDetails.class);

                UserProfileForm form = new UserProfileForm();

                BindingResult bindingResult = mock(BindingResult.class);

                when(bindingResult.hasErrors())
                                .thenReturn(true);

                RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

                String viewName = controller.updateProfile(
                                form,
                                bindingResult,
                                loginUser,
                                redirectAttributes);

                assertEquals(
                                "mypage/profile-form",
                                viewName);
        }

        @Test
        void newAddressDisplaysEmptyFormAndAddressStatus() {

                CustomUserDetails loginUser = mock(CustomUserDetails.class);

                when(loginUser.getId()).thenReturn(10L);

                when(shippingAddressService.hasAddress(10L))
                                .thenReturn(true);

                String viewName = controller.newAddress(
                                loginUser,
                                model);

                assertEquals(
                                "mypage/address-form",
                                viewName);

                verify(model)
                                .addAttribute(
                                                org.mockito.ArgumentMatchers.eq(
                                                                "shippingAddressForm"),
                                                org.mockito.ArgumentMatchers
                                                                .any(ShippingAddressForm.class));

                verify(model)
                                .addAttribute(
                                                "hasAddress",
                                                true);

                verify(shippingAddressService)
                                .hasAddress(10L);
        }

        @Test
        void createAddressUsesLoggedInUserId() {

                CustomUserDetails loginUser = mock(CustomUserDetails.class);

                when(loginUser.getId()).thenReturn(10L);

                ShippingAddressForm form = new ShippingAddressForm();

                BindingResult bindingResult = mock(BindingResult.class);

                when(bindingResult.hasErrors())
                                .thenReturn(false);

                RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

                String viewName = controller.createAddress(
                                form,
                                bindingResult,
                                loginUser,
                                redirectAttributes);

                assertEquals(
                                "redirect:/mypage",
                                viewName);

                verify(shippingAddressService)
                                .create(10L, form);
        }

        @Test
        void editAddressUsesAddressIdAndLoggedInUserId() {

                CustomUserDetails loginUser = mock(CustomUserDetails.class);

                when(loginUser.getId()).thenReturn(10L);

                ShippingAddressForm form = new ShippingAddressForm();

                when(shippingAddressService
                                .createForm(20L, 10L))
                                .thenReturn(form);

                String viewName = controller.editAddress(
                                20L,
                                loginUser,
                                model);

                assertEquals(
                                "mypage/address-form",
                                viewName);

                verify(shippingAddressService)
                                .createForm(20L, 10L);

                verify(model)
                                .addAttribute(
                                                "shippingAddressForm",
                                                form);

                verify(model)
                                .addAttribute(
                                                "addressId",
                                                20L);
        }

        @Test
        void updateAddressUsesAddressIdAndLoggedInUserId() {

                CustomUserDetails loginUser = mock(CustomUserDetails.class);

                when(loginUser.getId()).thenReturn(10L);

                ShippingAddressForm form = new ShippingAddressForm();

                BindingResult bindingResult = mock(BindingResult.class);

                when(bindingResult.hasErrors())
                                .thenReturn(false);

                RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

                String viewName = controller.updateAddress(
                                20L,
                                form,
                                bindingResult,
                                loginUser,
                                redirectAttributes);

                assertEquals(
                                "redirect:/mypage",
                                viewName);

                verify(shippingAddressService)
                                .update(20L, 10L, form);
        }
}