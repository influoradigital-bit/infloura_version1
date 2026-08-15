package com.influora.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.influora.domain.entity.User;
import com.influora.domain.enums.UserType;
import com.influora.repository.UserRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.brand.AnalyzeSiteTriggerService;
import com.influora.web.dto.onboarding.OnboardingDtos.KycPromptDismissedResponse;
import com.influora.web.dto.onboarding.OnboardingDtos.OnboardingStatusResponse;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * OB-1 (BrandF.md §105/§91) — GET /onboarding/brand/status now carries {@code
 * kycPromptDismissed}, and POST /onboarding/brand/kyc-prompt-dismissed persists the dismissal
 * server-side (via {@code User.kycPromptDismissed}) so it survives across devices instead of
 * living only in the frontend's localStorage flag.
 */
@ExtendWith(MockitoExtension.class)
class OnboardingServiceKycPromptTest {

    @Mock private UserRepository userRepository;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private BrandContextService brandContext;
    @Mock private WorkspaceSlugService slugService;
    @Mock private AnalyzeSiteTriggerService analyzeSiteTrigger;

    private OnboardingService service;

    @BeforeEach
    void setUp() {
        service =
                new OnboardingService(
                        userRepository, workspaceRepository, brandContext, slugService, analyzeSiteTrigger);
    }

    @Test
    void getBrandOnboardingStatus_reflectsUndismissedPromptByDefault() {
        AuthPrincipal principal = mockPrincipal();
        User user = User.newBrand("user-001", "brand@example.com", "hash", "A", "B", "Acme");
        when(userRepository.findById("user-001")).thenReturn(Optional.of(user));

        OnboardingStatusResponse response = service.getBrandOnboardingStatus(principal);

        assertFalse(response.kycPromptDismissed());
        verify(brandContext, times(1)).requireBrand(principal);
    }

    @Test
    void dismissBrandKycPrompt_persistsDismissalAndIsReflectedByStatus() {
        AuthPrincipal principal = mockPrincipal();
        User user = User.newBrand("user-001", "brand@example.com", "hash", "A", "B", "Acme");
        when(userRepository.findById("user-001")).thenReturn(Optional.of(user));

        KycPromptDismissedResponse dismissResponse = service.dismissBrandKycPrompt(principal);

        assertTrue(dismissResponse.kycPromptDismissed());
        assertTrue(user.isKycPromptDismissed());
        verify(userRepository, times(1)).save(user);

        // A fresh status read for the same user now reports the dismissal.
        OnboardingStatusResponse status = service.getBrandOnboardingStatus(principal);
        assertTrue(status.kycPromptDismissed());
    }

    @Test
    void dismissBrandKycPrompt_isIdempotent_doesNotReSaveWhenAlreadyDismissed() {
        AuthPrincipal principal = mockPrincipal();
        User user = User.newBrand("user-001", "brand@example.com", "hash", "A", "B", "Acme");
        user.dismissKycPrompt();
        when(userRepository.findById("user-001")).thenReturn(Optional.of(user));

        KycPromptDismissedResponse response = service.dismissBrandKycPrompt(principal);

        assertTrue(response.kycPromptDismissed());
        verify(userRepository, never()).save(any(User.class));
    }

    private AuthPrincipal mockPrincipal() {
        return new AuthPrincipal("user-001", "brand@example.com", UserType.BRAND, "workspace-001");
    }
}
