package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Workspace;
import com.influora.domain.entity.WorkspaceMember;
import com.influora.domain.enums.CampaignIntentType;
import com.influora.domain.enums.CampaignStatus;
import com.influora.domain.enums.VerificationStatus;
import com.influora.repository.CampaignRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.campaign.CampaignDtos.BudgetDto;
import com.influora.web.dto.campaign.CampaignDtos.CampaignPatchRequest;
import com.influora.web.dto.campaign.CampaignDtos.CampaignResponse;
import com.influora.web.dto.campaign.CampaignDtos.CampaignWriteRequest;
import com.influora.web.dto.campaign.CampaignDtos.HypeConfigDto;
import com.influora.web.dto.campaign.CampaignDtos.TimelineDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * Wave D task D3 follow-up (wiki/decisions/2026-07-07-d3-campaign-gating-scope.md): {@code
 * CampaignService.create} — the human REST campaign-creation path — must reject a {@code DIRECT}
 * ("sale"/conversion-shaped) campaign when the workspace has no active store integration, mirroring
 * {@code CreateCampaignExecutorTest}'s coverage of the AI-drafted path. Both paths share the same
 * {@link IntegrationHealthService#requiresStoreIntegration} predicate and the identical typed
 * {@code 409 NO_STORE_INTEGRATION} error.
 */
@ExtendWith(MockitoExtension.class)
class CampaignServiceTest {

    private static final String WORKSPACE_ID = "01HWORKSPACE12345678A";
    private static final String USER_ID = "01HUSER123456789012AB";
    private static final String CAMPAIGN_ID = "01HCAMPAIGN123456789A";

    @Mock private CampaignRepository campaignRepository;
    @Mock private BrandContextService brandContext;
    @Mock private IntegrationHealthService integrationHealthService;
    @Mock private BrandCampaignFeeService brandCampaignFeeService;
    @Mock private AuthPrincipal principal;
    @Mock private Workspace workspace;
    @Mock private WorkspaceMember member;

    private CampaignService service;

    @BeforeEach
    void setUp() {
        // Real CampaignValidator: no dependencies of its own, and default DRAFT status never
        // trips its ACTIVE-only verification check, so a mock would add nothing but noise.
        service =
                new CampaignService(
                        campaignRepository,
                        brandContext,
                        new CampaignValidator(),
                        integrationHealthService,
                        brandCampaignFeeService);
    }

    @Test
    @DisplayName("DIRECT (sale) campaign with no active store integration -> 409 NO_STORE_INTEGRATION, nothing persisted")
    void testDirectCampaignRejectedWithoutStoreIntegration() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        when(workspace.getId()).thenReturn(WORKSPACE_ID);
        when(integrationHealthService.hasActiveStoreIntegration(WORKSPACE_ID)).thenReturn(false);

        CampaignWriteRequest req = writeRequest(CampaignIntentType.DIRECT);

        ApiException ex =
                assertThrows(ApiException.class, () -> service.create(principal, req));

        assertEquals("NO_STORE_INTEGRATION", ex.getCode());
        assertEquals(409, ex.getStatus().value());
        verify(campaignRepository, never()).save(any(Campaign.class));
    }

    @Test
    @DisplayName("DIRECT (sale) campaign with an active store integration -> succeeds, campaign created")
    void testDirectCampaignSucceedsWithStoreIntegration() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        when(workspace.getId()).thenReturn(WORKSPACE_ID);
        when(principal.getUserId()).thenReturn(USER_ID);
        when(integrationHealthService.hasActiveStoreIntegration(WORKSPACE_ID)).thenReturn(true);
        when(campaignRepository.save(any(Campaign.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CampaignWriteRequest req = writeRequest(CampaignIntentType.DIRECT);

        var response = service.create(principal, req);

        assertNotNull(response);
        verify(campaignRepository).save(any(Campaign.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"HYPE", "REVIEW", "STANDARD"})
    @DisplayName("Non-store-dependent campaign types succeed even with zero integrations connected")
    void testNonStoreDependentTypesIgnoreIntegrationStatus(String campaignType) {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        when(workspace.getId()).thenReturn(WORKSPACE_ID);
        when(principal.getUserId()).thenReturn(USER_ID);
        when(campaignRepository.save(any(Campaign.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CampaignIntentType type = CampaignIntentType.valueOf(campaignType);
        // HYPE now requires a well-formed hype config (Priya task); the other two types ignore it.
        CampaignWriteRequest req = writeRequest(type, type == CampaignIntentType.HYPE ? validHype() : null);

        var response = service.create(principal, req);

        assertNotNull(response);
        // The integration health check must never even be consulted for a non-DIRECT type.
        verify(integrationHealthService, never()).hasActiveStoreIntegration(WORKSPACE_ID);
        verify(campaignRepository).save(any(Campaign.class));
    }

    @Test
    @DisplayName("Null/unspecified campaign type (legacy/untyped request) -> not gated, succeeds regardless of integration status")
    void testNullCampaignTypeIsNotGated() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        when(workspace.getId()).thenReturn(WORKSPACE_ID);
        when(principal.getUserId()).thenReturn(USER_ID);
        when(campaignRepository.save(any(Campaign.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CampaignWriteRequest req = writeRequest(null);

        var response = service.create(principal, req);

        assertNotNull(response);
        verify(integrationHealthService, never()).hasActiveStoreIntegration(WORKSPACE_ID);
        verify(campaignRepository).save(any(Campaign.class));
    }

    // --- update() / B1 brand-fee-on-publish gating (Kabir fix 1 + Kavya blocking-test-coverage) ---

    @Test
    @DisplayName("update(): DRAFT -> ACTIVE charges the brand publish fee exactly once")
    void testUpdateDraftToActiveChargesFeeOnce() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        when(workspace.getId()).thenReturn(WORKSPACE_ID);
        when(workspace.getVerificationStatus()).thenReturn(VerificationStatus.VERIFIED);

        Campaign campaign = activatableCampaign(CampaignStatus.DRAFT);
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        when(campaignRepository.save(any(Campaign.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = service.update(principal, CAMPAIGN_ID, activateRequest());

        assertNotNull(response);
        verify(brandCampaignFeeService, times(1)).chargeOnPublish(any(Campaign.class), eq(WORKSPACE_ID));
        verify(campaignRepository).save(any(Campaign.class));
    }

    @Test
    @DisplayName("update(): PAUSED -> ACTIVE (resume) also charges the fee")
    void testUpdatePausedToActiveChargesFee() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        when(workspace.getId()).thenReturn(WORKSPACE_ID);
        when(workspace.getVerificationStatus()).thenReturn(VerificationStatus.VERIFIED);

        Campaign campaign = activatableCampaign(CampaignStatus.PAUSED);
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        when(campaignRepository.save(any(Campaign.class))).thenAnswer(inv -> inv.getArgument(0));

        service.update(principal, CAMPAIGN_ID, activateRequest());

        verify(brandCampaignFeeService, times(1)).chargeOnPublish(any(Campaign.class), eq(WORKSPACE_ID));
    }

    @Test
    @DisplayName("update(): ACTIVE -> ACTIVE (no real transition) is a no-op — fee not re-charged")
    void testUpdateActiveToActiveDoesNotRechargeFee() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        when(workspace.getId()).thenReturn(WORKSPACE_ID);
        when(workspace.getVerificationStatus()).thenReturn(VerificationStatus.VERIFIED);

        Campaign campaign = activatableCampaign(CampaignStatus.ACTIVE);
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        when(campaignRepository.save(any(Campaign.class))).thenAnswer(inv -> inv.getArgument(0));

        service.update(principal, CAMPAIGN_ID, activateRequest());

        verify(brandCampaignFeeService, never()).chargeOnPublish(any(Campaign.class), anyString());
    }

    @Test
    @DisplayName("update(): insufficient wallet balance throws 402 and the status change is never persisted")
    void testUpdateInsufficientBalanceRollsBack() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        when(workspace.getId()).thenReturn(WORKSPACE_ID);
        when(workspace.getVerificationStatus()).thenReturn(VerificationStatus.VERIFIED);

        Campaign campaign = activatableCampaign(CampaignStatus.DRAFT);
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        when(brandCampaignFeeService.chargeOnPublish(any(Campaign.class), eq(WORKSPACE_ID)))
                .thenThrow(
                        new ApiException(
                                "INSUFFICIENT_WALLET_BALANCE_FOR_PUBLISH",
                                "Insufficient wallet balance",
                                HttpStatus.PAYMENT_REQUIRED));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.update(principal, CAMPAIGN_ID, activateRequest()));

        assertEquals("INSUFFICIENT_WALLET_BALANCE_FOR_PUBLISH", ex.getCode());
        assertEquals(402, ex.getStatus().value());
        verify(campaignRepository, never()).save(any(Campaign.class));
    }

    @Test
    @DisplayName("update(): any exception from the fee charge rolls back the whole PATCH")
    void testUpdateGenericFeeChargeExceptionRollsBack() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        when(workspace.getId()).thenReturn(WORKSPACE_ID);
        when(workspace.getVerificationStatus()).thenReturn(VerificationStatus.VERIFIED);

        Campaign campaign = activatableCampaign(CampaignStatus.DRAFT);
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        when(brandCampaignFeeService.chargeOnPublish(any(Campaign.class), eq(WORKSPACE_ID)))
                .thenThrow(new RuntimeException("ledger unavailable"));

        assertThrows(
                RuntimeException.class, () -> service.update(principal, CAMPAIGN_ID, activateRequest()));
        verify(campaignRepository, never()).save(any(Campaign.class));
    }

    private static Campaign activatableCampaign(CampaignStatus status) {
        return Campaign.builder()
                .id(CAMPAIGN_ID)
                .workspaceId(WORKSPACE_ID)
                .title("Test Campaign")
                .status(status)
                .build();
    }

    private static CampaignPatchRequest activateRequest() {
        return new CampaignPatchRequest(
                null, null, null, CampaignStatus.ACTIVE, null, null, null, null, null, null, null, null,
                null, null, null, null);
    }

    private static CampaignWriteRequest writeRequest(CampaignIntentType campaignType) {
        return writeRequest(campaignType, null);
    }

    private static CampaignWriteRequest writeRequest(CampaignIntentType campaignType, HypeConfigDto hype) {
        return new CampaignWriteRequest(
                "Test Campaign Title",
                "description",
                null,
                null,
                campaignType,
                hype,
                new BudgetDto(BigDecimal.TEN, BigDecimal.valueOf(100), "INR"),
                new TimelineDto(LocalDate.now().plusDays(1), LocalDate.now().plusDays(30)),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private static HypeConfigDto validHype() {
        return new HypeConfigDto(
                "https://instagram.com/reel/abc123",
                "Original Sound - Artist",
                "#GlowDropChallenge",
                java.util.List.of("Remix the hook", "Duet reaction"),
                BigDecimal.valueOf(500),
                "INR",
                100,
                0,
                "2026-07-21T12:00:00Z");
    }

    // --- Hype campaign config persistence (Priya task: persist the `hype` block instead of
    // silently dropping it) ---

    @Test
    @DisplayName("HYPE campaign round-trips: create persists campaignType + hype config, returned on GET-equivalent response")
    void testHypeCampaignRoundTripsConfig() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        when(workspace.getId()).thenReturn(WORKSPACE_ID);
        when(principal.getUserId()).thenReturn(USER_ID);
        when(campaignRepository.save(any(Campaign.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        HypeConfigDto hype = validHype();
        CampaignWriteRequest req = writeRequest(CampaignIntentType.HYPE, hype);

        CampaignResponse response = service.create(principal, req);

        assertNotNull(response);
        assertEquals(CampaignIntentType.HYPE, response.campaignType());
        assertNotNull(response.hype());
        assertEquals(hype.sourceReelUrl(), response.hype().sourceReelUrl());
        assertEquals(hype.hashtag(), response.hype().hashtag());
        assertEquals(hype.formatLanes(), response.hype().formatLanes());
        assertEquals(0, hype.perReelRate().compareTo(response.hype().perReelRate()));
        assertEquals(hype.slotCap(), response.hype().slotCap());
        assertEquals(0, response.hype().slotsFilled());
        assertEquals("INR", response.hype().currency());
    }

    @Test
    @DisplayName("STANDARD campaign is unchanged: no hype config persisted even if omitted, campaignType defaults to STANDARD")
    void testStandardCampaignUnaffectedByHypeStorage() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        when(workspace.getId()).thenReturn(WORKSPACE_ID);
        when(principal.getUserId()).thenReturn(USER_ID);
        when(campaignRepository.save(any(Campaign.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CampaignWriteRequest req = writeRequest(null, null);

        CampaignResponse response = service.create(principal, req);

        assertNotNull(response);
        assertEquals(CampaignIntentType.STANDARD, response.campaignType());
        assertEquals(null, response.hype());
    }

    @Test
    @DisplayName("Malformed HYPE block (missing sourceReelUrl) -> 400 VALIDATION_ERROR, nothing persisted")
    void testMalformedHypeConfigRejected() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        when(workspace.getId()).thenReturn(WORKSPACE_ID);

        HypeConfigDto malformed =
                new HypeConfigDto(
                        "", "Original Sound", "#Tag", java.util.List.of("Remix the hook"),
                        BigDecimal.valueOf(500), "INR", 100, 0, null);
        CampaignWriteRequest req = writeRequest(CampaignIntentType.HYPE, malformed);

        ApiException ex = assertThrows(ApiException.class, () -> service.create(principal, req));

        assertEquals("VALIDATION_ERROR", ex.getCode());
        assertEquals(400, ex.getStatus().value());
        verify(campaignRepository, never()).save(any(Campaign.class));
    }

    @Test
    @DisplayName("HYPE campaign with no hype block at all -> 400 VALIDATION_ERROR, nothing persisted")
    void testHypeCampaignMissingConfigRejected() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        when(workspace.getId()).thenReturn(WORKSPACE_ID);

        CampaignWriteRequest req = writeRequest(CampaignIntentType.HYPE, null);

        ApiException ex = assertThrows(ApiException.class, () -> service.create(principal, req));

        assertEquals("VALIDATION_ERROR", ex.getCode());
        verify(campaignRepository, never()).save(any(Campaign.class));
    }

    // --- update(): hype-config PATCH (G3 — silent data-loss on partial hype edit) ---

    @Test
    @DisplayName("update(): full hype-config PATCH on an existing HYPE campaign persists all fields")
    void testUpdateFullHypeConfigPatchPersistsAllFields() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        when(workspace.getId()).thenReturn(WORKSPACE_ID);

        Campaign campaign = hypeCampaign(validHype());
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        when(campaignRepository.save(any(Campaign.class))).thenAnswer(inv -> inv.getArgument(0));

        HypeConfigDto replacement =
                new HypeConfigDto(
                        "https://instagram.com/reel/newone",
                        "New Sound - Artist",
                        "#NewChallenge",
                        java.util.List.of("New lane"),
                        BigDecimal.valueOf(750),
                        "USD",
                        200,
                        5,
                        "2026-08-01T00:00:00Z");

        CampaignResponse response = service.update(principal, CAMPAIGN_ID, hypePatchRequest(replacement));

        assertNotNull(response.hype());
        assertEquals(replacement.sourceReelUrl(), response.hype().sourceReelUrl());
        assertEquals(replacement.audioTrack(), response.hype().audioTrack());
        assertEquals(replacement.hashtag(), response.hype().hashtag());
        assertEquals(replacement.formatLanes(), response.hype().formatLanes());
        assertEquals(0, replacement.perReelRate().compareTo(response.hype().perReelRate()));
        assertEquals(replacement.currency(), response.hype().currency());
        assertEquals(replacement.slotCap(), response.hype().slotCap());
        assertEquals(replacement.slotsFilled(), response.hype().slotsFilled());
        assertEquals(replacement.liveUntil(), response.hype().liveUntil());
        verify(campaignRepository).save(any(Campaign.class));
    }

    @Test
    @DisplayName(
            "update(): partial hype PATCH (slotCap only) merges onto stored config — untouched"
                    + " sub-fields (sourceReelUrl/audioTrack/hashtag/formatLanes/perReelRate/liveUntil)"
                    + " are NOT wiped [G3 anti-data-loss]")
    void testUpdatePartialHypeConfigPatchMergesPreservingUntouchedFields() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        when(workspace.getId()).thenReturn(WORKSPACE_ID);

        HypeConfigDto original = validHype();
        Campaign campaign = hypeCampaign(original);
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        when(campaignRepository.save(any(Campaign.class))).thenAnswer(inv -> inv.getArgument(0));

        // Only slotCap set — every other sub-field null, exactly what a "bump one field" caller
        // (e.g. a slot-cap increase form) would send.
        HypeConfigDto partial =
                new HypeConfigDto(null, null, null, null, null, null, 250, null, null);

        CampaignResponse response = service.update(principal, CAMPAIGN_ID, hypePatchRequest(partial));

        assertNotNull(response.hype());
        // The one field the caller actually changed:
        assertEquals(250, response.hype().slotCap());
        // Anti-data-loss guarantee: everything else survives the partial PATCH untouched.
        assertEquals(original.sourceReelUrl(), response.hype().sourceReelUrl());
        assertEquals(original.audioTrack(), response.hype().audioTrack());
        assertEquals(original.hashtag(), response.hype().hashtag());
        assertEquals(original.formatLanes(), response.hype().formatLanes());
        assertEquals(0, original.perReelRate().compareTo(response.hype().perReelRate()));
        assertEquals(original.currency(), response.hype().currency());
        assertEquals(original.slotsFilled(), response.hype().slotsFilled());
        assertEquals(original.liveUntil(), response.hype().liveUntil());
    }

    @Test
    @DisplayName(
            "update(): malformed hype block on PATCH to a HYPE campaign (perReelRate <= 0 after"
                    + " merge) -> 400 VALIDATION_ERROR, nothing persisted")
    void testUpdateMalformedHypeConfigPatchRejected() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        when(workspace.getId()).thenReturn(WORKSPACE_ID);

        Campaign campaign = hypeCampaign(validHype());
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        HypeConfigDto malformed =
                new HypeConfigDto(null, null, null, null, BigDecimal.ZERO, null, null, null, null);

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.update(principal, CAMPAIGN_ID, hypePatchRequest(malformed)));

        assertEquals("VALIDATION_ERROR", ex.getCode());
        assertEquals(400, ex.getStatus().value());
        verify(campaignRepository, never()).save(any(Campaign.class));
    }

    @Test
    @DisplayName(
            "update(): hype block sent on PATCH to a non-HYPE (STANDARD) campaign is silently"
                    + " ignored — no error, hypeConfigJson stays null")
    void testUpdateHypeBlockIgnoredForNonHypeCampaign() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        when(workspace.getId()).thenReturn(WORKSPACE_ID);

        Campaign campaign =
                Campaign.builder()
                        .id(CAMPAIGN_ID)
                        .workspaceId(WORKSPACE_ID)
                        .title("Standard Campaign")
                        .status(CampaignStatus.DRAFT)
                        .campaignType(CampaignIntentType.STANDARD)
                        .build();
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        when(campaignRepository.save(any(Campaign.class))).thenAnswer(inv -> inv.getArgument(0));

        CampaignResponse response =
                service.update(principal, CAMPAIGN_ID, hypePatchRequest(validHype()));

        assertNotNull(response);
        assertEquals(null, response.hype());
        assertEquals(null, campaign.getHypeConfigJson());
        verify(campaignRepository).save(any(Campaign.class));
    }

    private static Campaign hypeCampaign(HypeConfigDto hype) {
        return Campaign.builder()
                .id(CAMPAIGN_ID)
                .workspaceId(WORKSPACE_ID)
                .title("Hype Campaign")
                .status(CampaignStatus.DRAFT)
                .campaignType(CampaignIntentType.HYPE)
                .hypeConfigJson(com.influora.common.JsonLists.toJsonObject(hype))
                .build();
    }

    private static CampaignPatchRequest hypePatchRequest(HypeConfigDto hype) {
        return new CampaignPatchRequest(
                null, null, null, null, hype, null, null, null, null, null, null, null, null,
                null, null, null);
    }
}
