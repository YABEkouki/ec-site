package com.example.ecsite.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.example.ecsite.dto.UserAccountInfo;
import com.example.ecsite.dto.UserAccountUpdateResult;
import com.example.ecsite.entity.ShippingAddress;
import com.example.ecsite.entity.User;
import com.example.ecsite.entity.UserProfile;
import com.example.ecsite.exception.EmailAlreadyExistsException;
import com.example.ecsite.exception.EmailAlreadyVerifiedException;
import com.example.ecsite.exception.EmailNotRegisteredException;
import com.example.ecsite.exception.EmailVerificationTooSoonException;
import com.example.ecsite.exception.IncorrectCurrentPasswordException;
import com.example.ecsite.exception.SameAsCurrentPasswordException;
import com.example.ecsite.exception.UsernameAlreadyExistsException;
import com.example.ecsite.form.PasswordChangeForm;
import com.example.ecsite.form.ShippingAddressForm;
import com.example.ecsite.form.UserAccountEditForm;
import com.example.ecsite.form.UserProfileForm;
import com.example.ecsite.security.CustomUserDetails;
import com.example.ecsite.service.EmailVerificationService;
import com.example.ecsite.service.MailService;
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

    @Mock
    private EmailVerificationService emailVerificationService;

    @Mock
    private MailService mailService;

    private MyPageController controller;

    @BeforeEach
    void setUp() {

        controller = new MyPageController(
                userProfileService,
                shippingAddressService,
                userService,
                emailVerificationService,
                mailService);
    }

    @Test
    void indexDisplaysProfileAndAddressesForLoggedInUser() {

        CustomUserDetails loginUser = mock(CustomUserDetails.class);

        when(loginUser.getId()).thenReturn(10L);

        UserAccountInfo accountInfo = new UserAccountInfo(
                "user1",
                "user1@example.com",
                null,
                LocalDateTime.of(2026, 9, 1, 10, 0),
                LocalDateTime.of(2026, 9, 10, 12, 0),
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

    @Test
    void editAccountDisplaysCurrentAccountEditForm() {

        CustomUserDetails loginUser = mock(CustomUserDetails.class);

        when(loginUser.getId()).thenReturn(10L);

        UserAccountEditForm form = new UserAccountEditForm();
        form.setUsername("user1");
        form.setEmail("user1@example.com");

        when(userService.createAccountEditForm(10L))
                .thenReturn(form);

        String viewName = controller.editAccount(
                loginUser,
                model);

        assertEquals(
                "mypage/account-form",
                viewName);

        verify(userService)
                .createAccountEditForm(10L);

        verify(model)
                .addAttribute(
                        "userAccountEditForm",
                        form);
    }

    @Test
    void updateAccountUpdatesUsernameAndAuthenticationPrincipal() {

        CustomUserDetails loginUser = new CustomUserDetails(
                10L,
                "user1",
                "encoded-password",
                true,
                List.of());

        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                loginUser,
                null,
                loginUser.getAuthorities());

        SecurityContextHolder.getContext()
                .setAuthentication(authentication);

        try {
            UserAccountEditForm form = new UserAccountEditForm();
            form.setUsername(" user2 ");
            form.setEmail("user1@example.com");

            BindingResult bindingResult = mock(BindingResult.class);
            when(bindingResult.hasErrors()).thenReturn(false);

            RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

            when(userService.updateAccount(
                    10L,
                    " user2 ",
                    "user1@example.com"))
                    .thenReturn(
                            new UserAccountUpdateResult(
                                    "user2",
                                    "user1@example.com",
                                    false));

            String viewName = controller.updateAccount(
                    form,
                    bindingResult,
                    loginUser,
                    redirectAttributes);

            assertEquals(
                    "redirect:/mypage",
                    viewName);

            verify(userService)
                    .updateAccount(
                            10L,
                            " user2 ",
                            "user1@example.com");

            verify(redirectAttributes)
                    .addFlashAttribute(
                            "successMessage",
                            "アカウント情報を更新しました。");

            CustomUserDetails updatedPrincipal = (CustomUserDetails) SecurityContextHolder
                    .getContext()
                    .getAuthentication()
                    .getPrincipal();

            assertEquals(
                    10L,
                    updatedPrincipal.getId());

            assertEquals(
                    "user2",
                    updatedPrincipal.getUsername());

            assertEquals(
                    "encoded-password",
                    updatedPrincipal.getPassword());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void updateAccountKeepsAuthenticationWhenUsernameIsUnchanged() {

        CustomUserDetails loginUser = new CustomUserDetails(
                10L,
                "user1",
                "encoded-password",
                true,
                List.of());

        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                loginUser,
                null,
                loginUser.getAuthorities());

        SecurityContextHolder.getContext()
                .setAuthentication(authentication);

        try {
            UserAccountEditForm form = new UserAccountEditForm();
            form.setUsername(" user1 ");
            form.setEmail("user1@example.com");

            BindingResult bindingResult = mock(BindingResult.class);
            when(bindingResult.hasErrors()).thenReturn(false);

            RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

            when(userService.updateAccount(
                    10L,
                    " user1 ",
                    "user1@example.com"))
                    .thenReturn(
                            new UserAccountUpdateResult(
                                    "user1",
                                    "user1@example.com",
                                    false));

            String viewName = controller.updateAccount(
                    form,
                    bindingResult,
                    loginUser,
                    redirectAttributes);

            assertEquals(
                    "redirect:/mypage",
                    viewName);

            assertSame(
                    authentication,
                    SecurityContextHolder.getContext()
                            .getAuthentication());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void updateAccountReturnsFormWhenValidationFails() {

        CustomUserDetails loginUser = mock(CustomUserDetails.class);

        UserAccountEditForm form = new UserAccountEditForm();

        BindingResult bindingResult = mock(BindingResult.class);
        when(bindingResult.hasErrors()).thenReturn(true);

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        String viewName = controller.updateAccount(
                form,
                bindingResult,
                loginUser,
                redirectAttributes);

        assertEquals(
                "mypage/account-form",
                viewName);
    }

    @Test
    void updateAccountReturnsFormWhenUsernameAlreadyExists() {

        CustomUserDetails loginUser = mock(CustomUserDetails.class);
        when(loginUser.getId()).thenReturn(10L);

        UserAccountEditForm form = new UserAccountEditForm();
        form.setUsername("user2");
        form.setEmail("user1@example.com");

        BindingResult bindingResult = mock(BindingResult.class);
        when(bindingResult.hasErrors()).thenReturn(false);

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        when(userService.updateAccount(
                10L,
                "user2",
                "user1@example.com"))
                .thenThrow(
                        new UsernameAlreadyExistsException(
                                "user2",
                                null));

        String viewName = controller.updateAccount(
                form,
                bindingResult,
                loginUser,
                redirectAttributes);

        assertEquals(
                "mypage/account-form",
                viewName);

        verify(bindingResult)
                .rejectValue(
                        "username",
                        "duplicate",
                        "ユーザー名は既に使用されています。");
    }

    @Test
    void updateAccountReturnsFormWhenEmailAlreadyExists() {

        CustomUserDetails loginUser = mock(CustomUserDetails.class);
        when(loginUser.getId()).thenReturn(10L);

        UserAccountEditForm form = new UserAccountEditForm();
        form.setUsername("user1");
        form.setEmail("used@example.com");

        BindingResult bindingResult = mock(BindingResult.class);
        when(bindingResult.hasErrors()).thenReturn(false);

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        when(userService.updateAccount(
                10L,
                "user1",
                "used@example.com"))
                .thenThrow(
                        new EmailAlreadyExistsException(
                                "used@example.com",
                                null));

        String viewName = controller.updateAccount(
                form,
                bindingResult,
                loginUser,
                redirectAttributes);

        assertEquals(
                "mypage/account-form",
                viewName);

        verify(bindingResult)
                .rejectValue(
                        "email",
                        "duplicate",
                        "メールアドレスは既に使用されています。");
    }

    @Test
    void updateAccountSendsVerificationMailWhenEmailChanges() {

        CustomUserDetails loginUser = new CustomUserDetails(
                10L,
                "user1",
                "encoded-password",
                true,
                List.of());

        UserAccountEditForm form = new UserAccountEditForm();
        form.setUsername("user1");
        form.setEmail("new@example.com");

        BindingResult bindingResult = mock(BindingResult.class);

        when(bindingResult.hasErrors())
                .thenReturn(false);

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        when(userService.updateAccount(
                10L,
                "user1",
                "new@example.com"))
                .thenReturn(
                        new UserAccountUpdateResult(
                                "user1",
                                "new@example.com",
                                true));

        when(emailVerificationService.issueToken(10L))
                .thenReturn("raw-token");

        String viewName = controller.updateAccount(
                form,
                bindingResult,
                loginUser,
                redirectAttributes);

        assertEquals(
                "redirect:/mypage",
                viewName);

        verify(emailVerificationService)
                .issueToken(10L);

        verify(mailService)
                .sendEmailVerification(
                        "new@example.com",
                        "raw-token");

        verify(redirectAttributes)
                .addFlashAttribute(
                        "successMessage",
                        "アカウント情報を更新しました。");
    }

    @Test
    void updateAccountKeepsUpdateWhenVerificationMailSendingFails() {

        CustomUserDetails loginUser = new CustomUserDetails(
                10L,
                "user1",
                "encoded-password",
                true,
                List.of());

        UserAccountEditForm form = new UserAccountEditForm();
        form.setUsername("user1");
        form.setEmail("new@example.com");

        BindingResult bindingResult = mock(BindingResult.class);

        when(bindingResult.hasErrors())
                .thenReturn(false);

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        when(userService.updateAccount(
                10L,
                "user1",
                "new@example.com"))
                .thenReturn(
                        new UserAccountUpdateResult(
                                "user1",
                                "new@example.com",
                                true));

        when(emailVerificationService.issueToken(10L))
                .thenReturn("raw-token");

        doThrow(new org.springframework.mail.MailSendException(
                "mail send failed"))
                .when(mailService)
                .sendEmailVerification(
                        "new@example.com",
                        "raw-token");

        String viewName = controller.updateAccount(
                form,
                bindingResult,
                loginUser,
                redirectAttributes);

        assertEquals(
                "redirect:/mypage",
                viewName);

        verify(userService)
                .updateAccount(
                        10L,
                        "user1",
                        "new@example.com");

        verify(emailVerificationService)
                .issueToken(10L);

        verify(mailService)
                .sendEmailVerification(
                        "new@example.com",
                        "raw-token");

        verify(redirectAttributes)
                .addFlashAttribute(
                        "successMessage",
                        "アカウント情報を更新しました。");

        verify(redirectAttributes)
                .addFlashAttribute(
                        "errorMessage",
                        "確認メールの送信に失敗しました。時間をおいて再送してください。");
    }

    @Test
    void resendEmailVerificationSendsVerificationMail() {

        CustomUserDetails loginUser = mock(CustomUserDetails.class);

        when(loginUser.getId())
                .thenReturn(10L);

        User user = new User();
        user.setEmail("user@example.com");

        when(emailVerificationService.issueToken(10L))
                .thenReturn("raw-token");

        when(userService.findById(10L))
                .thenReturn(user);

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        String viewName = controller.resendEmailVerification(
                loginUser,
                redirectAttributes);

        assertEquals(
                "redirect:/mypage",
                viewName);

        verify(emailVerificationService)
                .issueToken(10L);

        verify(userService)
                .findById(10L);

        verify(mailService)
                .sendEmailVerification(
                        "user@example.com",
                        "raw-token");

        verify(redirectAttributes)
                .addFlashAttribute(
                        "successMessage",
                        "確認メールを送信しました。");
    }

    @Test
    void resendEmailVerificationReturnsErrorWhenEmailIsNotRegistered() {

        CustomUserDetails loginUser = mock(CustomUserDetails.class);

        when(loginUser.getId())
                .thenReturn(10L);

        when(emailVerificationService.issueToken(10L))
                .thenThrow(new EmailNotRegisteredException());

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        String viewName = controller.resendEmailVerification(
                loginUser,
                redirectAttributes);

        assertEquals(
                "redirect:/mypage",
                viewName);

        verify(redirectAttributes)
                .addFlashAttribute(
                        "errorMessage",
                        "メールアドレスが登録されていません。");

        verify(mailService, never())
                .sendEmailVerification(any(), any());
    }

    @Test
    void resendEmailVerificationReturnsErrorWhenEmailIsAlreadyVerified() {

        CustomUserDetails loginUser = mock(CustomUserDetails.class);

        when(loginUser.getId())
                .thenReturn(10L);

        when(emailVerificationService.issueToken(10L))
                .thenThrow(new EmailAlreadyVerifiedException());

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        String viewName = controller.resendEmailVerification(
                loginUser,
                redirectAttributes);

        assertEquals(
                "redirect:/mypage",
                viewName);

        verify(redirectAttributes)
                .addFlashAttribute(
                        "errorMessage",
                        "メールアドレスはすでに確認済みです。");

        verify(mailService, never())
                .sendEmailVerification(any(), any());
    }

    @Test
    void resendEmailVerificationReturnsErrorWithinCooldown() {

        CustomUserDetails loginUser = mock(CustomUserDetails.class);

        when(loginUser.getId())
                .thenReturn(10L);

        when(emailVerificationService.issueToken(10L))
                .thenThrow(
                        new EmailVerificationTooSoonException());

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        String viewName = controller.resendEmailVerification(
                loginUser,
                redirectAttributes);

        assertEquals(
                "redirect:/mypage",
                viewName);

        verify(redirectAttributes)
                .addFlashAttribute(
                        "errorMessage",
                        "確認メールを再送するまで、しばらくお待ちください。");

        verify(mailService, never())
                .sendEmailVerification(any(), any());
    }

    @Test
    void resendEmailVerificationReturnsErrorWhenMailSendingFails() {

        CustomUserDetails loginUser = mock(CustomUserDetails.class);

        when(loginUser.getId())
                .thenReturn(10L);

        User user = new User();
        user.setEmail("user@example.com");

        when(emailVerificationService.issueToken(10L))
                .thenReturn("raw-token");

        when(userService.findById(10L))
                .thenReturn(user);

        doThrow(new org.springframework.mail.MailSendException(
                "mail send failed"))
                .when(mailService)
                .sendEmailVerification(
                        "user@example.com",
                        "raw-token");

        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);

        String viewName = controller.resendEmailVerification(
                loginUser,
                redirectAttributes);

        assertEquals(
                "redirect:/mypage",
                viewName);

        verify(emailVerificationService)
                .issueToken(10L);

        verify(userService)
                .findById(10L);

        verify(mailService)
                .sendEmailVerification(
                        "user@example.com",
                        "raw-token");

        verify(redirectAttributes)
                .addFlashAttribute(
                        "errorMessage",
                        "確認メールの送信に失敗しました。時間をおいて再送してください。");

        verify(redirectAttributes, never())
                .addFlashAttribute(
                        eq("successMessage"),
                        any());
    }

}
