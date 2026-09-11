package com.example.ecsite.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.example.ecsite.entity.AdminAccount;
import com.example.ecsite.form.AdminAccountCreateForm;
import com.example.ecsite.form.AdminAccountEditForm;
import com.example.ecsite.repository.AdminAccountRepository;

@ExtendWith(MockitoExtension.class)
class AdminAccountServiceTest {

    @Mock
    private AdminAccountRepository adminAccountRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Test
    void createNormalizesUsernameAndCreatesEnabledAccount() {

        AdminAccountCreateForm form = createForm(
                " admin1 ",
                "password123");

        when(adminAccountRepository.existsByUsername("admin1"))
                .thenReturn(false);

        when(passwordEncoder.encode("password123"))
                .thenReturn("encoded-password");

        AdminAccountService service = createService();

        service.create(form);

        ArgumentCaptor<AdminAccount> captor = ArgumentCaptor.forClass(AdminAccount.class);

        verify(adminAccountRepository)
                .saveAndFlush(captor.capture());

        AdminAccount saved = captor.getValue();

        assertEquals("admin1", saved.getUsername());
        assertEquals("encoded-password", saved.getPassword());
        assertTrue(saved.isEnabled());
    }

    @Test
    void createRejectsDuplicateUsername() {

        AdminAccountCreateForm form = createForm(
                "admin1",
                "password123");

        when(adminAccountRepository.existsByUsername("admin1"))
                .thenReturn(true);

        AdminAccountService service = createService();

        assertThrows(
                IllegalArgumentException.class,
                () -> service.create(form));

        verify(adminAccountRepository, never())
                .saveAndFlush(any(AdminAccount.class));
    }

    @Test
    void createConvertsDatabaseDuplicateException() {

        AdminAccountCreateForm form = createForm(
                "admin1",
                "password123");

        when(adminAccountRepository.existsByUsername("admin1"))
                .thenReturn(false);

        when(passwordEncoder.encode("password123"))
                .thenReturn("encoded-password");

        when(adminAccountRepository.saveAndFlush(any(AdminAccount.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "duplicate username"));

        AdminAccountService service = createService();

        assertThrows(
                IllegalArgumentException.class,
                () -> service.create(form));
    }

    @Test
    void updateChangesUsernameAndEnabled() {

        AdminAccount account = createAccount("admin1", "old-password", true);

        when(adminAccountRepository.findById(2L))
                .thenReturn(Optional.of(account));

        when(adminAccountRepository
                .existsByUsernameAndIdNot("admin2", 2L))
                .thenReturn(false);

        AdminAccountEditForm form = editForm(" admin2 ", "", "", false);

        AdminAccountService service = createService();

        service.update(2L, form, 1L);

        assertEquals("admin2", account.getUsername());
        assertFalse(account.isEnabled());
        assertEquals("old-password", account.getPassword());

        verify(passwordEncoder, never())
                .encode(any());
    }

    @Test
    void updateEncodesNewPassword() {

        AdminAccount account = createAccount("admin1", "old-password", true);

        when(adminAccountRepository.findById(2L))
                .thenReturn(Optional.of(account));

        when(adminAccountRepository
                .existsByUsernameAndIdNot("admin1", 2L))
                .thenReturn(false);

        when(passwordEncoder.encode("newpassword"))
                .thenReturn("new-encoded-password");

        AdminAccountEditForm form = editForm(
                "admin1",
                "newpassword",
                "newpassword",
                true);

        AdminAccountService service = createService();

        service.update(2L, form, 1L);

        assertEquals(
                "new-encoded-password",
                account.getPassword());
    }

    @Test
    void updateKeepsPasswordWhenBlank() {

        AdminAccount account = createAccount("admin1", "old-password", true);

        when(adminAccountRepository.findById(2L))
                .thenReturn(Optional.of(account));

        when(adminAccountRepository
                .existsByUsernameAndIdNot("admin1", 2L))
                .thenReturn(false);

        AdminAccountEditForm form = editForm("admin1", "", "", true);

        AdminAccountService service = createService();

        service.update(2L, form, 1L);

        assertEquals("old-password", account.getPassword());

        verify(passwordEncoder, never())
                .encode(any());
    }

    @Test
    void updateRejectsDuplicateUsername() {

        AdminAccount account = createAccount("admin1", "password", true);

        when(adminAccountRepository.findById(2L))
                .thenReturn(Optional.of(account));

        when(adminAccountRepository
                .existsByUsernameAndIdNot("admin2", 2L))
                .thenReturn(true);

        AdminAccountEditForm form = editForm("admin2", "", "", true);

        AdminAccountService service = createService();

        assertThrows(
                IllegalArgumentException.class,
                () -> service.update(2L, form, 1L));

        verify(adminAccountRepository, never())
                .saveAndFlush(any(AdminAccount.class));
    }

    @Test
    void updateRejectsDisablingLoggedInAccount() {

        AdminAccount account = createAccount("admin1", "password", true);

        when(adminAccountRepository.findById(1L))
                .thenReturn(Optional.of(account));

        when(adminAccountRepository
                .existsByUsernameAndIdNot("admin1", 1L))
                .thenReturn(false);

        AdminAccountEditForm form = editForm("admin1", "", "", false);

        AdminAccountService service = createService();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.update(1L, form, 1L));

        assertEquals(
                "ログイン中の管理者アカウントを無効化することはできません。",
                exception.getMessage());

        verify(adminAccountRepository, never())
                .saveAndFlush(any(AdminAccount.class));
    }

    @Test
    void updateAllowsDisablingOtherAccount() {

        AdminAccount account = createAccount("admin2", "password", true);

        when(adminAccountRepository.findById(2L))
                .thenReturn(Optional.of(account));

        when(adminAccountRepository
                .existsByUsernameAndIdNot("admin2", 2L))
                .thenReturn(false);

        AdminAccountEditForm form = editForm("admin2", "", "", false);

        AdminAccountService service = createService();

        service.update(2L, form, 1L);

        assertFalse(account.isEnabled());

        verify(adminAccountRepository)
                .saveAndFlush(account);
    }

    @Test
    void updateRejectsUnknownAccount() {

        when(adminAccountRepository.findById(99L))
                .thenReturn(Optional.empty());

        AdminAccountEditForm form = editForm("admin99", "", "", true);

        AdminAccountService service = createService();

        assertThrows(
                IllegalArgumentException.class,
                () -> service.update(99L, form, 1L));
    }

    private AdminAccountService createService() {
        return new AdminAccountService(
                adminAccountRepository,
                passwordEncoder);
    }

    private AdminAccountCreateForm createForm(
            String username,
            String password) {

        AdminAccountCreateForm form = new AdminAccountCreateForm();

        form.setUsername(username);
        form.setPassword(password);
        form.setConfirmPassword(password);

        return form;
    }

    private AdminAccountEditForm editForm(
            String username,
            String password,
            String confirmPassword,
            boolean enabled) {

        AdminAccountEditForm form = new AdminAccountEditForm();

        form.setUsername(username);
        form.setPassword(password);
        form.setConfirmPassword(confirmPassword);
        form.setEnabled(enabled);

        return form;
    }

    private AdminAccount createAccount(
            String username,
            String password,
            boolean enabled) {

        AdminAccount account = new AdminAccount();

        account.setUsername(username);
        account.setPassword(password);
        account.setEnabled(enabled);

        return account;
    }

    @Test
    void findAllEnabledReturnsEnabledAccountsOrderedByUsername() {

        AdminAccount admin1 = createAccount("admin1", "password", true);
        AdminAccount admin2 = createAccount("admin2", "password", true);

        when(adminAccountRepository
                .findByEnabledTrueOrderByUsernameAsc())
                .thenReturn(List.of(admin1, admin2));

        AdminAccountService service = createService();

        List<AdminAccount> result = service.findAllEnabled();

        assertEquals(
                List.of(admin1, admin2),
                result);

        verify(adminAccountRepository)
                .findByEnabledTrueOrderByUsernameAsc();
    }

}
