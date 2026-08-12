package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.CreatorBankAccount;
import com.influora.domain.entity.CreatorProfile;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.UserRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.payout.CreatorBankAccountService;
import com.influora.web.dto.onboarding.OnboardingDtos.CreatorKycRequest;
import com.influora.web.dto.onboarding.OnboardingDtos.CreatorPayoutRequest;
import com.influora.web.dto.onboarding.OnboardingDtos.CreatorPayoutResponse;
import com.influora.web.dto.onboarding.OnboardingDtos.CreatorProfileRequest;
import com.influora.web.dto.onboarding.OnboardingDtos.CreatorSocialRequest;
import com.influora.web.dto.onboarding.OnboardingDtos.KycResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * N1 (Wave 6) — covers the parts of CreatorOnboardingService with actual branching logic: the
 * rateMin/rateMax guard, the KYC persistence (reusing the D14 `pan` column + new identity-KYC
 * columns), and the UPI/bank discriminated-union payout routing through the SAME encrypted
 * CreatorBankAccountService N3 wires into WalletController.
 */
@ExtendWith(MockitoExtension.class)
class CreatorOnboardingServiceTest {

    private static final String CREATOR_USER_ID = "01HCREATORUSER1234AB";

    @Mock private CreatorContextService creatorContext;
    @Mock private CreatorProfileRepository creatorProfileRepository;
    @Mock private UserRepository userRepository;
    @Mock private CreatorBankAccountService creatorBankAccountService;
    @Mock private AuthPrincipal principal;

    private CreatorOnboardingService service;
    private CreatorProfile profile;

    @BeforeEach
    void setUp() {
        service =
                new CreatorOnboardingService(
                        creatorContext,
                        creatorProfileRepository,
                        userRepository,
                        creatorBankAccountService);
        profile = CreatorProfile.newForUser("prof_1", CREATOR_USER_ID, "Priya Creates");
        // lenient: the payout-method tests below don't touch creatorContext at all (savePayout
        // goes straight to CreatorBankAccountService, which does its own principal resolution).
        org.mockito.Mockito.lenient().when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
    }

    @Test
    @DisplayName(
            "connectSocial (CR-108) refuses every platform with a typed NOT_IMPLEMENTED instead of"
                    + " fabricating a followers(0)/verified(false) row")
    void testConnectSocialRefusesFabricatedConnectionForUnimplementedPlatform() {
        CreatorSocialRequest req = new CreatorSocialRequest("YOUTUBE", "mock_oauth_code");

        ApiException ex = assertThrows(ApiException.class, () -> service.connectSocial(principal, req));

        assertEquals("SOCIAL_OAUTH_NOT_IMPLEMENTED", ex.getCode());
        assertEquals(HttpStatus.NOT_IMPLEMENTED, ex.getStatus());
        verify(creatorContext).requireCreatorProfile(principal);
    }

    @Test
    @DisplayName("connectSocial (CR-108) refuses INSTAGRAM here too — that platform's real OAuth is"
            + " a separate dedicated endpoint (MetaOAuthController), not this one")
    void testConnectSocialRefusesInstagramToo() {
        CreatorSocialRequest req = new CreatorSocialRequest("INSTAGRAM", "mock_oauth_code");

        ApiException ex = assertThrows(ApiException.class, () -> service.connectSocial(principal, req));

        assertEquals("SOCIAL_OAUTH_NOT_IMPLEMENTED", ex.getCode());
    }

    @Test
    @DisplayName("saveProfile rejects rateMin > rateMax before touching the repository")
    void testSaveProfileRejectsInvertedRateRange() {
        CreatorProfileRequest req =
                new CreatorProfileRequest(
                        "Priya", null, List.of(), List.of(), null, new BigDecimal("20000"), new BigDecimal("5000"));

        ApiException ex = assertThrows(ApiException.class, () -> service.saveProfile(principal, req));
        assertEquals("INVALID_RATE_RANGE", ex.getCode());
        verify(creatorProfileRepository, never()).save(any());
    }

    @Test
    @DisplayName("submitKyc reuses the D14 pan column and moves identityKycStatus to PENDING")
    void testSubmitKycPersistsAndReturnsPending() {
        CreatorKycRequest req = new CreatorKycRequest("abcde1234f", "1234", "creators/x/selfie/1");

        KycResponse response = service.submitKyc(principal, req);

        assertEquals("PENDING", response.kycStatus());
        assertEquals("ABCDE1234F", profile.getPan());
        assertEquals("1234", profile.getAadhaarLast4());
        verify(creatorProfileRepository).save(profile);
    }

    @Test
    @DisplayName("savePayout(method=upi) routes through CreatorBankAccountService as a UPI instrument")
    void testSavePayoutUpi() {
        CreatorPayoutRequest req = new CreatorPayoutRequest("upi", "creator@upi", null, null, null);
        CreatorBankAccount saved =
                CreatorBankAccount.createEncrypted(
                        "pm_1", CREATOR_USER_ID, "c", null, "UPI", "****upi", true, Instant.now(), Instant.now());
        when(creatorBankAccountService.addInstrument(principal, "UPI", "creator@upi", null, null))
                .thenReturn(saved);

        CreatorPayoutResponse response = service.savePayout(principal, req);

        assertEquals("pm_1", response.payoutId());
        verify(creatorBankAccountService).addInstrument(principal, "UPI", "creator@upi", null, null);
    }

    @Test
    @DisplayName("savePayout(method=bank) routes through CreatorBankAccountService as a BANK instrument")
    void testSavePayoutBank() {
        CreatorPayoutRequest req =
                new CreatorPayoutRequest("bank", null, "1234567890", "HDFC0001234", "Priya Sharma");
        CreatorBankAccount saved =
                CreatorBankAccount.createEncrypted(
                        "pm_2",
                        CREATOR_USER_ID,
                        "c",
                        "ifsc-c",
                        "BANK",
                        "****7890",
                        true,
                        Instant.now(),
                        Instant.now());
        when(creatorBankAccountService.addInstrument(
                        principal, "BANK", "1234567890", "HDFC0001234", null))
                .thenReturn(saved);

        CreatorPayoutResponse response = service.savePayout(principal, req);

        assertEquals("pm_2", response.payoutId());
        verify(creatorBankAccountService)
                .addInstrument(principal, "BANK", "1234567890", "HDFC0001234", null);
    }

    @Test
    @DisplayName("savePayout rejects an unknown method without calling the bank-account service")
    void testSavePayoutRejectsUnknownMethod() {
        CreatorPayoutRequest req = new CreatorPayoutRequest("crypto", null, null, null, null);

        ApiException ex = assertThrows(ApiException.class, () -> service.savePayout(principal, req));
        assertEquals("INVALID_PAYOUT_METHOD", ex.getCode());
        verify(creatorBankAccountService, never()).addInstrument(any(), any(), any(), any(), any());
    }
}
