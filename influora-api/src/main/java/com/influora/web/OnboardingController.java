package com.influora.web;

import com.influora.common.ApiResponse;
import com.influora.security.AuthPrincipal;
import com.influora.service.OnboardingService;
import com.influora.web.dto.onboarding.OnboardingDtos.BrandCompanyRequest;
import com.influora.web.dto.onboarding.OnboardingDtos.KycPromptDismissedResponse;
import com.influora.web.dto.onboarding.OnboardingDtos.KycRequest;
import com.influora.web.dto.onboarding.OnboardingDtos.KycResponse;
import com.influora.web.dto.onboarding.OnboardingDtos.OkResponse;
import com.influora.web.dto.onboarding.OnboardingDtos.OnboardingStatusResponse;
import com.influora.web.dto.onboarding.OnboardingDtos.WorkspaceIdResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
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

    /**
     * OB-2 (BrandF.md §102) — server-side onboarding status read, fresh per request. The existing
     * client-side guard (`App.tsx` `ProtectedRoute`) only checks token presence; this endpoint is
     * the previously-missing piece that lets a caller ask the server, not a cached token/
     * localStorage flag, whether onboarding is actually complete.
     */
    @GetMapping("/status")
    public ResponseEntity<ApiResponse<OnboardingStatusResponse>> status(
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(onboardingService.getBrandOnboardingStatus(principal)));
    }

    /**
     * OB-1 (BrandF.md §105/§91) — server-side persistence for "brand skipped the KYC prompt",
     * so the dismissal survives across devices instead of living only in the client's
     * localStorage flag (`brand-kyc-prompt.tsx`'s `DISMISS_KEY`). No request body — the
     * principal alone identifies who dismissed it. Idempotent; safe to call more than once.
     */
    @PostMapping("/kyc-prompt-dismissed")
    public ResponseEntity<ApiResponse<KycPromptDismissedResponse>> dismissKycPrompt(
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(onboardingService.dismissBrandKycPrompt(principal)));
    }
}
