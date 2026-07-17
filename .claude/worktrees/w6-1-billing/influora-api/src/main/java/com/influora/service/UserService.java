package com.influora.service;

import com.influora.common.ApiException;
import com.influora.domain.entity.User;
import com.influora.repository.UserRepository;
import com.influora.web.dto.user.UserDtos.UpdateProfileRequest;
import com.influora.web.dto.user.UserDtos.UserProfileDto;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public UserProfileDto getProfile(String userId) {
        User user =
                userRepository
                        .findById(userId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "USER_NOT_FOUND", "User not found", HttpStatus.NOT_FOUND));
        return toDto(user);
    }

    @Transactional
    public UserProfileDto updateProfile(String userId, UpdateProfileRequest req) {
        User user =
                userRepository
                        .findById(userId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "USER_NOT_FOUND", "User not found", HttpStatus.NOT_FOUND));

        if (req.firstName() != null) {
            user.setFirstName(req.firstName());
        }
        if (req.lastName() != null) {
            user.setLastName(req.lastName());
        }
        if (req.displayName() != null) {
            user.setDisplayName(req.displayName());
        }
        if (req.timezone() != null) {
            user.setTimezone(req.timezone());
        }
        if (req.avatarUrl() != null) {
            user.setAvatarUrl(req.avatarUrl());
        }

        userRepository.save(user);
        return toDto(user);
    }

    private static UserProfileDto toDto(User user) {
        return new UserProfileDto(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getFirstName(),
                user.getLastName(),
                user.getUserType(),
                user.getStatus(),
                user.getAvatarUrl(),
                user.isEmailVerified(),
                user.isPhoneVerified(),
                user.getTimezone(),
                user.getCreatedAt());
    }
}
