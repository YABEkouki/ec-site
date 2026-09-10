package com.example.ecsite.repository;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;

import com.example.ecsite.entity.AdminAccount;
import com.example.ecsite.entity.User;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AdminAccountRepositoryTest {

    @Autowired
    private AdminAccountRepository adminAccountRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void findByUsernameReturnsAccount() {

        AdminAccount account =
                createAdminAccount("admin-find");

        adminAccountRepository.saveAndFlush(account);

        assertTrue(
                adminAccountRepository
                        .findByUsername("admin-find")
                        .isPresent());
    }

    @Test
    void existsByUsernameReturnsTrueWhenUsernameExists() {

        AdminAccount account =
                createAdminAccount("admin-exists");

        adminAccountRepository.saveAndFlush(account);

        assertTrue(
                adminAccountRepository
                        .existsByUsername("admin-exists"));
    }

    @Test
    void existsByUsernameAndIdNotExcludesSameAccount() {

        AdminAccount account =
                createAdminAccount("admin-edit");

        account =
                adminAccountRepository.saveAndFlush(account);

        assertFalse(
                adminAccountRepository
                        .existsByUsernameAndIdNot(
                                "admin-edit",
                                account.getId()));
    }

    @Test
    void existsByUsernameAndIdNotFindsOtherAccount() {

        AdminAccount first =
                createAdminAccount("admin-first");

        AdminAccount second =
                createAdminAccount("admin-second");

        first =
                adminAccountRepository.saveAndFlush(first);

        second =
                adminAccountRepository.saveAndFlush(second);

        assertTrue(
                adminAccountRepository
                        .existsByUsernameAndIdNot(
                                "admin-first",
                                second.getId()));
    }

    @Test
    void duplicateAdminUsernameIsRejected() {

        AdminAccount first =
                createAdminAccount("admin-duplicate");

        AdminAccount second =
                createAdminAccount("admin-duplicate");

        adminAccountRepository.saveAndFlush(first);

        assertThrows(
                DataIntegrityViolationException.class,
                () -> adminAccountRepository.saveAndFlush(second));
    }

    @Test
    void sameUsernameCanExistForCustomerAndAdmin() {

        User user = new User();
        user.setUsername("shared-name");
        user.setPassword("password");
        user.setEnabled(true);

        userRepository.saveAndFlush(user);

        AdminAccount adminAccount =
                createAdminAccount("shared-name");

        adminAccountRepository.saveAndFlush(adminAccount);

        assertTrue(
                userRepository
                        .findByUsername("shared-name")
                        .isPresent());

        assertTrue(
                adminAccountRepository
                        .findByUsername("shared-name")
                        .isPresent());
    }

    private AdminAccount createAdminAccount(
            String username) {

        AdminAccount account =
                new AdminAccount();

        account.setUsername(username);
        account.setPassword("password");
        account.setEnabled(true);

        return account;
    }
}
