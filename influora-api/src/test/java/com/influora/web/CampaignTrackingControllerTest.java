package com.influora.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.UserType;
import com.influora.security.AuthPrincipal;
import com.influora.service.BrandContextService;
import com.influora.service.tracking.CampaignTrackingService;
import com.influora.web.dto.tracking.TrackingDtos.CreateTrackingLinkRequest;
import com.influora.web.dto.tracking.TrackingDtos.TrackingLinkResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * Regression coverage for B18 (feature-audit-brand-creator-2026-07-23.md) -- {@code POST
 * /campaigns/{campaignId}/tracking-links} returned a bare {@code 500 INTERNAL_ERROR} instead of a
 * clean {@code 400} when the request body was missing a required field (most notably {@code
 * baseUrl}, which reached {@code CampaignLinkService#buildTrackingUrl} unchecked and threw an
 * unhandled {@code NullPointerException} on {@code baseUrl.contains("?")}).
 *
 * <p>No MockMvc harness exists in this codebase yet ({@code AuthControllerTest} javadoc) -- two
 * things are proven separately, matching that convention: (1) {@link
 * CreateTrackingLinkRequest}'s {@code @NotBlank} constraints actually fire for missing/blank
 * fields, using a real {@link Validator} (the same one Spring's {@code @Valid} delegates to), and
 * (2) the controller method itself, called directly, returns {@code 201 Created} with the
 * persisted link on a valid request.
 */
@ExtendWith(MockitoExtension.class)
class CampaignTrackingControllerTest {

    private static final String USER_ID = "user-1";
    private static final String WORKSPACE_ID = "01HWORKSPACE123456789A";
    private static final String CAMPAIGN_ID = "01HCAMPAIGN123456789A";
    private static final String COLLAB_ID = "01HCOLLAB1234567890AB";
    private static final String CREATOR_PROFILE_ID = "01HCREATORPROFILE1234";

    private static final AuthPrincipal BRAND_PRINCIPAL =
            new AuthPrincipal(USER_ID, "brand@example.com", UserType.BRAND, WORKSPACE_ID);

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @Mock private CampaignTrackingService campaignTrackingService;
    @Mock private BrandContextService brandContextService;

    private CampaignTrackingController controller;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void tearDownValidator() {
        validatorFactory.close();
    }

    @BeforeEach
    void setUp() {
        controller = new CampaignTrackingController(campaignTrackingService, brandContextService);
    }

    // ------------------------------------------------------------------
    // CreateTrackingLinkRequest: @NotBlank fires for missing/blank fields (the B18 root cause)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("CreateTrackingLinkRequest: a missing baseUrl fails validation (was an unhandled NPE downstream)")
    void missingBaseUrl_failsValidation() {
        CreateTrackingLinkRequest request =
                new CreateTrackingLinkRequest(COLLAB_ID, CREATOR_PROFILE_ID, null, "INSTAGRAM");

        Set<ConstraintViolation<CreateTrackingLinkRequest>> violations = validator.validate(request);

        assertFalse(violations.isEmpty());
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("baseUrl")));
    }

    @Test
    @DisplayName("CreateTrackingLinkRequest: a blank (whitespace-only) baseUrl also fails validation")
    void blankBaseUrl_failsValidation() {
        CreateTrackingLinkRequest request =
                new CreateTrackingLinkRequest(COLLAB_ID, CREATOR_PROFILE_ID, "   ", "INSTAGRAM");

        Set<ConstraintViolation<CreateTrackingLinkRequest>> violations = validator.validate(request);

        assertFalse(violations.isEmpty());
    }

    @Test
    @DisplayName("CreateTrackingLinkRequest: missing collaborationId/creatorProfileId/platform each fail validation")
    void missingOtherRequiredFields_failValidation() {
        assertFalse(
                validator
                        .validate(
                                new CreateTrackingLinkRequest(
                                        null, CREATOR_PROFILE_ID, "https://brand.example.com", "INSTAGRAM"))
                        .isEmpty());
        assertFalse(
                validator
                        .validate(
                                new CreateTrackingLinkRequest(
                                        COLLAB_ID, null, "https://brand.example.com", "INSTAGRAM"))
                        .isEmpty());
        assertFalse(
                validator
                        .validate(
                                new CreateTrackingLinkRequest(
                                        COLLAB_ID, CREATOR_PROFILE_ID, "https://brand.example.com", null))
                        .isEmpty());
    }

    @Test
    @DisplayName("CreateTrackingLinkRequest: a fully-populated request passes validation")
    void fullyPopulatedRequest_passesValidation() {
        CreateTrackingLinkRequest request =
                new CreateTrackingLinkRequest(
                        COLLAB_ID, CREATOR_PROFILE_ID, "https://brand.example.com/landing", "INSTAGRAM");

        assertTrue(validator.validate(request).isEmpty());
    }

    // ------------------------------------------------------------------
    // Controller: valid create returns 201 with the persisted link
    // ------------------------------------------------------------------

    @Test
    @DisplayName("createTrackingLink: a valid request returns 201 Created with the persisted link")
    void validRequest_returns201WithPersistedLink() {
        Workspace workspace = org.mockito.Mockito.mock(Workspace.class);
        when(workspace.getId()).thenReturn(WORKSPACE_ID);
        when(brandContextService.requireBrandWorkspace(BRAND_PRINCIPAL)).thenReturn(workspace);

        TrackingLinkResponse persisted =
                new TrackingLinkResponse(
                        "01HUTM1234567890ABCDE",
                        CAMPAIGN_ID,
                        COLLAB_ID,
                        CREATOR_PROFILE_ID,
                        "https://brand.example.com/landing",
                        "instagram",
                        "influencer",
                        "summer-sale-2026",
                        "priya-sharma",
                        "https://brand.example.com/landing?utm_source=instagram",
                        null,
                        0L,
                        0L,
                        0L,
                        BigDecimal.ZERO,
                        Instant.now(),
                        Instant.now(),
                        null);
        when(campaignTrackingService.createTrackingLink(
                        WORKSPACE_ID,
                        CAMPAIGN_ID,
                        COLLAB_ID,
                        CREATOR_PROFILE_ID,
                        "https://brand.example.com/landing",
                        "INSTAGRAM"))
                .thenReturn(persisted);

        CreateTrackingLinkRequest request =
                new CreateTrackingLinkRequest(
                        COLLAB_ID, CREATOR_PROFILE_ID, "https://brand.example.com/landing", "INSTAGRAM");

        var response = controller.createTrackingLink(BRAND_PRINCIPAL, CAMPAIGN_ID, request);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals(persisted, response.getBody().data());
    }
}
