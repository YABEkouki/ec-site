package com.example.ecsite.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import com.example.ecsite.dto.AdminCustomerDetail;
import com.example.ecsite.dto.AdminCustomerListItem;
import com.example.ecsite.dto.AdminCustomerPurchaseSummary;
import com.example.ecsite.entity.ShippingAddress;
import com.example.ecsite.entity.User;
import com.example.ecsite.entity.UserProfile;
import com.example.ecsite.exception.CustomerNotFoundException;
import com.example.ecsite.form.AdminCustomerSearchForm;
import com.example.ecsite.repository.OrderRepository;
import com.example.ecsite.repository.ShippingAddressRepository;
import com.example.ecsite.repository.UserProfileRepository;
import com.example.ecsite.repository.UserRepository;
import com.example.ecsite.repository.projection.AdminCustomerListProjection;
import com.example.ecsite.repository.projection.AdminCustomerPurchaseSummaryProjection;

@ExtendWith(MockitoExtension.class)
class AdminCustomerServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private UserProfileRepository userProfileRepository;

    @Mock
    private ShippingAddressRepository shippingAddressRepository;

    private AdminCustomerService adminCustomerService;

    @BeforeEach
    void setUp() {

        adminCustomerService = new AdminCustomerService(
                userRepository,
                userProfileRepository,
                shippingAddressRepository,
                orderRepository);
    }

    @Test
    void searchCustomersPassesConditionsAndConvertsProjection() {

        AdminCustomerSearchForm form = new AdminCustomerSearchForm();

        form.setUserId(10L);
        form.setUsername("customer");
        form.setName("山田");
        form.setEnabled(true);
        form.setHasOrders(true);
        form.setHasPurchases(false);

        AdminCustomerListProjection projection = projection(
                10L,
                "customer01",
                "山田太郎",
                true);

        when(userRepository.searchCustomers(
                eq(10L),
                eq("customer"),
                eq("山田"),
                eq(true),
                eq(true),
                eq(false),
                any(Pageable.class)))
                .thenReturn(
                        new PageImpl<>(
                                List.of(projection)));

        Page<AdminCustomerListItem> result = adminCustomerService.searchCustomers(
                form,
                0,
                10);

        assertEquals(1, result.getTotalElements());

        AdminCustomerListItem customer = result.getContent().get(0);

        assertEquals(10L, customer.userId());
        assertEquals("customer01", customer.username());
        assertEquals("山田太郎", customer.name());
        assertEquals(true, customer.enabled());
    }

    @Test
    void searchCustomersTrimsUsernameAndName() {

        AdminCustomerSearchForm form = new AdminCustomerSearchForm();

        form.setUsername("  customer  ");
        form.setName("  山田  ");

        when(userRepository.searchCustomers(
                eq(null),
                eq("customer"),
                eq("山田"),
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
                        eq("customer"),
                        eq("山田"),
                        eq(null),
                        eq(null),
                        eq(null),
                        any(Pageable.class));
    }

    @Test
    void searchCustomersConvertsBlankConditionsToNull() {

        AdminCustomerSearchForm form = new AdminCustomerSearchForm();

        form.setUsername("   ");
        form.setName("");

        when(userRepository.searchCustomers(
                eq(null),
                eq(null),
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
                        eq(null),
                        eq(null),
                        any(Pageable.class));
    }

    @Test
    void searchCustomersUsesRequestedPageAndSizeWithIdDescSort() {

        AdminCustomerSearchForm form = new AdminCustomerSearchForm();

        when(userRepository.searchCustomers(
                eq(null),
                eq(null),
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

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(
                Pageable.class);

        verify(userRepository)
                .searchCustomers(
                        eq(null),
                        eq(null),
                        eq(null),
                        eq(null),
                        eq(null),
                        eq(null),
                        pageableCaptor.capture());

        Pageable pageable = pageableCaptor.getValue();

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

        AdminCustomerSearchForm form = new AdminCustomerSearchForm();

        AdminCustomerListProjection projection = projection(
                20L,
                "no-profile",
                null,
                true);

        when(userRepository.searchCustomers(
                eq(null),
                eq(null),
                eq(null),
                eq(null),
                eq(null),
                eq(null),
                any(Pageable.class)))
                .thenReturn(
                        new PageImpl<>(
                                List.of(projection)));

        Page<AdminCustomerListItem> result = adminCustomerService.searchCustomers(
                form,
                0,
                10);

        assertNull(
                result.getContent()
                        .get(0)
                        .name());
    }

    @Test
    void findCustomerDetailReturnsProfileAndShippingAddresses() {

        User user = new User();
        ReflectionTestUtils.setField(user, "id", 10L);
        user.setUsername("customer01");
        user.setEmail("customer01@example.com");
        user.setEnabled(true);

        UserProfile profile = new UserProfile();
        profile.setName("山田太郎");
        profile.setPostalCode("100-0001");
        profile.setPrefecture("東京都");
        profile.setCity("千代田区");
        profile.setAddressLine("千代田1-1");
        profile.setPhone("090-1111-2222");

        ShippingAddress defaultAddress = new ShippingAddress();
        defaultAddress.setName("自宅");
        defaultAddress.setRecipientName("山田太郎");
        defaultAddress.setPostalCode("100-0001");
        defaultAddress.setPrefecture("東京都");
        defaultAddress.setCity("千代田区");
        defaultAddress.setAddressLine("千代田1-1");
        defaultAddress.setPhone("090-1111-2222");
        defaultAddress.setDefaultAddress(true);

        ShippingAddress otherAddress = new ShippingAddress();
        otherAddress.setName("勤務先");
        otherAddress.setRecipientName("山田太郎");
        otherAddress.setPostalCode("160-0022");
        otherAddress.setPrefecture("東京都");
        otherAddress.setCity("新宿区");
        otherAddress.setAddressLine("新宿1-1");
        otherAddress.setPhone("03-1111-2222");
        otherAddress.setDefaultAddress(false);

        when(userRepository.findById(10L))
                .thenReturn(Optional.of(user));

        when(userProfileRepository.findByUserId(10L))
                .thenReturn(Optional.of(profile));

        when(shippingAddressRepository
                .findByUserIdOrderByDefaultAddressDescCreatedAtAsc(10L))
                .thenReturn(List.of(defaultAddress, otherAddress));

        AdminCustomerDetail result = adminCustomerService.findCustomerDetail(10L);

        assertEquals(10L, result.userId());
        assertEquals("customer01", result.username());
        assertEquals("customer01@example.com", result.email());
        assertTrue(result.enabled());

        assertEquals("山田太郎", result.name());
        assertEquals("100-0001", result.postalCode());
        assertEquals("東京都", result.prefecture());
        assertEquals("千代田区", result.city());
        assertEquals("千代田1-1", result.addressLine());
        assertEquals("090-1111-2222", result.phone());

        assertEquals(2, result.shippingAddresses().size());

        assertEquals(
                "自宅",
                result.shippingAddresses().get(0).name());
        assertEquals(
                "山田太郎",
                result.shippingAddresses().get(0).recipientName());
        assertTrue(
                result.shippingAddresses().get(0).defaultAddress());

        assertEquals(
                "勤務先",
                result.shippingAddresses().get(1).name());
        assertFalse(
                result.shippingAddresses().get(1).defaultAddress());
    }

    @Test
    void findCustomerDetailAllowsMissingProfile() {

        User user = new User();
        ReflectionTestUtils.setField(user, "id", 20L);
        user.setUsername("no-profile");
        user.setEnabled(true);

        when(userRepository.findById(20L))
                .thenReturn(Optional.of(user));

        when(userProfileRepository.findByUserId(20L))
                .thenReturn(Optional.empty());

        when(shippingAddressRepository
                .findByUserIdOrderByDefaultAddressDescCreatedAtAsc(20L))
                .thenReturn(List.of());

        AdminCustomerDetail result = adminCustomerService.findCustomerDetail(20L);

        assertEquals(20L, result.userId());
        assertEquals("no-profile", result.username());

        assertNull(result.name());
        assertNull(result.postalCode());
        assertNull(result.prefecture());
        assertNull(result.city());
        assertNull(result.addressLine());
        assertNull(result.phone());
    }

    @Test
    void findCustomerDetailReturnsEmptyShippingAddressesWhenNoneExist() {

        User user = new User();
        ReflectionTestUtils.setField(user, "id", 30L);
        user.setUsername("no-address");
        user.setEnabled(false);

        when(userRepository.findById(30L))
                .thenReturn(Optional.of(user));

        when(userProfileRepository.findByUserId(30L))
                .thenReturn(Optional.empty());

        when(shippingAddressRepository
                .findByUserIdOrderByDefaultAddressDescCreatedAtAsc(30L))
                .thenReturn(List.of());

        AdminCustomerDetail result = adminCustomerService.findCustomerDetail(30L);

        assertFalse(result.enabled());
        assertTrue(result.shippingAddresses().isEmpty());
    }

    @Test
    void findCustomerDetailThrowsNotFoundWhenCustomerDoesNotExist() {

        when(userRepository.findById(999L))
                .thenReturn(Optional.empty());

        assertThrows(
                CustomerNotFoundException.class,
                () -> adminCustomerService.findCustomerDetail(999L));
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

    @Test
    void getPurchaseSummaryReturnsMappedSummary() {
        Long userId = 1L;

        AdminCustomerPurchaseSummaryProjection projection = mock(AdminCustomerPurchaseSummaryProjection.class);

        when(projection.getOrderCount()).thenReturn(4L);
        when(projection.getPurchaseAmount()).thenReturn(5000L);
        when(projection.getLastOrderedAt())
                .thenReturn(LocalDateTime.of(2026, 9, 4, 13, 0));

        when(orderRepository.findCustomerPurchaseSummary(userId))
                .thenReturn(projection);

        AdminCustomerPurchaseSummary summary = adminCustomerService.getPurchaseSummary(userId);

        assertThat(summary.orderCount()).isEqualTo(4L);
        assertThat(summary.purchaseAmount()).isEqualTo(5000L);
        assertThat(summary.lastOrderedAt())
                .isEqualTo(LocalDateTime.of(2026, 9, 4, 13, 0));
    }

}
