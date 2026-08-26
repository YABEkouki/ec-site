package com.example.ecsite.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.ecsite.entity.User;
import com.example.ecsite.entity.UserProfile;
import com.example.ecsite.form.UserProfileForm;
import com.example.ecsite.repository.UserProfileRepository;

@ExtendWith(MockitoExtension.class)
class UserProfileServiceTest {

    @Mock
    private UserProfileRepository userProfileRepository;

    @Mock
    private UserService userService;

    @Test
    void findByUserIdReturnsProfile() {

        Long userId = 1L;
        UserProfile profile = new UserProfile();

        when(userProfileRepository.findByUserId(userId))
                .thenReturn(Optional.of(profile));

        UserProfileService service = new UserProfileService(
                userProfileRepository,
                userService);

        UserProfile result = service.findByUserId(userId);

        assertSame(profile, result);
    }

    @Test
    void saveCreatesProfileWhenProfileDoesNotExist() {

        Long userId = 1L;

        User user = new User();

        UserProfileForm form = createForm();

        when(userProfileRepository.findByUserId(userId))
                .thenReturn(Optional.empty());
        when(userService.findById(userId))
                .thenReturn(user);

        UserProfileService service = new UserProfileService(
                userProfileRepository,
                userService);

        service.save(userId, form);

        ArgumentCaptor<UserProfile> captor =
                ArgumentCaptor.forClass(UserProfile.class);

        verify(userProfileRepository).save(captor.capture());

        UserProfile saved = captor.getValue();

        assertSame(user, saved.getUser());
        assertEquals("山田 太郎", saved.getName());
        assertEquals("123-4567", saved.getPostalCode());
        assertEquals("東京都", saved.getPrefecture());
        assertEquals("新宿区", saved.getCity());
        assertEquals("西新宿1-1-1", saved.getAddressLine());
        assertEquals("090-1234-5678", saved.getPhone());
    }

    @Test
    void saveUpdatesExistingProfile() {

        Long userId = 1L;

        UserProfile profile = new UserProfile();

        UserProfileForm form = createForm();
        form.setName("更新 太郎");

        when(userProfileRepository.findByUserId(userId))
                .thenReturn(Optional.of(profile));

        UserProfileService service = new UserProfileService(
                userProfileRepository,
                userService);

        service.save(userId, form);

        assertEquals("更新 太郎", profile.getName());

        verify(userProfileRepository).save(profile);
    }

    @Test
    void createFormCopiesProfileValues() {

        Long userId = 1L;

        UserProfile profile = new UserProfile();
        profile.setName("山田 太郎");
        profile.setPostalCode("123-4567");
        profile.setPrefecture("東京都");
        profile.setCity("新宿区");
        profile.setAddressLine("西新宿1-1-1");
        profile.setPhone("090-1234-5678");

        when(userProfileRepository.findByUserId(userId))
                .thenReturn(Optional.of(profile));

        UserProfileService service = new UserProfileService(
                userProfileRepository,
                userService);

        UserProfileForm form = service.createForm(userId);

        assertEquals("山田 太郎", form.getName());
        assertEquals("123-4567", form.getPostalCode());
        assertEquals("東京都", form.getPrefecture());
        assertEquals("新宿区", form.getCity());
        assertEquals("西新宿1-1-1", form.getAddressLine());
        assertEquals("090-1234-5678", form.getPhone());
    }

    private UserProfileForm createForm() {

        UserProfileForm form = new UserProfileForm();

        form.setName("山田 太郎");
        form.setPostalCode("123-4567");
        form.setPrefecture("東京都");
        form.setCity("新宿区");
        form.setAddressLine("西新宿1-1-1");
        form.setPhone("090-1234-5678");

        return form;
    }
}