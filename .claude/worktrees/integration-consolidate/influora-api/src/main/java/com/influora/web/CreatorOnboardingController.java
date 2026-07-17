package com.influora.web;

import com.influora.common.ApiResponse;
import com.influora.security.AuthPrincipal;
import com.influora.service.CreatorOnboardingService;
import com.influora.web.dto.onboarding.OnboardingDtos.CreatorIdResponse;
import com.influora.web.dto.onboarding.OnboardingDtos.CreatorKycRequest;
import com.influora.web.dto.onboarding.OnboardingDtos.CreatorPayoutRequest;
import com.influora.web.dto.onboarding.OnboardingDtos.CreatorPayoutResponse;
import com.influora.web.dto.onboarding.OnboardingDtos.CreatorProfileRequest;
import com.influora.web.dto.onboarding.OnboardingDtos.CreatorSocialRequest;
import com.influora.web.dto.onboarding.OnboardingDtos.CreatorSocialResponse;
import com.influora.web.dto.onboarding.OnboardingDtos.KycResponse;
import com.influora.web.dto.onboarding.OnboardingDtos.OkResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * N1 (Wave 6) — creator equivalent of {@code OnboardingController} (which only ever mapped
 * {@code /onboarding/brand/*}). Paths/verbs/shapes match src/lib/api.ts's {@code onboarding.*}
 * creator methods exactly (creator-onboarding.tsx was already calling these with no backend route
 * to hit).
 */
@RestController
@RequestMapping("/onboarding/creator")
public class CreatorOnboardingController {

    private final CreatorOnboardingService creatorOnboardingService;

    public CreatorOnboardingController(CreatorOnboardingService creatorOnboardingService) {
        this.creatorOnboardingService = creatorOnboardingService;
    }

    @PostMapping("/socials")
    public ResponseEntity<ApiResponse<CreatorSocialResponse>> connectSocial(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody CreatorSocialRequest body) {
        return ResponseEntity.ok(ApiResponse.ok(creatorOnboardingService.connectSocial(principal, body)));
    }

    @PostMapping("/profile")
    public ResponseEntity<ApiResponse<CreatorIdResponse>> saveProfile(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody CreatorProfileRequest body) {
        return ResponseEntity.ok(ApiResponse.ok(creatorOnboardingService.saveProfile(principal, body)));
    }

    @PostMapping("/complete")
    public ResponseEntity<ApiResponse<OkResponse>> complete(
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(creatorOnboardingService.complete(principal)));
    }

    @PostMapping("/kyc")
    public ResponseEntity<ApiResponse<KycResponse>> submitKyc(
            @AuthenticationPrincipal AuthPrincipal principal, @Valid @RequestBody CreatorKycRequest body) {
        return ResponseEntity.ok(ApiResponse.ok(creatorOnboardingService.submitKyc(principal, body)));
    }

    @PostMapping("/payout")
    public ResponseEntity<ApiResponse<CreatorPayoutResponse>> savePayout(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody CreatorPayoutRequest body) {
        return ResponseEntity.ok(ApiResponse.ok(creatorOnboardingService.savePayout(principal, body)));
    }
}
