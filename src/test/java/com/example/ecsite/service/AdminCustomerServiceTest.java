package com.example.ecsite.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import com.example.ecsite.dto.AdminCustomerListItem;
import com.example.ecsite.form.AdminCustomerSearchForm;
import com.example.ecsite.repository.UserRepository;
import com.example.ecsite.repository.projection.AdminCustomerListProjection;

@ExtendWith(MockitoExtension.class)
class AdminCustomerServiceTest {

    @Mock
    private UserRepository userRepository;

    private AdminCustomerService adminCustomerService;

    @BeforeEach
    void setUp() {

        adminCustomerService =
                new AdminCustomerService(
                        userRepository);
    }

    @Test
    void searchCustomersPassesConditionsAndConvertsProjection() {

        AdminCustomerSearchForm form =
                new AdminCustomerSearchForm();

        form.setUserId(10L);
        form.setUsername("customer");
        form.setName("山田");
        form.setEnabled(true);

        AdminCustomerListProjection projection =
                projection(
                        10L,
                        "customer01",
                        "山田太郎",
                        true);

        when(userRepository.searchCustomers(
                eq(10L),
                eq("customer"),
                eq("山田"),
                eq(true),
                any(Pageable.class)))
                .thenReturn(
                        new PageImpl<>(
                                List.of(projection)));

        Page<AdminCustomerListItem> result =
                adminCustomerService.searchCustomers(
                        form,
                        0,
                        10);

        assertEquals(1, result.getTotalElements());

        AdminCustomerListItem customer =
                result.getContent().get(0);

        assertEquals(10L, customer.userId());
        assertEquals("customer01", customer.username());
        assertEquals("山田太郎", customer.name());
        assertEquals(true, customer.enabled());
    }

    @Test
    void searchCustomersTrimsUsernameAndName() {

        AdminCustomerSearchForm form =
                new AdminCustomerSearchForm();

        form.setUsername("  customer  ");
        form.setName("  山田  ");

        when(userRepository.searchCustomers(
                eq(null),
                eq("customer"),
                eq("山田"),
                eq(null),
                any(Pageable.class)))
                .thenReturn(Page.empty());

        adminCustomerService.searchCustomers(
                form,
                0,
                10);

        verify(userRepository)
                .searchCustomers(
                        eq(null),
                        eq("customer"),
                        eq("山田"),
                        eq(null),
                        any(Pageable.class));
    }

    @Test
    void searchCustomersConvertsBlankConditionsToNull() {

        AdminCustomerSearchForm form =
                new AdminCustomerSearchForm();

        form.setUsername("   ");
        form.setName("");

        when(userRepository.searchCustomers(
                eq(null),
                eq(null),
                eq(null),
                eq(null),
                any(Pageable.class)))
                .thenReturn(Page.empty());

        adminCustomerService.searchCustomers(
                form,
                0,
                10);

        verify(userRepository)
                .searchCustomers(
                        eq(null),
                        eq(null),
                        eq(null),
                        eq(null),
                        any(Pageable.class));
    }

    @Test
    void searchCustomersUsesRequestedPageAndSizeWithIdDescSort() {

        AdminCustomerSearchForm form =
                new AdminCustomerSearchForm();

        when(userRepository.searchCustomers(
                eq(null),
                eq(null),
                eq(null),
                eq(null),
                any(Pageable.class)))
                .thenReturn(Page.empty());

        adminCustomerService.searchCustomers(
                form,
                2,
                25);

        ArgumentCaptor<Pageable> pageableCaptor =
                ArgumentCaptor.forClass(
                        Pageable.class);

        verify(userRepository)
                .searchCustomers(
                        eq(null),
                        eq(null),
                        eq(null),
                        eq(null),
                        pageableCaptor.capture());

        Pageable pageable =
                pageableCaptor.getValue();

        assertEquals(2, pageable.getPageNumber());
        assertEquals(25, pageable.getPageSize());
        assertEquals(
                org.springframework.data.domain.Sort.Direction.DESC,
                pageable.getSort()
                        .getOrderFor("id")
                        .getDirection());
    }

    @Test
    void searchCustomersAllowsNullProfileName() {

        AdminCustomerSearchForm form =
                new AdminCustomerSearchForm();

        AdminCustomerListProjection projection =
                projection(
                        20L,
                        "no-profile",
                        null,
                        true);

        when(userRepository.searchCustomers(
                eq(null),
                eq(null),
                eq(null),
                eq(null),
                any(Pageable.class)))
                .thenReturn(
                        new PageImpl<>(
                                List.of(projection)));

        Page<AdminCustomerListItem> result =
                adminCustomerService.searchCustomers(
                        form,
                        0,
                        10);

        assertNull(
                result.getContent()
                        .get(0)
                        .name());
    }

    private AdminCustomerListProjection projection(
            Long userId,
            String username,
            String name,
            boolean enabled) {

        return new AdminCustomerListProjection() {

            @Override
            public Long getUserId() {
                return userId;
            }

            @Override
            public String getUsername() {
                return username;
            }

            @Override
            public String getName() {
                return name;
            }

            @Override
            public boolean getEnabled() {
                return enabled;
            }
        };
    }
}
