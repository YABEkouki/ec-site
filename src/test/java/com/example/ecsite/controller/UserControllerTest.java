package com.example.ecsite.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.example.ecsite.dto.UserRegistrationResult;
import com.example.ecsite.exception.EmailAlreadyExistsException;
import com.example.ecsite.exception.UsernameAlreadyExistsException;
import com.example.ecsite.form.UserForm;
import com.example.ecsite.service.EmailVerificationService;
import com.example.ecsite.service.MailService;
import com.example.ecsite.service.UserService;

@ExtendWith(MockitoExtension.class)
class UserControllerTest {

    @Mock
    private UserService userService;

    @Mock
    private EmailVerificationService emailVerificationService;

    @Mock
    private MailService mailService;

    @Mock
    private RedirectAttributes redirectAttributes;

    private UserController controller;

    @BeforeEach
    void setUp() {
        controller = new UserController(
                userService,
                emailVerificationService,
                mailService);
    }

    @Test
    void signupRegistersUserAndRedirectsToLogin() {

        UserForm userForm = createUserForm();
        BindingResult bindingResult = createBindingResult(userForm);

        when(userService.passwordsMatch(userForm))
                .thenReturn(true);

        when(userService.usernameExists("user1"))
                .thenReturn(false);

        when(userService.emailExists("user1@example.com"))
                .thenReturn(false);

        when(userService.register(userForm))
                .thenReturn(
                        new UserRegistrationResult(
                                100L,
                                "user1@example.com"));

        when(emailVerificationService.issueToken(100L))
                .thenReturn("raw-token");

        String view = controller.signup(
                userForm,
                bindingResult,
                redirectAttributes);

        assertEquals(
                "redirect:/login?registered",
                view);

        assertTrue(!bindingResult.hasErrors());

        verify(userService).register(userForm);

        verify(emailVerificationService)
                .issueToken(100L);

        verify(mailService)
                .sendEmailVerification(
                        "user1@example.com",
                        "raw-token");

        verify(redirectAttributes, never())
                .addFlashAttribute(
                        org.mockito.ArgumentMatchers.eq("errorMessage"),
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    void signupReturnsFormWhenInputHasErrors() {

        UserForm userForm = createUserForm();
        BindingResult bindingResult = createBindingResult(userForm);

        bindingResult.rejectValue(
                "username",
                "invalid",
                "ユーザー名を入力してください。");

        String view = controller.signup(
                userForm,
                bindingResult,
                redirectAttributes);

        assertEquals("users/signup", view);

        verifyNoInteractions(userService);
    }

    @Test
    void signupReturnsFormWhenPasswordsDoNotMatch() {

        UserForm userForm = createUserForm();
        BindingResult bindingResult = createBindingResult(userForm);

        when(userService.passwordsMatch(userForm))
                .thenReturn(false);

        String view = controller.signup(
                userForm,
                bindingResult,
                redirectAttributes);

        assertEquals("users/signup", view);

        assertTrue(
                bindingResult.hasFieldErrors(
                        "confirmPassword"));

        verify(userService, never())
                .usernameExists(userForm.getUsername());

        verify(userService, never())
                .register(userForm);
    }

    @Test
    void signupReturnsFormWhenUsernameAlreadyExists() {

        UserForm userForm = createUserForm();
        BindingResult bindingResult = createBindingResult(userForm);

        when(userService.passwordsMatch(userForm))
                .thenReturn(true);

        when(userService.usernameExists("user1"))
                .thenReturn(true);

        String view = controller.signup(
                userForm,
                bindingResult,
                redirectAttributes);

        assertEquals("users/signup", view);

        assertTrue(
                bindingResult.hasFieldErrors("username"));

        verify(userService, never())
                .register(userForm);
    }

    @Test
    void signupHandlesDuplicateDetectedDuringRegistration() {

        UserForm userForm = createUserForm();
        BindingResult bindingResult = createBindingResult(userForm);

        when(userService.passwordsMatch(userForm))
                .thenReturn(true);

        when(userService.usernameExists("user1"))
                .thenReturn(false);

        doThrow(new UsernameAlreadyExistsException(
                "user1",
                new RuntimeException("テスト用の原因例外")))
                .when(userService)
                .register(userForm);

        String view = controller.signup(
                userForm,
                bindingResult,
                redirectAttributes);

        assertEquals("users/signup", view);

        assertTrue(
                bindingResult.hasFieldErrors("username"));

        verify(userService).register(userForm);
    }

    @Test
    void signupReturnsFormWhenEmailAlreadyExists() {

        UserForm userForm = createUserForm();
        BindingResult bindingResult = createBindingResult(userForm);

        when(userService.passwordsMatch(userForm))
                .thenReturn(true);

        when(userService.usernameExists("user1"))
                .thenReturn(false);

        when(userService.emailExists("user1@example.com"))
                .thenReturn(true);

        String view = controller.signup(
                userForm,
                bindingResult,
                redirectAttributes);

        assertEquals("users/signup", view);

        assertTrue(
                bindingResult.hasFieldErrors("email"));

        verify(userService, never())
                .register(userForm);
    }

    @Test
    void signupHandlesDuplicateEmailDetectedDuringRegistration() {

        UserForm userForm = createUserForm();
        BindingResult bindingResult = createBindingResult(userForm);

        when(userService.passwordsMatch(userForm))
                .thenReturn(true);

        when(userService.usernameExists("user1"))
                .thenReturn(false);

        when(userService.emailExists("user1@example.com"))
                .thenReturn(false);

        doThrow(new EmailAlreadyExistsException(
                "user1@example.com",
                new RuntimeException("テスト用の原因例外")))
                .when(userService)
                .register(userForm);

        String view = controller.signup(
                userForm,
                bindingResult,
                redirectAttributes);

        assertEquals("users/signup", view);

        assertTrue(
                bindingResult.hasFieldErrors("email"));

        verify(userService)
                .register(userForm);
    }

    @Test
    void signupKeepsRegistrationSuccessWhenVerificationMailFails() {

        UserForm userForm = createUserForm();
        BindingResult bindingResult = createBindingResult(userForm);

        when(userService.passwordsMatch(userForm))
                .thenReturn(true);

        when(userService.usernameExists("user1"))
                .thenReturn(false);

        when(userService.emailExists("user1@example.com"))
                .thenReturn(false);

        when(userService.register(userForm))
                .thenReturn(
                        new UserRegistrationResult(
                                100L,
                                "user1@example.com"));

        when(emailVerificationService.issueToken(100L))
                .thenReturn("raw-token");

        doThrow(new org.springframework.mail.MailSendException(
                "mail send failed"))
                .when(mailService)
                .sendEmailVerification(
                        "user1@example.com",
                        "raw-token");

        String view = controller.signup(
                userForm,
                bindingResult,
                redirectAttributes);

        assertEquals(
                "redirect:/login?registered",
                view);

        assertTrue(!bindingResult.hasErrors());

        verify(userService)
                .register(userForm);

        verify(emailVerificationService)
                .issueToken(100L);

        verify(mailService)
                .sendEmailVerification(
                        "user1@example.com",
                        "raw-token");

        verify(redirectAttributes)
                .addFlashAttribute(
                        "errorMessage",
                        "会員登録は完了しましたが、確認メールの送信に失敗しました。ログイン後、マイページから再送してください。");
    }

    private UserForm createUserForm() {

        UserForm userForm = new UserForm();

        userForm.setUsername("user1");
        userForm.setEmail("user1@example.com");
        userForm.setPassword("password123");
        userForm.setConfirmPassword("password123");

        return userForm;
    }

    private BindingResult createBindingResult(
            UserForm userForm) {

        return new BeanPropertyBindingResult(
                userForm,
                "userForm");
    }
}