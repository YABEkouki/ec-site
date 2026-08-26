package com.example.ecsite.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ecsite.entity.UserProfile;
import com.example.ecsite.form.UserProfileForm;
import com.example.ecsite.repository.UserProfileRepository;

@Service
public class UserProfileService {

    private final UserProfileRepository userProfileRepository;
    private final UserService userService;

    public UserProfileService(
            UserProfileRepository userProfileRepository,
            UserService userService) {

        this.userProfileRepository = userProfileRepository;
        this.userService = userService;
    }

    @Transactional(readOnly = true)
    public UserProfile findByUserId(Long userId) {

        return userProfileRepository.findByUserId(userId)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public UserProfileForm createForm(Long userId) {

        UserProfileForm form = new UserProfileForm();

        userProfileRepository.findByUserId(userId)
                .ifPresent(profile -> copyToForm(profile, form));

        return form;
    }

    @Transactional
    public void save(
            Long userId,
            UserProfileForm form) {

        UserProfile profile = userProfileRepository
                .findByUserId(userId)
                .orElseGet(() -> {
                    UserProfile newProfile = new UserProfile();
                    newProfile.setUser(userService.findById(userId));
                    return newProfile;
                });

        copyToEntity(form, profile);

        userProfileRepository.save(profile);
    }

    private void copyToForm(
            UserProfile profile,
            UserProfileForm form) {

        form.setName(profile.getName());
        form.setPostalCode(profile.getPostalCode());
        form.setPrefecture(profile.getPrefecture());
        form.setCity(profile.getCity());
        form.setAddressLine(profile.getAddressLine());
        form.setPhone(profile.getPhone());
    }

    private void copyToEntity(
            UserProfileForm form,
            UserProfile profile) {

        profile.setName(form.getName().trim());
        profile.setPostalCode(form.getPostalCode().trim());
        profile.setPrefecture(form.getPrefecture().trim());
        profile.setCity(form.getCity().trim());
        profile.setAddressLine(form.getAddressLine().trim());
        profile.setPhone(form.getPhone().trim());
    }
}