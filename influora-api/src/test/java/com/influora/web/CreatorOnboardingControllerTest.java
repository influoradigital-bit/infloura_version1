package com.influora.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * N1 (Wave 6) — creator-onboarding.tsx was already calling api.onboarding.connectCreatorSocial /
 * saveCreatorProfile / completeCreator / submitCreatorKyc / saveCreatorPayout with no backend
 * route to hit (OnboardingController only ever mapped /onboarding/brand). These tests lock in
 * CreatorOnboardingController's delegation to CreatorOnboardingService for every one of those
 * paths.
 */
@ExtendWith(MockitoExtension.class)
class CreatorOnboardingControllerTest {

    @Mock private CreatorOnboardingService service;
    @Mock private AuthPrincipal principal;

    private CreatorOnboardingController controller;

    @BeforeEach
    void setUp() {
        controller = new CreatorOnboardingController(service);
    }

    @Test
    @DisplayName("POST /onboarding/creator/socials delegates to service")
    void testConnectSocial() {
        CreatorSocialRequest body = new CreatorSocialRequest("INSTAGRAM", "mock_oauth_code");
        when(service.connectSocial(principal, body))
                .thenReturn(new CreatorSocialResponse("INSTAGRAM", "", 0));

        ResponseEntity<ApiResponse<CreatorSocialResponse>> response =
                controller.connectSocial(principal, body);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("INSTAGRAM", response.getBody().data().platform());
        verify(service).connectSocial(principal, body);
    }

    @Test
    @DisplayName("POST /onboarding/creator/profile delegates to service")
    void testSaveProfile() {
        CreatorProfileRequest body =
                new CreatorProfileRequest(
                        "Priya Creates",
                        "bio",
                        List.of("Fashion & Lifestyle"),
                        List.of("Hindi"),
                        "Mumbai",
                        new BigDecimal("5000"),
                        new BigDecimal("15000"));
        when(service.saveProfile(principal, body)).thenReturn(new CreatorIdResponse("cr_1"));

        ResponseEntity<ApiResponse<CreatorIdResponse>> response = controller.saveProfile(principal, body);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("cr_1", response.getBody().data().creatorId());
        verify(service).saveProfile(principal, body);
    }

    @Test
    @DisplayName("POST /onboarding/creator/complete delegates to service")
    void testComplete() {
        when(service.complete(principal)).thenReturn(new OkResponse(true));

        ResponseEntity<ApiResponse<OkResponse>> response = controller.complete(principal);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(true, response.getBody().data().ok());
        verify(service).complete(principal);
    }

    @Test
    @DisplayName("POST /onboarding/creator/kyc delegates to service")
    void testSubmitKyc() {
        CreatorKycRequest body = new CreatorKycRequest("ABCDE1234F", "1234", "creators/x/selfie/1");
        when(service.submitKyc(principal, body)).thenReturn(new KycResponse("PENDING"));

        ResponseEntity<ApiResponse<KycResponse>> response = controller.submitKyc(principal, body);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("PENDING", response.getBody().data().kycStatus());
        verify(service).submitKyc(principal, body);
    }

    @Test
    @DisplayName("POST /onboarding/creator/payout delegates to service")
    void testSavePayout() {
        CreatorPayoutRequest body = new CreatorPayoutRequest("upi", "creator@upi", null, null, null);
        when(service.savePayout(principal, body)).thenReturn(new CreatorPayoutResponse("pm_1"));

        ResponseEntity<ApiResponse<CreatorPayoutResponse>> response = controller.savePayout(principal, body);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("pm_1", response.getBody().data().payoutId());
        verify(service).savePayout(principal, body);
    }
}
