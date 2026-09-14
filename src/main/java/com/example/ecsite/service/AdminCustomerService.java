package com.example.ecsite.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.dto.AdminCustomerListItem;
import com.example.ecsite.form.AdminCustomerSearchForm;
import com.example.ecsite.repository.UserRepository;

@Service
@Transactional(readOnly = true)
public class AdminCustomerService {

    private final UserRepository userRepository;

    public AdminCustomerService(
            UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public Page<AdminCustomerListItem> searchCustomers(
            AdminCustomerSearchForm searchForm,
            int page,
            int size) {

        String username =
                normalize(searchForm.getUsername());

        String name =
                normalize(searchForm.getName());

        PageRequest pageable =
                PageRequest.of(
                        page,
                        size,
                        Sort.by(
                                Sort.Direction.DESC,
                                "id"));

        return userRepository.searchCustomers(
                        searchForm.getUserId(),
                        username,
                        name,
                        searchForm.getEnabled(),
                        pageable)
                .map(customer ->
                        new AdminCustomerListItem(
                                customer.getUserId(),
                                customer.getUsername(),
                                customer.getName(),
                                customer.getEnabled()));
    }

    private String normalize(String value) {

        if (value == null) {
            return null;
        }

        String normalized = value.trim();

        return normalized.isEmpty()
                ? null
                : normalized;
    }
}
