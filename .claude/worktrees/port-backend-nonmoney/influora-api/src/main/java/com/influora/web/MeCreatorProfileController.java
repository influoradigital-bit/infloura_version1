package com.influora.web;

import com.influora.common.ApiResponse;
import com.influora.security.AuthPrincipal;
import com.influora.service.CreatorProfileService;
import com.influora.web.dto.creator.CreatorProfileDtos.CreatorProfilePatchRequest;
import com.influora.web.dto.creator.CreatorProfileDtos.CreatorProfileSelfResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/me/creator-profile")
public class MeCreatorProfileController {

    private final CreatorProfileService creatorProfileService;

    public MeCreatorProfileController(CreatorProfileService creatorProfileService) {
        this.creatorProfileService = creatorProfileService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<CreatorProfileSelfResponse>> get(
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(creatorProfileService.getMyProfile(principal)));
    }

    @PatchMapping
    public ResponseEntity<ApiResponse<CreatorProfileSelfResponse>> patch(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody CreatorProfilePatchRequest body) {
        return ResponseEntity.ok(ApiResponse.ok(creatorProfileService.patchMyProfile(principal, body)));
    }
}
