package com.example.ecsite.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.ecsite.entity.ShippingAddress;
import com.example.ecsite.entity.User;
import com.example.ecsite.exception.ShippingAddressNotFoundException;
import com.example.ecsite.form.ShippingAddressForm;
import com.example.ecsite.repository.ShippingAddressRepository;

@ExtendWith(MockitoExtension.class)
class ShippingAddressServiceTest {

        @Mock
        private ShippingAddressRepository shippingAddressRepository;

        @Mock
        private UserService userService;

        @Test
        void findAllByUserIdReturnsUsersAddresses() {

                Long userId = 1L;

                List<ShippingAddress> addresses = List.of(new ShippingAddress(), new ShippingAddress());

                when(shippingAddressRepository
                                .findByUserIdOrderByDefaultAddressDescCreatedAtAsc(userId))
                                .thenReturn(addresses);

                ShippingAddressService service = createService();

                List<ShippingAddress> result = service.findAllByUserId(userId);

                assertSame(addresses, result);
        }

        @Test
        void findByIdAndUserIdReturnsOwnedAddress() {

                Long addressId = 10L;
                Long userId = 1L;

                ShippingAddress address = new ShippingAddress();

                when(shippingAddressRepository
                                .findByIdAndUserId(addressId, userId))
                                .thenReturn(Optional.of(address));

                ShippingAddressService service = createService();

                ShippingAddress result = service.findByIdAndUserId(addressId, userId);

                assertSame(address, result);
        }

        @Test
        void findByIdAndUserIdRejectsAddressNotOwnedByUser() {

                Long addressId = 10L;
                Long userId = 1L;

                when(shippingAddressRepository
                                .findByIdAndUserId(addressId, userId))
                                .thenReturn(Optional.empty());

                ShippingAddressService service = createService();

                assertThrows(
                                ShippingAddressNotFoundException.class,
                                () -> service.findByIdAndUserId(
                                                addressId,
                                                userId));
        }

        @Test
        void createMakesFirstAddressDefault() {

                Long userId = 1L;

                User user = new User();
                ShippingAddressForm form = createForm(false);

                when(shippingAddressRepository.existsByUserId(userId))
                                .thenReturn(false);
                when(userService.findById(userId))
                                .thenReturn(user);

                ShippingAddressService service = createService();

                service.create(userId, form);

                ArgumentCaptor<ShippingAddress> captor = ArgumentCaptor.forClass(ShippingAddress.class);

                verify(shippingAddressRepository).save(captor.capture());

                ShippingAddress saved = captor.getValue();

                assertSame(user, saved.getUser());
                assertTrue(saved.isDefaultAddress());
                assertEquals("自宅", saved.getName());
                assertEquals("山田 太郎", saved.getRecipientName());
        }

        @Test
        void createKeepsAdditionalAddressNonDefault() {

                Long userId = 1L;

                ShippingAddressForm form = createForm(false);

                when(shippingAddressRepository.existsByUserId(userId))
                                .thenReturn(true);

                User user = new User();

                when(userService.findById(userId))
                                .thenReturn(user);

                ShippingAddressService service = createService();

                service.create(userId, form);

                ArgumentCaptor<ShippingAddress> captor = ArgumentCaptor.forClass(ShippingAddress.class);

                verify(shippingAddressRepository).save(captor.capture());

                assertFalse(captor.getValue().isDefaultAddress());
        }

        @Test
        void createReplacesCurrentDefaultWhenRequested() {

                Long userId = 1L;

                ShippingAddress currentDefault = new ShippingAddress();
                currentDefault.setDefaultAddress(true);

                ShippingAddressForm form = createForm(true);

                when(shippingAddressRepository.existsByUserId(userId))
                                .thenReturn(true);
                when(shippingAddressRepository
                                .findByUserIdAndDefaultAddressTrue(userId))
                                .thenReturn(Optional.of(currentDefault));
                when(userService.findById(userId))
                                .thenReturn(new User());

                ShippingAddressService service = createService();

                service.create(userId, form);

                assertFalse(currentDefault.isDefaultAddress());

                verify(shippingAddressRepository)
                                .saveAndFlush(currentDefault);
        }

        @Test
        void updateCannotEditAnotherUsersAddress() {

                Long addressId = 10L;
                Long userId = 1L;

                ShippingAddressForm form = createForm(false);

                when(shippingAddressRepository
                                .findByIdAndUserId(addressId, userId))
                                .thenReturn(Optional.empty());

                ShippingAddressService service = createService();

                assertThrows(
                                ShippingAddressNotFoundException.class,
                                () -> service.update(
                                                addressId,
                                                userId,
                                                form));

                verify(shippingAddressRepository, never())
                                .save(org.mockito.ArgumentMatchers.any());
        }

        @Test
        void updateChangesAddressValues() {

                Long addressId = 10L;
                Long userId = 1L;

                ShippingAddress address = new ShippingAddress();

                ShippingAddressForm form = createForm(false);
                form.setName("勤務先");

                when(shippingAddressRepository
                                .findByIdAndUserId(addressId, userId))
                                .thenReturn(Optional.of(address));

                ShippingAddressService service = createService();

                service.update(addressId, userId, form);

                assertEquals("勤務先", address.getName());
                assertEquals("山田 太郎", address.getRecipientName());
                assertEquals("123-4567", address.getPostalCode());

                verify(shippingAddressRepository).save(address);
        }

        @Test
        void findDefaultAddressReturnsDefaultAddress() {

                Long userId = 1L;

                ShippingAddress address = new ShippingAddress();
                address.setDefaultAddress(true);

                when(shippingAddressRepository
                                .findByUserIdAndDefaultAddressTrue(userId))
                                .thenReturn(Optional.of(address));

                ShippingAddressService service = createService();

                Optional<ShippingAddress> result = service.findDefaultAddress(userId);

                assertTrue(result.isPresent());
                assertSame(address, result.get());
        }

        private ShippingAddressService createService() {

                return new ShippingAddressService(
                                shippingAddressRepository,
                                userService);
        }

        private ShippingAddressForm createForm(
                        boolean defaultAddress) {

                ShippingAddressForm form = new ShippingAddressForm();

                form.setName("自宅");
                form.setRecipientName("山田 太郎");
                form.setPostalCode("123-4567");
                form.setPrefecture("東京都");
                form.setCity("新宿区");
                form.setAddressLine("西新宿1-1-1");
                form.setPhone("090-1234-5678");
                form.setDefaultAddress(defaultAddress);

                return form;
        }

        @Test
        void updateFlushesCurrentDefaultBeforeSettingNewDefault() {

                Long addressId = 20L;
                Long userId = 1L;

                ShippingAddress currentDefault = new ShippingAddress();
                currentDefault.setDefaultAddress(true);

                ShippingAddress address = new ShippingAddress();
                address.setDefaultAddress(false);

                ShippingAddressForm form = createForm(true);

                when(shippingAddressRepository
                                .findByIdAndUserId(addressId, userId))
                                .thenReturn(Optional.of(address));

                when(shippingAddressRepository
                                .findByUserIdAndDefaultAddressTrue(userId))
                                .thenReturn(Optional.of(currentDefault));

                ShippingAddressService service = createService();

                service.update(
                                addressId,
                                userId,
                                form);

                assertFalse(
                                currentDefault.isDefaultAddress());

                assertTrue(
                                address.isDefaultAddress());

                verify(shippingAddressRepository)
                                .saveAndFlush(currentDefault);

                verify(shippingAddressRepository)
                                .save(address);
        }

        @Test
        void hasAddressReturnsRepositoryResult() {

                Long userId = 1L;

                when(shippingAddressRepository.existsByUserId(userId))
                                .thenReturn(true);

                ShippingAddressService service = createService();

                boolean result = service.hasAddress(userId);

                assertTrue(result);

                verify(shippingAddressRepository)
                                .existsByUserId(userId);
        }
}