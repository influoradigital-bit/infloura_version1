package com.influora.web;

import com.influora.common.ApiResponse;
import com.influora.security.AuthPrincipal;
import com.influora.service.UserService;
import com.influora.web.dto.user.UserDtos.UpdateProfileRequest;
import com.influora.web.dto.user.UserDtos.UserProfileDto;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserProfileDto>> me(@AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(userService.getProfile(principal.getUserId())));
    }

    @PatchMapping("/me")
    public ResponseEntity<ApiResponse<UserProfileDto>> updateMe(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody UpdateProfileRequest body) {
        return ResponseEntity.ok(ApiResponse.ok(userService.updateProfile(principal.getUserId(), body)));
    }
}
