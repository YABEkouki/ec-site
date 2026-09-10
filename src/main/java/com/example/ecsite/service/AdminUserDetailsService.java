package com.example.ecsite.service;

import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.example.ecsite.entity.AdminAccount;
import com.example.ecsite.repository.AdminAccountRepository;
import com.example.ecsite.security.AdminUserDetails;

@Service
public class AdminUserDetailsService implements UserDetailsService {

    private final AdminAccountRepository adminAccountRepository;

    public AdminUserDetailsService(
            AdminAccountRepository adminAccountRepository) {

        this.adminAccountRepository = adminAccountRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username)
            throws UsernameNotFoundException {

        AdminAccount adminAccount = adminAccountRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException(username));

        return new AdminUserDetails(
                adminAccount.getId(),
                adminAccount.getUsername(),
                adminAccount.getPassword(),
                adminAccount.isEnabled(),
                AuthorityUtils.createAuthorityList("ROLE_ADMIN"));
    }
}
