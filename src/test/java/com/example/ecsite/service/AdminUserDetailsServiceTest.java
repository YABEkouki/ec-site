package com.example.ecsite.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import com.example.ecsite.entity.AdminAccount;
import com.example.ecsite.repository.AdminAccountRepository;
import com.example.ecsite.security.AdminUserDetails;

@ExtendWith(MockitoExtension.class)
class AdminUserDetailsServiceTest {

    @Mock
    private AdminAccountRepository adminAccountRepository;

    @Test
    void loadUserByUsernameCreatesAdminUserDetails() {

        AdminAccount adminAccount = new AdminAccount();
        adminAccount.setUsername("admin");
        adminAccount.setPassword("encoded-password");
        adminAccount.setEnabled(true);

        when(adminAccountRepository.findByUsername("admin"))
                .thenReturn(Optional.of(adminAccount));

        AdminUserDetailsService service =
                new AdminUserDetailsService(
                        adminAccountRepository);

        UserDetails result =
                service.loadUserByUsername("admin");

        AdminUserDetails userDetails =
                (AdminUserDetails) result;

        assertEquals(
                "admin",
                userDetails.getUsername());

        assertEquals(
                "encoded-password",
                userDetails.getPassword());

        assertEquals(
                1,
                userDetails.getAuthorities().size());

        assertEquals(
                "ROLE_ADMIN",
                userDetails.getAuthorities()
                        .iterator()
                        .next()
                        .getAuthority());

        verify(adminAccountRepository)
                .findByUsername("admin");
    }

    @Test
    void loadUserByUsernameThrowsWhenAdminDoesNotExist() {

        when(adminAccountRepository.findByUsername("unknown"))
                .thenReturn(Optional.empty());

        AdminUserDetailsService service =
                new AdminUserDetailsService(
                        adminAccountRepository);

        assertThrows(
                UsernameNotFoundException.class,
                () -> service.loadUserByUsername("unknown"));

        verify(adminAccountRepository)
                .findByUsername("unknown");
    }
}
