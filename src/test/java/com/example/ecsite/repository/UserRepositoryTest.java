package com.example.ecsite.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.OrderStatus;
import com.example.ecsite.entity.User;
import com.example.ecsite.entity.UserProfile;
import com.example.ecsite.repository.projection.AdminCustomerListProjection;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Test
    void searchCustomersReturnsAllCustomers() {

        User first = createUser(
                "customer-search-all-1",
                true);

        User second = createUser(
                "customer-search-all-2",
                false);

        createProfile(first, "顧客検索一郎");
        createProfile(second, "顧客検索二郎");

        Page<AdminCustomerListProjection> result = search(
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                10);

        assertEquals(2, result.getTotalElements());

        assertEquals(
                second.getId(),
                result.getContent().get(0).getUserId());

        assertEquals(
                first.getId(),
                result.getContent().get(1).getUserId());
    }

    @Test
    void searchCustomersFiltersByUserId() {

        User target = createUser(
                "customer-search-id-target",
                true);

        createUser(
                "customer-search-id-other",
                true);

        Page<AdminCustomerListProjection> result = search(
                target.getId(),
                null,
                null,
                null,
                null,
                null,
                0,
                10);

        assertEquals(1, result.getTotalElements());
        assertEquals(
                target.getId(),
                result.getContent().get(0).getUserId());
    }

    @Test
    void searchCustomersFiltersByUsernamePartialMatch() {

        User target = createUser(
                "customer-search-username-target",
                true);

        createUser(
                "customer-search-username-other",
                true);

        Page<AdminCustomerListProjection> result = search(
                null,
                "target",
                null,
                null,
                null,
                null,
                0,
                10);

        assertEquals(1, result.getTotalElements());
        assertEquals(
                target.getId(),
                result.getContent().get(0).getUserId());
    }

    @Test
    void searchCustomersFiltersByNamePartialMatch() {

        User target = createUser(
                "customer-search-name-target",
                true);

        User other = createUser(
                "customer-search-name-other",
                true);

        createProfile(target, "山田太郎");
        createProfile(other, "佐藤花子");

        Page<AdminCustomerListProjection> result = search(
                null,
                null,
                "山田",
                null,
                null,
                null,
                0,
                10);

        assertEquals(1, result.getTotalElements());
        assertEquals(
                target.getId(),
                result.getContent().get(0).getUserId());
        assertEquals(
                "山田太郎",
                result.getContent().get(0).getName());
    }

    @Test
    void searchCustomersFiltersByEnabledTrue() {

        User enabled = createUser(
                "customer-search-enabled-true",
                true);

        createUser(
                "customer-search-enabled-false",
                false);

        Page<AdminCustomerListProjection> result = search(
                null,
                null,
                null,
                true,
                null,
                null,
                0,
                10);

        assertEquals(1, result.getTotalElements());
        assertEquals(
                enabled.getId(),
                result.getContent().get(0).getUserId());
    }

    @Test
    void searchCustomersFiltersByEnabledFalse() {

        createUser(
                "customer-search-disabled-true",
                true);

        User disabled = createUser(
                "customer-search-disabled-false",
                false);

        Page<AdminCustomerListProjection> result = search(
                null,
                null,
                null,
                false,
                null,
                null,
                0,
                10);

        assertEquals(1, result.getTotalElements());
        assertEquals(
                disabled.getId(),
                result.getContent().get(0).getUserId());
    }

    @Test
    void searchCustomersCombinesConditionsWithAnd() {

        User target = createUser(
                "customer-search-and-target",
                true);

        User wrongName = createUser(
                "customer-search-and-wrong-name",
                true);

        User disabled = createUser(
                "customer-search-and-disabled-target",
                false);

        createProfile(target, "検索太郎");
        createProfile(wrongName, "別人");
        createProfile(disabled, "検索太郎");

        Page<AdminCustomerListProjection> result = search(
                null,
                "target",
                "検索",
                true,
                null,
                null,
                0,
                10);

        assertEquals(1, result.getTotalElements());
        assertEquals(
                target.getId(),
                result.getContent().get(0).getUserId());
    }

    @Test
    void searchCustomersReturnsCustomerWithoutProfile() {

        User user = createUser(
                "customer-search-no-profile",
                true);

        Page<AdminCustomerListProjection> result = search(
                user.getId(),
                null,
                null,
                null,
                null,
                null,
                0,
                10);

        assertEquals(1, result.getTotalElements());
        assertEquals(
                user.getId(),
                result.getContent().get(0).getUserId());

        assertNull(
                result.getContent().get(0).getName());
    }

    @Test
    void searchCustomersDoesNotMatchCustomerWithoutProfileWhenSearchingName() {

        createUser(
                "customer-search-no-profile-name",
                true);

        Page<AdminCustomerListProjection> result = search(
                null,
                null,
                "存在しない氏名",
                null,
                null,
                null,
                0,
                10);

        assertEquals(0, result.getTotalElements());
    }

    @Test
    void searchCustomersReturnsNewestIdFirst() {

        User first = createUser(
                "customer-search-sort-1",
                true);

        User second = createUser(
                "customer-search-sort-2",
                true);

        User third = createUser(
                "customer-search-sort-3",
                true);

        Page<AdminCustomerListProjection> result = search(
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                10);

        assertEquals(
                third.getId(),
                result.getContent().get(0).getUserId());

        assertEquals(
                second.getId(),
                result.getContent().get(1).getUserId());

        assertEquals(
                first.getId(),
                result.getContent().get(2).getUserId());
    }

    @Test
    void searchCustomersSupportsPaging() {

        createUser(
                "customer-search-page-1",
                true);

        User second = createUser(
                "customer-search-page-2",
                true);

        User third = createUser(
                "customer-search-page-3",
                true);

        Page<AdminCustomerListProjection> firstPage = search(
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                2);

        Page<AdminCustomerListProjection> secondPage = search(
                null,
                null,
                null,
                null,
                null,
                null,
                1,
                2);

        assertEquals(3, firstPage.getTotalElements());
        assertEquals(2, firstPage.getContent().size());
        assertEquals(1, secondPage.getContent().size());

        assertEquals(
                third.getId(),
                firstPage.getContent().get(0).getUserId());

        assertEquals(
                second.getId(),
                firstPage.getContent().get(1).getUserId());
    }

    @Test
    void searchCustomersFiltersByHasOrdersTrue() {

        User withOrder = createUser(
                "customer-search-has-orders",
                true);

        createUser(
                "customer-search-no-orders",
                true);

        createOrder(
                withOrder,
                OrderStatus.ORDERED);

        Page<AdminCustomerListProjection> result = search(
                null,
                null,
                null,
                null,
                true,
                null,
                0,
                10);

        assertEquals(1, result.getTotalElements());
        assertEquals(
                withOrder.getId(),
                result.getContent().get(0).getUserId());
    }

    @Test
    void searchCustomersFiltersByHasOrdersFalse() {

        User withOrder = createUser(
                "customer-search-with-orders",
                true);

        User withoutOrder = createUser(
                "customer-search-without-orders",
                true);

        createOrder(
                withOrder,
                OrderStatus.ORDERED);

        Page<AdminCustomerListProjection> result = search(
                null,
                null,
                null,
                null,
                false,
                null,
                0,
                10);

        assertEquals(1, result.getTotalElements());
        assertEquals(
                withoutOrder.getId(),
                result.getContent().get(0).getUserId());
    }

    @Test
    void searchCustomersFiltersByHasPurchasesTrueWithPaidOrder() {

        User purchased = createUser(
                "customer-search-paid",
                true);

        createUser(
                "customer-search-not-purchased",
                true);

        createOrder(
                purchased,
                OrderStatus.PAID);

        Page<AdminCustomerListProjection> result = search(
                null,
                null,
                null,
                null,
                null,
                true,
                0,
                10);

        assertEquals(1, result.getTotalElements());
        assertEquals(
                purchased.getId(),
                result.getContent().get(0).getUserId());
    }

    @Test
    void searchCustomersFiltersByHasPurchasesTrueWithShippedOrder() {

        User purchased = createUser(
                "customer-search-shipped",
                true);

        createUser(
                "customer-search-not-shipped",
                true);

        createOrder(
                purchased,
                OrderStatus.SHIPPED);

        Page<AdminCustomerListProjection> result = search(
                null,
                null,
                null,
                null,
                null,
                true,
                0,
                10);

        assertEquals(1, result.getTotalElements());
        assertEquals(
                purchased.getId(),
                result.getContent().get(0).getUserId());
    }

    @Test
    void searchCustomersFiltersByHasPurchasesFalseWithNoOrders() {

        User withoutOrder = createUser(
                "customer-search-no-order-purchase",
                true);

        User purchased = createUser(
                "customer-search-purchased-other",
                true);

        createOrder(
                purchased,
                OrderStatus.PAID);

        Page<AdminCustomerListProjection> result = search(
                null,
                null,
                null,
                null,
                null,
                false,
                0,
                10);

        assertEquals(1, result.getTotalElements());
        assertEquals(
                withoutOrder.getId(),
                result.getContent().get(0).getUserId());
    }

    @Test
    void searchCustomersFiltersByHasPurchasesFalseWithOrderedOnly() {

        User orderedOnly = createUser(
                "customer-search-ordered-only",
                true);

        User purchased = createUser(
                "customer-search-paid-other",
                true);

        createOrder(
                orderedOnly,
                OrderStatus.ORDERED);

        createOrder(
                purchased,
                OrderStatus.PAID);

        Page<AdminCustomerListProjection> result = search(
                null,
                null,
                null,
                null,
                null,
                false,
                0,
                10);

        assertEquals(1, result.getTotalElements());
        assertEquals(
                orderedOnly.getId(),
                result.getContent().get(0).getUserId());
    }

    @Test
    void searchCustomersFiltersByHasPurchasesFalseWithCancelledOnly() {

        User cancelledOnly = createUser(
                "customer-search-cancelled-only",
                true);

        User purchased = createUser(
                "customer-search-paid-control",
                true);

        createOrder(
                cancelledOnly,
                OrderStatus.CANCELLED);

        createOrder(
                purchased,
                OrderStatus.PAID);

        Page<AdminCustomerListProjection> result = search(
                null,
                null,
                null,
                null,
                null,
                false,
                0,
                10);

        assertEquals(1, result.getTotalElements());
        assertEquals(
                cancelledOnly.getId(),
                result.getContent().get(0).getUserId());
    }

    @Test
    void searchCustomersCombinesPurchaseConditionsWithExistingConditions() {

        User target = createUser(
                "customer-search-purchase-target",
                true);

        User noPurchase = createUser(
                "customer-search-purchase-no-purchase-target",
                true);

        User disabled = createUser(
                "customer-search-purchase-disabled-target",
                false);

        createProfile(target, "購入検索太郎");
        createProfile(noPurchase, "購入検索太郎");
        createProfile(disabled, "購入検索太郎");

        createOrder(
                target,
                OrderStatus.PAID);

        createOrder(
                noPurchase,
                OrderStatus.ORDERED);

        createOrder(
                disabled,
                OrderStatus.PAID);

        Page<AdminCustomerListProjection> result = search(
                null,
                "target",
                "購入検索",
                true,
                true,
                true,
                0,
                10);

        assertEquals(1, result.getTotalElements());
        assertEquals(
                target.getId(),
                result.getContent().get(0).getUserId());
    }

    @Test
    void searchCustomersReturnsEmptyWhenNoOrdersAndHasPurchasesTrue() {

        User user = createUser(
                "customer-search-contradictory",
                true);

        createOrder(
                user,
                OrderStatus.PAID);

        Page<AdminCustomerListProjection> result = search(
                null,
                null,
                null,
                null,
                false,
                true,
                0,
                10);

        assertEquals(0, result.getTotalElements());
    }

    private Page<AdminCustomerListProjection> search(
            Long userId,
            String username,
            String name,
            Boolean enabled,
            Boolean hasOrders,
            Boolean hasPurchases,
            int page,
            int size) {

        return userRepository.searchCustomers(
                userId,
                username,
                name,
                enabled,
                hasOrders,
                hasPurchases,
                PageRequest.of(
                        page,
                        size,
                        Sort.by(
                                Sort.Direction.DESC,
                                "id")));
    }

    private User createUser(
            String username,
            boolean enabled) {

        User user = new User();
        user.setUsername(username);
        user.setPassword("password");
        user.setEnabled(enabled);

        return userRepository.saveAndFlush(user);
    }

    private UserProfile createProfile(
            User user,
            String name) {

        UserProfile profile = new UserProfile();

        profile.setUser(user);
        profile.setName(name);
        profile.setPostalCode("1000001");
        profile.setPrefecture("東京都");
        profile.setCity("千代田区");
        profile.setAddressLine("千代田1-1");
        profile.setPhone("09012345678");

        return userProfileRepository.saveAndFlush(profile);
    }

    private Order createOrder(
            User user,
            OrderStatus status) {

        Order order = new Order(
                user.getId(),
                1000);

        if (status == OrderStatus.PAID) {
            order.markAsPaid();
        } else if (status == OrderStatus.SHIPPED) {
            order.markAsPaid();
            order.markAsShipped();
        } else if (status == OrderStatus.CANCELLED) {
            order.cancel();
        }

        return orderRepository.saveAndFlush(order);
    }

}
