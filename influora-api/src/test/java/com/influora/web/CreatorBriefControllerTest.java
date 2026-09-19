package com.influora.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.common.ApiResponse;
import com.influora.config.MeeraCreatorFeatureProperties;
import com.influora.domain.entity.CreatorProfile;
import com.influora.security.AuthPrincipal;
import com.influora.service.CreatorAgentPreferencesService;
import com.influora.service.CreatorBriefService;
import com.influora.service.CreatorContextService;
import com.influora.web.dto.brief.BriefDtos.BriefAnalysisResponse;
import com.influora.web.dto.brief.BriefDtos.BriefListItem;
import com.influora.web.dto.brief.BriefDtos.PasteBriefRequest;
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
 * T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;3.8), B0-42.
 *
 * <p>The gates are the subject here, in their fixed order, on every route. The flag has to run BEFORE
 * identity so a disabled feature 404s uniformly — a 403 would tell an unauthenticated caller the
 * feature exists — and consent has to run BEFORE the paste is persisted, because a brief is personal
 * data a brand sent about a deal and storing it for an unconsented creator is the exact thing the DPDP
 * notice gates.
 *
 * <p>Also pins what this controller does NOT expose: the three secure-link routes are Phase B1, and a
 * route that produced a brand-visible package payload before the floor strip exists would ship the
 * leak and the fix in opposite phases.
 */
@ExtendWith(MockitoExtension.class)
class CreatorBriefControllerTest {

    private static final String CREATOR_USER_ID = "01HCREATORUSER1234567A";
    private static final String BRIEF_ID = "01HBRIEF12345678901234";

    @Mock private CreatorBriefService briefService;
    @Mock private CreatorContextService creatorContext;
    @Mock private CreatorAgentPreferencesService preferencesService;
    @Mock private MeeraCreatorFeatureProperties featureProperties;
    @Mock private AuthPrincipal principal;
    @Mock private CreatorProfile profile;

    private CreatorBriefController controller;

    @BeforeEach
    void setUp() {
        controller =
                new CreatorBriefController(
                        briefService, creatorContext, preferencesService, featureProperties);
        lenient().when(featureProperties.isCreatorEnabled()).thenReturn(true);
        lenient().when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
        lenient().when(profile.getUserId()).thenReturn(CREATOR_USER_ID);
        lenient().when(preferencesService.isConsentAccepted(CREATOR_USER_ID)).thenReturn(true);
    }

    private static BriefAnalysisResponse analysis() {
        return new BriefAnalysisResponse(
                BRIEF_ID, "PASTED", "ANALYZED", null, null, List.of(), null, "AI", null, List.of(), null);
    }

    @Test
    @DisplayName("paste returns 201 and delegates with the creator's USER id, never a body id")
    void paste_created() {
        when(briefService.paste(CREATOR_USER_ID, "a brief")).thenReturn(analysis());

        ResponseEntity<ApiResponse<BriefAnalysisResponse>> response =
                controller.paste(principal, new PasteBriefRequest("a brief"));

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals(BRIEF_ID, response.getBody().data().briefId());
        verify(briefService).paste(CREATOR_USER_ID, "a brief");
    }

    @Test
    @DisplayName("the flag is checked FIRST — a disabled feature 404s without resolving the creator")
    void flagOff_404sBeforeIdentity() {
        when(featureProperties.isCreatorEnabled()).thenReturn(false);

        ApiException e =
                assertThrows(
                        ApiException.class,
                        () -> controller.paste(principal, new PasteBriefRequest("a brief")));

        assertEquals("FEATURE_DISABLED", e.getCode());
        assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
        verifyNoInteractions(creatorContext);
        verifyNoInteractions(briefService);
    }

    @Test
    @DisplayName("consent is required BEFORE the paste is persisted, not after")
    void consentMissing_403sBeforeAnythingIsStored() {
        when(preferencesService.isConsentAccepted(CREATOR_USER_ID)).thenReturn(false);

        ApiException e =
                assertThrows(
                        ApiException.class,
                        () -> controller.paste(principal, new PasteBriefRequest("a brief")));

        assertEquals("CONSENT_REQUIRED", e.getCode());
        assertEquals(HttpStatus.FORBIDDEN, e.getStatus());
        verifyNoInteractions(briefService);
    }

    @Test
    @DisplayName("every route runs the flag and the consent gate, not just the paste")
    void allRoutesAreGated() {
        when(featureProperties.isCreatorEnabled()).thenReturn(false);

        assertEquals(
                "FEATURE_DISABLED",
                assertThrows(ApiException.class, () -> controller.list(principal, 20)).getCode());
        assertEquals(
                "FEATURE_DISABLED",
                assertThrows(ApiException.class, () -> controller.get(principal, BRIEF_ID)).getCode());
        assertEquals(
                "FEATURE_DISABLED",
                assertThrows(ApiException.class, () -> controller.dismiss(principal, BRIEF_ID)).getCode());
        verifyNoInteractions(briefService);
    }

    @Test
    @DisplayName("list passes the limit straight through — the cap lives in the service, not the edge")
    void list_passesLimit() {
        when(briefService.list(eq(CREATOR_USER_ID), anyInt()))
                .thenReturn(
                        List.of(new BriefListItem(BRIEF_ID, "PASTED", "NEW", null, null, null, null)));

        ResponseEntity<ApiResponse<List<BriefListItem>>> response = controller.list(principal, 5);

        assertEquals(1, response.getBody().data().size());
        verify(briefService).list(CREATOR_USER_ID, 5);
    }

    @Test
    @DisplayName("dismiss is 204 with no body")
    void dismiss_noContent() {
        ResponseEntity<Void> response = controller.dismiss(principal, BRIEF_ID);
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(briefService).dismiss(CREATOR_USER_ID, BRIEF_ID);
    }

    @Test
    @DisplayName(
            "a BRAND principal is refused on EVERY route — this is what earns this controller its place"
                    + " in FloorBarrierTest.FLOOR_PERMITTED_CONTROLLERS")
    void brandPrincipalIsRefusedOnEveryRoute() {
        // BriefAnalysisResponse carries the whole PackageQuote, floor and anchor included, because a
        // creator reopening her brief must see the numbers she was actually shown. That is only safe
        // while no brand can reach this controller, and FloorBarrierTest's allow-list takes that on
        // trust — so it is proven here instead.
        when(creatorContext.requireCreatorProfile(principal))
                .thenThrow(
                        new ApiException(
                                "WRONG_USER_TYPE",
                                "This endpoint is for creator accounts only",
                                HttpStatus.FORBIDDEN));

        for (Runnable call :
                List.<Runnable>of(
                        () -> controller.paste(principal, new PasteBriefRequest("a brief")),
                        () -> controller.list(principal, 20),
                        () -> controller.get(principal, BRIEF_ID),
                        () -> controller.dismiss(principal, BRIEF_ID))) {
            ApiException e = assertThrows(ApiException.class, call::run);
            assertEquals("WRONG_USER_TYPE", e.getCode());
            assertEquals(HttpStatus.FORBIDDEN, e.getStatus());
        }
        verifyNoInteractions(briefService);
    }

    @Test
    @DisplayName("NO secure-link routes exist on this controller — those are Phase B1")
    void noSecureLinkRoutesInB0() {
        boolean anySecureLink =
                java.util.Arrays.stream(CreatorBriefController.class.getDeclaredMethods())
                        .flatMap(m -> java.util.Arrays.stream(m.getAnnotations()))
                        .map(Object::toString)
                        .anyMatch(a -> a.toLowerCase().contains("secure"));
        org.junit.jupiter.api.Assertions.assertFalse(
                anySecureLink,
                "a secure-link route would create a brand-visible package payload before the floor"
                        + " strip that has to guard it exists");
        org.junit.jupiter.api.Assertions.assertFalse(
                java.util.Arrays.stream(CreatorBriefService.class.getDeclaredMethods())
                        .anyMatch(m -> m.getName().toLowerCase().contains("securelink")),
                "createSecureLink is Phase B1 and must not be reachable from B0");
    }
}
