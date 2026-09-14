package com.example.ecsite.service;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.dto.AdminCustomerDetail;
import com.example.ecsite.dto.AdminCustomerListItem;
import com.example.ecsite.dto.AdminCustomerShippingAddress;
import com.example.ecsite.entity.User;
import com.example.ecsite.entity.UserProfile;
import com.example.ecsite.exception.CustomerNotFoundException;
import com.example.ecsite.form.AdminCustomerSearchForm;
import com.example.ecsite.repository.ShippingAddressRepository;
import com.example.ecsite.repository.UserProfileRepository;
import com.example.ecsite.repository.UserRepository;

@Service
@Transactional(readOnly = true)
public class AdminCustomerService {

    private final UserRepository userRepository;
    private final UserProfileRepository userProfileRepository;
    private final ShippingAddressRepository shippingAddressRepository;

    public AdminCustomerService(
            UserRepository userRepository,
            UserProfileRepository userProfileRepository,
            ShippingAddressRepository shippingAddressRepository) {
        this.userRepository = userRepository;
        this.userProfileRepository = userProfileRepository;
        this.shippingAddressRepository = shippingAddressRepository;
    }

    public Page<AdminCustomerListItem> searchCustomers(
            AdminCustomerSearchForm searchForm,
            int page,
            int size) {

        String username = normalize(searchForm.getUsername());

        String name = normalize(searchForm.getName());

        PageRequest pageable = PageRequest.of(
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
                .map(customer -> new AdminCustomerListItem(
                        customer.getUserId(),
                        customer.getUsername(),
                        customer.getName(),
                        customer.getEnabled()));
    }

    public AdminCustomerDetail findCustomerDetail(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomerNotFoundException(userId));

        UserProfile profile = userProfileRepository.findByUserId(userId)
                .orElse(null);

        List<AdminCustomerShippingAddress> shippingAddresses = shippingAddressRepository
                .findByUserIdOrderByDefaultAddressDescCreatedAtAsc(userId)
                .stream()
                .map(address -> new AdminCustomerShippingAddress(
                        address.getName(),
                        address.getRecipientName(),
                        address.getPostalCode(),
                        address.getPrefecture(),
                        address.getCity(),
                        address.getAddressLine(),
                        address.getPhone(),
                        address.isDefaultAddress()))
                .toList();

        return new AdminCustomerDetail(
                user.getId(),
                user.getUsername(),
                user.isEnabled(),
                profile != null ? profile.getName() : null,
                profile != null ? profile.getPostalCode() : null,
                profile != null ? profile.getPrefecture() : null,
                profile != null ? profile.getCity() : null,
                profile != null ? profile.getAddressLine() : null,
                profile != null ? profile.getPhone() : null,
                shippingAddresses);
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
