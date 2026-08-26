package com.example.ecsite.service;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.entity.ShippingAddress;
import com.example.ecsite.exception.ShippingAddressNotFoundException;
import com.example.ecsite.form.ShippingAddressForm;
import com.example.ecsite.repository.ShippingAddressRepository;

@Service
public class ShippingAddressService {

        private final ShippingAddressRepository shippingAddressRepository;
        private final UserService userService;

        public ShippingAddressService(
                        ShippingAddressRepository shippingAddressRepository,
                        UserService userService) {

                this.shippingAddressRepository = shippingAddressRepository;
                this.userService = userService;
        }

        @Transactional(readOnly = true)
        public List<ShippingAddress> findAllByUserId(
                        Long userId) {

                return shippingAddressRepository
                                .findByUserIdOrderByDefaultAddressDescCreatedAtAsc(
                                                userId);
        }

        @Transactional(readOnly = true)
        public ShippingAddress findByIdAndUserId(
                        Long addressId,
                        Long userId) {

                return shippingAddressRepository
                                .findByIdAndUserId(addressId, userId)
                                .orElseThrow(
                                                () -> new ShippingAddressNotFoundException(
                                                                addressId));
        }

        @Transactional(readOnly = true)
        public Optional<ShippingAddress> findDefaultAddress(
                        Long userId) {

                return shippingAddressRepository
                                .findByUserIdAndDefaultAddressTrue(userId);
        }

        @Transactional
        public void create(
                        Long userId,
                        ShippingAddressForm form) {

                boolean firstAddress = !shippingAddressRepository.existsByUserId(userId);

                boolean makeDefault = firstAddress || form.isDefaultAddress();

                if (makeDefault && !firstAddress) {
                        clearCurrentDefault(userId);
                }

                ShippingAddress address = new ShippingAddress();

                address.setUser(userService.findById(userId));

                copyToEntity(form, address);

                address.setDefaultAddress(makeDefault);

                shippingAddressRepository.save(address);
        }

        @Transactional
        public void update(
                        Long addressId,
                        Long userId,
                        ShippingAddressForm form) {

                ShippingAddress address = findByIdAndUserId(addressId, userId);

                if (form.isDefaultAddress()
                                && !address.isDefaultAddress()) {

                        clearCurrentDefault(userId);
                }

                boolean keepDefault = address.isDefaultAddress()
                                || form.isDefaultAddress();

                copyToEntity(form, address);

                address.setDefaultAddress(keepDefault);

                shippingAddressRepository.save(address);
        }

        @Transactional(readOnly = true)
        public ShippingAddressForm createForm(
                        Long addressId,
                        Long userId) {

                ShippingAddress address = findByIdAndUserId(addressId, userId);

                ShippingAddressForm form = new ShippingAddressForm();

                form.setName(address.getName());
                form.setRecipientName(address.getRecipientName());
                form.setPostalCode(address.getPostalCode());
                form.setPrefecture(address.getPrefecture());
                form.setCity(address.getCity());
                form.setAddressLine(address.getAddressLine());
                form.setPhone(address.getPhone());
                form.setDefaultAddress(address.isDefaultAddress());

                return form;
        }

        private void clearCurrentDefault(Long userId) {

                shippingAddressRepository
                                .findByUserIdAndDefaultAddressTrue(userId)
                                .ifPresent(currentDefault -> {

                                        currentDefault.setDefaultAddress(false);

                                        shippingAddressRepository.saveAndFlush(
                                                        currentDefault);
                                });
        }

        private void copyToEntity(
                        ShippingAddressForm form,
                        ShippingAddress address) {

                address.setName(form.getName().trim());
                address.setRecipientName(
                                form.getRecipientName().trim());
                address.setPostalCode(
                                form.getPostalCode().trim());
                address.setPrefecture(
                                form.getPrefecture().trim());
                address.setCity(form.getCity().trim());
                address.setAddressLine(
                                form.getAddressLine().trim());
                address.setPhone(form.getPhone().trim());
        }

        @Transactional(readOnly = true)
        public boolean hasAddress(Long userId) {
                return shippingAddressRepository.existsByUserId(userId);
        }
}