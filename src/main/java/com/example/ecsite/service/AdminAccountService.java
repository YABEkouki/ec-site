package com.example.ecsite.service;

import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.entity.AdminAccount;
import com.example.ecsite.form.AdminAccountCreateForm;
import com.example.ecsite.form.AdminAccountEditForm;
import com.example.ecsite.repository.AdminAccountRepository;

@Service
public class AdminAccountService {

    private final AdminAccountRepository adminAccountRepository;
    private final PasswordEncoder passwordEncoder;

    public AdminAccountService(
            AdminAccountRepository adminAccountRepository,
            PasswordEncoder passwordEncoder) {
        this.adminAccountRepository = adminAccountRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    public List<AdminAccount> findAll() {
        return adminAccountRepository.findAll();
    }

    @Transactional(readOnly = true)
    public AdminAccount findById(Long id) {
        return adminAccountRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(
                        "管理者アカウントが見つかりません。id=" + id));
    }

    @Transactional(readOnly = true)
    public boolean usernameExists(String username) {
        return adminAccountRepository.existsByUsername(normalizeUsername(username));
    }

    @Transactional(readOnly = true)
    public boolean usernameExistsForOtherAccount(String username, Long id) {
        return adminAccountRepository.existsByUsernameAndIdNot(
                normalizeUsername(username), id);
    }

    @Transactional
    public AdminAccount create(AdminAccountCreateForm form) {
        String username = normalizeUsername(form.getUsername());

        if (adminAccountRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("このユーザー名は既に使用されています。");
        }

        AdminAccount adminAccount = new AdminAccount();
        adminAccount.setUsername(username);
        adminAccount.setPassword(passwordEncoder.encode(form.getPassword()));
        adminAccount.setEnabled(true);

        try {
            return adminAccountRepository.saveAndFlush(adminAccount);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException(
                    "このユーザー名は既に使用されています。", e);
        }
    }

    @Transactional
    public AdminAccount update(
            Long id,
            AdminAccountEditForm form,
            Long loginAdminId) {

        AdminAccount adminAccount = findById(id);
        String username = normalizeUsername(form.getUsername());

        if (adminAccountRepository.existsByUsernameAndIdNot(username, id)) {
            throw new IllegalArgumentException("このユーザー名は既に使用されています。");
        }

        if (id.equals(loginAdminId) && !form.isEnabled()) {
            throw new IllegalArgumentException(
                    "ログイン中の管理者アカウントを無効化することはできません。");
        }

        adminAccount.setUsername(username);
        adminAccount.setEnabled(form.isEnabled());

        if (form.getPassword() != null && !form.getPassword().isBlank()) {
            adminAccount.setPassword(passwordEncoder.encode(form.getPassword()));
        }

        try {
            return adminAccountRepository.saveAndFlush(adminAccount);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException(
                    "このユーザー名は既に使用されています。", e);
        }
    }

    private String normalizeUsername(String username) {
        return username == null ? null : username.trim();
    }
}
