package com.influora.web;

import com.influora.common.ApiResponse;
import com.influora.security.AuthPrincipal;
import com.influora.service.OnboardingService;
import com.influora.web.dto.onboarding.OnboardingDtos.BrandCompanyRequest;
import com.influora.web.dto.onboarding.OnboardingDtos.KycRequest;
import com.influora.web.dto.onboarding.OnboardingDtos.KycResponse;
import com.influora.web.dto.onboarding.OnboardingDtos.OkResponse;
import com.influora.web.dto.onboarding.OnboardingDtos.WorkspaceIdResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/onboarding/brand")
public class OnboardingController {

    private final OnboardingService onboardingService;

    public OnboardingController(OnboardingService onboardingService) {
        this.onboardingService = onboardingService;
    }

    @PostMapping("/company")
    public ResponseEntity<ApiResponse<WorkspaceIdResponse>> saveCompany(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody BrandCompanyRequest body) {
        return ResponseEntity.ok(ApiResponse.ok(onboardingService.saveBrandCompany(principal, body)));
    }

    @PostMapping("/complete")
    public ResponseEntity<ApiResponse<OkResponse>> complete(
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(onboardingService.completeBrand(principal)));
    }

    @PostMapping("/kyc")
    public ResponseEntity<ApiResponse<KycResponse>> submitKyc(
            @AuthenticationPrincipal AuthPrincipal principal, @Valid @RequestBody KycRequest body) {
        return ResponseEntity.ok(ApiResponse.ok(onboardingService.submitBrandKyc(principal, body)));
    }
}
