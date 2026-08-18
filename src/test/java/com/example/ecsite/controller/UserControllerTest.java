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

import com.example.ecsite.exception.UsernameAlreadyExistsException;
import com.example.ecsite.form.UserForm;
import com.example.ecsite.service.UserService;

@ExtendWith(MockitoExtension.class)
class UserControllerTest {

    @Mock
    private UserService userService;

    private UserController controller;

    @BeforeEach
    void setUp() {
        controller = new UserController(userService);
    }

    @Test
    void signupRegistersUserAndRedirectsToLogin() {

        UserForm userForm = createUserForm();
        BindingResult bindingResult = createBindingResult(userForm);

        when(userService.passwordsMatch(userForm))
                .thenReturn(true);

        when(userService.usernameExists("user1"))
                .thenReturn(false);

        String view = controller.signup(
                userForm,
                bindingResult);

        assertEquals(
                "redirect:/login?registered",
                view);

        assertTrue(!bindingResult.hasErrors());

        verify(userService).register(userForm);
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
                bindingResult);

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
                bindingResult);

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
                bindingResult);

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
                bindingResult);

        assertEquals("users/signup", view);

        assertTrue(
                bindingResult.hasFieldErrors("username"));

        verify(userService).register(userForm);
    }

    private UserForm createUserForm() {

        UserForm userForm = new UserForm();

        userForm.setUsername("user1");
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