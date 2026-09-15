package com.example.ecsite.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.example.ecsite.dto.UserAccountInfo;
import com.example.ecsite.entity.ShippingAddress;
import com.example.ecsite.entity.UserProfile;
import com.example.ecsite.exception.IncorrectCurrentPasswordException;
import com.example.ecsite.exception.SameAsCurrentPasswordException;
import com.example.ecsite.form.PasswordChangeForm;
import com.example.ecsite.form.ShippingAddressForm;
import com.example.ecsite.form.UserProfileForm;
import com.example.ecsite.security.CustomUserDetails;
import com.example.ecsite.service.ShippingAddressService;
import com.example.ecsite.service.UserProfileService;
import com.example.ecsite.service.UserService;

@ExtendWith(MockitoExtension.class)
class MyPageControllerTest {

    @Mock
    private UserProfileService userProfileService;

    @Mock
    private ShippingAddressService shippingAddressService;

    @Mock
    private Model model;

    @Mock
    private UserService userService;

    private MyPageController controller;

    @BeforeEach
    void setUp() {

        controller = new MyPageController(
                userProfileService,
                shippingAddressService,
                userService);
    }

    @Test
    void indexDisplaysProfileAndAddressesForLoggedInUser() {

        CustomUserDetails loginUser = mock(CustomUserDetails.class);

        when(loginUser.getId()).thenReturn(10L);

        UserAccountInfo accountInfo = new UserAccountInfo(
                "user1",
                LocalDateTime.of(2026, 9, 1, 10, 0),
                LocalDateTime.of(2026, 9, 14, 9, 0),
                LocalDateTime.of(2026, 9, 15, 9, 0));

        when(userService.getAccountInfo(10L))
                .thenReturn(accountInfo);

        UserProfile profile = new UserProfile();
        List<ShippingAddress> addresses = List.of(new ShippingAddress());

        when(userProfileService.findByUserId(10L))
                .thenReturn(profile);

        when(shippingAddressService.findAllByUserId(10L))
                .thenReturn(addresses);

        String viewName = controller.index(loginUser, model);

        assertEquals("mypage/index", viewName);

        verify(model)
                .addAttribute("profile", profile);

        verify(model)
                .addAttribute("addresses", addresses);

        verify(userService)
                .getAccountInfo(10L);

        verify(model)
                .addAttribute(
                        "account",
                        accountInfo);
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

    @Test
    void editPasswordDisplaysPasswordChangeForm() {

        String viewName = controller.editPassword(model);

        assertEquals(
                "mypage/password-form",
                viewName);

        verify(model)
                .addAttribute(
                        eq("passwordChangeForm"),
                        any(PasswordChangeForm.class));
    }

    @Test
    void changePasswordChangesLoggedInUsersPassword() {

        CustomUserDetails loginUser = mock(CustomUserDetails.class);
        when(loginUser.getId()).thenReturn(10L);

        PasswordChangeForm form = new PasswordChangeForm();
        form.setCurrentPassword("current-password");
        form.setNewPassword("new-password");
        form.setConfirmPassword("new-password");

        BindingResult bindingResult = mock(BindingResult.class);
        when(bindingResult.hasErrors()).thenReturn(false);

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        String viewName = controller.changePassword(
                form,
                bindingResult,
                loginUser,
                redirectAttributes);

        assertEquals(
                "redirect:/mypage",
                viewName);

        verify(userService)
                .changePassword(
                        10L,
                        "current-password",
                        "new-password");

        verify(redirectAttributes)
                .addFlashAttribute(
                        "successMessage",
                        "パスワードを変更しました。");
    }

    @Test
    void changePasswordReturnsFormWhenConfirmationDoesNotMatch() {

        CustomUserDetails loginUser = mock(CustomUserDetails.class);

        PasswordChangeForm form = new PasswordChangeForm();
        form.setCurrentPassword("current-password");
        form.setNewPassword("new-password");
        form.setConfirmPassword("different-password");

        BindingResult bindingResult = mock(BindingResult.class);
        when(bindingResult.hasErrors()).thenReturn(false);

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        String viewName = controller.changePassword(
                form,
                bindingResult,
                loginUser,
                redirectAttributes);

        assertEquals(
                "mypage/password-form",
                viewName);

        verify(bindingResult)
                .rejectValue(
                        "confirmPassword",
                        "mismatch",
                        "新しいパスワードと確認用パスワードが一致しません。");
    }

    @Test
    void changePasswordReturnsFormWhenCurrentPasswordIsIncorrect() {

        CustomUserDetails loginUser = mock(CustomUserDetails.class);
        when(loginUser.getId()).thenReturn(10L);

        PasswordChangeForm form = new PasswordChangeForm();
        form.setCurrentPassword("wrong-password");
        form.setNewPassword("new-password");
        form.setConfirmPassword("new-password");

        BindingResult bindingResult = mock(BindingResult.class);
        when(bindingResult.hasErrors()).thenReturn(false);

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        doThrow(new IncorrectCurrentPasswordException())
                .when(userService)
                .changePassword(
                        10L,
                        "wrong-password",
                        "new-password");

        String viewName = controller.changePassword(
                form,
                bindingResult,
                loginUser,
                redirectAttributes);

        assertEquals(
                "mypage/password-form",
                viewName);

        verify(bindingResult)
                .rejectValue(
                        "currentPassword",
                        "incorrect",
                        "現在のパスワードが正しくありません。");
    }

    @Test
    void changePasswordReturnsFormWhenNewPasswordIsSameAsCurrentPassword() {

        CustomUserDetails loginUser = mock(CustomUserDetails.class);
        when(loginUser.getId()).thenReturn(10L);

        PasswordChangeForm form = new PasswordChangeForm();
        form.setCurrentPassword("same-password");
        form.setNewPassword("same-password");
        form.setConfirmPassword("same-password");

        BindingResult bindingResult = mock(BindingResult.class);
        when(bindingResult.hasErrors()).thenReturn(false);

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        doThrow(new SameAsCurrentPasswordException())
                .when(userService)
                .changePassword(
                        10L,
                        "same-password",
                        "same-password");

        String viewName = controller.changePassword(
                form,
                bindingResult,
                loginUser,
                redirectAttributes);

        assertEquals(
                "mypage/password-form",
                viewName);

        verify(bindingResult)
                .rejectValue(
                        "newPassword",
                        "sameAsCurrent",
                        "新しいパスワードには現在のパスワードと異なるパスワードを入力してください。");
    }

}
