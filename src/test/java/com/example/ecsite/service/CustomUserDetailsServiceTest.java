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

import com.example.ecsite.entity.User;
import com.example.ecsite.repository.UserRepository;
import com.example.ecsite.security.CustomUserDetails;

@ExtendWith(MockitoExtension.class)
class CustomUserDetailsServiceTest {

    @Mock
    private UserRepository userRepository;

    @Test
    void loadUserByUsernameCreatesCustomerUserDetails() {

        User user = new User();
        user.setUsername("user1");
        user.setPassword("encoded-password");
        user.setEnabled(true);

        when(userRepository.findByUsername("user1"))
                .thenReturn(Optional.of(user));

        CustomUserDetailsService service =
                new CustomUserDetailsService(userRepository);

        UserDetails result =
                service.loadUserByUsername("user1");

        CustomUserDetails userDetails =
                (CustomUserDetails) result;

        assertEquals(
                "user1",
                userDetails.getUsername());

        assertEquals(
                "encoded-password",
                userDetails.getPassword());

        assertEquals(
                1,
                userDetails.getAuthorities().size());

        assertEquals(
                "ROLE_USER",
                userDetails.getAuthorities()
                        .iterator()
                        .next()
                        .getAuthority());

        verify(userRepository)
                .findByUsername("user1");
    }

    @Test
    void loadUserByUsernameThrowsWhenUserDoesNotExist() {

        when(userRepository.findByUsername("unknown"))
                .thenReturn(Optional.empty());

        CustomUserDetailsService service =
                new CustomUserDetailsService(userRepository);

        assertThrows(
                UsernameNotFoundException.class,
                () -> service.loadUserByUsername("unknown"));

        verify(userRepository)
                .findByUsername("unknown");
    }
}
