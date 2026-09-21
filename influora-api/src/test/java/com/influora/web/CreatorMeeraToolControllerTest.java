package com.influora.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.common.ApiResponse;
import com.influora.config.MeeraCreatorFeatureProperties;
import com.influora.domain.enums.CreatorToolName;
import com.influora.domain.enums.MeeraToolTier;
import com.influora.domain.enums.UserType;
import com.influora.security.OnBehalfAuthResolver;
import com.influora.security.OnBehalfAuthResolver.OnBehalfContext;
import com.influora.service.AuditLogService;
import com.influora.service.CreatorAgentPreferencesService;
import com.influora.service.meera.tool.creator.CheckDealRisksExecutor;
import com.influora.service.meera.tool.creator.CreatorToolCallValidator;
import com.influora.service.meera.tool.ToolCallValidator.ToolCallRejectedException;
import com.influora.service.meera.tool.creator.EstimateMyRateExecutor;
import com.influora.service.meera.tool.creator.GetBriefExecutor;
import com.influora.service.meera.tool.creator.GetMyDealsExecutor;
import com.influora.service.meera.tool.creator.GetMyMetricsExecutor;
import com.influora.web.dto.meera.CreatorToolDtos.CheckDealRisksResult;
import com.influora.web.dto.meera.CreatorToolDtos.EstimateMyRateResult;
import com.influora.web.dto.meera.CreatorToolDtos.GetBriefResult;
import com.influora.web.dto.meera.CreatorToolDtos.GetMyDealsResult;
import com.influora.web.dto.meera.CreatorToolDtos.GetMyMetricsResult;
import com.influora.web.dto.meera.CreatorToolDtos.MetricsResult;
import com.influora.web.dto.meera.CreatorToolDtos.PackageQuote;
import com.influora.web.dto.meera.CreatorToolDtos.RiskFlag;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;3.4) — the gate chain on the creator tool surface, in the
 * order it must run: feature flag, workspace id, scope, principal, consent, validator, executor,
 * audit.
 */
@ExtendWith(MockitoExtension.class)
class CreatorMeeraToolControllerTest {

    private static final String CREATOR_USER_ID = "01HCREATORUSER1234567A";
    private static final String JWT = "on-behalf-jwt";
    private static final Map<String, Object> BODY = Map.of("workspace_id", CREATOR_USER_ID);

    @Mock private OnBehalfAuthResolver onBehalfAuthResolver;
    @Mock private CreatorToolCallValidator creatorToolCallValidator;
    @Mock private CreatorAgentPreferencesService preferencesService;
    @Mock private AuditLogService auditLogService;
    @Mock private MeeraCreatorFeatureProperties featureProperties;
    @Mock private GetMyDealsExecutor getMyDealsExecutor;
    @Mock private GetMyMetricsExecutor getMyMetricsExecutor;
    @Mock private EstimateMyRateExecutor estimateMyRateExecutor;
    @Mock private CheckDealRisksExecutor checkDealRisksExecutor;
    @Mock private GetBriefExecutor getBriefExecutor;

    private CreatorMeeraToolController controller;

    @BeforeEach
    void setUp() {
        controller =
                new CreatorMeeraToolController(
                        onBehalfAuthResolver,
                        creatorToolCallValidator,
                        preferencesService,
                        auditLogService,
                        featureProperties,
                        getMyDealsExecutor,
                        getMyMetricsExecutor,
                        estimateMyRateExecutor,
                        checkDealRisksExecutor,
                        getBriefExecutor);
        lenient().when(featureProperties.isCreatorEnabled()).thenReturn(true);
    }

    private OnBehalfContext creatorContext() {
        return new OnBehalfContext(CREATOR_USER_ID, CREATOR_USER_ID, UserType.CREATOR, "01HCONV1234567890AB");
    }

    private void stubResolverFor(CreatorToolName tool, OnBehalfContext ctx) {
        when(onBehalfAuthResolver.resolveForWorkspaceRequiringScope(JWT, CREATOR_USER_ID, tool.name()))
                .thenReturn(ctx);
    }

    @Test
    @DisplayName(
            "flag off: every route 404s FEATURE_DISABLED before the resolver, the consent check or"
                    + " any executor runs -- a disabled feature must not reveal whether the caller is"
                    + " even a creator")
    void testFeatureFlagOff404sFirst() {
        when(featureProperties.isCreatorEnabled()).thenReturn(false);

        ApiException deals =
                assertThrows(ApiException.class, () -> controller.getMyDeals(JWT, BODY));
        ApiException metrics =
                assertThrows(ApiException.class, () -> controller.getMyMetrics(JWT, BODY));
        ApiException rate =
                assertThrows(ApiException.class, () -> controller.estimateMyRate(JWT, BODY));
        ApiException risks =
                assertThrows(ApiException.class, () -> controller.checkDealRisks(JWT, BODY));
        ApiException brief =
                assertThrows(ApiException.class, () -> controller.getBrief(JWT, BODY));

        assertEquals("FEATURE_DISABLED", deals.getCode());
        assertEquals(HttpStatus.NOT_FOUND, deals.getStatus());
        assertEquals("FEATURE_DISABLED", metrics.getCode());
        assertEquals(HttpStatus.NOT_FOUND, metrics.getStatus());
        assertEquals("FEATURE_DISABLED", rate.getCode());
        assertEquals(HttpStatus.NOT_FOUND, rate.getStatus());
        assertEquals("FEATURE_DISABLED", risks.getCode());
        assertEquals(HttpStatus.NOT_FOUND, risks.getStatus());
        assertEquals("FEATURE_DISABLED", brief.getCode());
        assertEquals(HttpStatus.NOT_FOUND, brief.getStatus());

        verifyNoInteractions(
                onBehalfAuthResolver,
                preferencesService,
                getMyDealsExecutor,
                getMyMetricsExecutor,
                estimateMyRateExecutor,
                checkDealRisksExecutor,
                getBriefExecutor);
        // [SEC: Kabir Wave 2, finding 3] The refusal is audited, with a null tenant key: the flag
        // is checked before anything about the caller is proven, so there is no identity to record.
        assertRejectionRow(null, "get_my_deals", "FEATURE_DISABLED");
        assertRejectionRow(null, "get_my_metrics", "FEATURE_DISABLED");
        assertRejectionRow(null, "estimate_my_rate", "FEATURE_DISABLED");
        assertRejectionRow(null, "check_deal_risks", "FEATURE_DISABLED");
        assertRejectionRow(null, "get_brief", "FEATURE_DISABLED");
    }

    @Test
    @DisplayName("a body without workspace_id is 400 WORKSPACE_ID_REQUIRED, before the token is parsed")
    void testMissingWorkspaceIdIs400() {
        ApiException ex = assertThrows(ApiException.class, () -> controller.getMyDeals(JWT, Map.of()));

        assertEquals("WORKSPACE_ID_REQUIRED", ex.getCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        verifyNoInteractions(onBehalfAuthResolver, getMyDealsExecutor);
        // [QA Wave 2 should-fix] This was the one refusal on this surface that wrote no audit row,
        // which contradicted the class javadoc's "every rejection writes a row" and left a path
        // reachable with the flag ON and a malformed body completely untraceable. Null tenant key:
        // the body carried no workspace id and the token has not been parsed, so there is no
        // identity to record that would not be invented.
        assertRejectionRow(null, "get_my_deals", "WORKSPACE_ID_REQUIRED");
    }

    @Test
    @DisplayName(
            "a blank workspace_id is refused and audited exactly like a missing one -- on both"
                    + " routes, so neither is a hole in the rejection series")
    void testBlankWorkspaceIdIsAuditedOnEveryRoute() {
        Map<String, Object> blank = Map.of("workspace_id", "   ");

        assertEquals(
                "WORKSPACE_ID_REQUIRED",
                assertThrows(ApiException.class, () -> controller.getMyDeals(JWT, blank)).getCode());
        assertEquals(
                "WORKSPACE_ID_REQUIRED",
                assertThrows(ApiException.class, () -> controller.getMyMetrics(JWT, blank)).getCode());

        verifyNoInteractions(onBehalfAuthResolver, getMyDealsExecutor, getMyMetricsExecutor);
        assertRejectionRow(null, "get_my_deals", "WORKSPACE_ID_REQUIRED");
        assertRejectionRow(null, "get_my_metrics", "WORKSPACE_ID_REQUIRED");
    }

    @Test
    @DisplayName(
            "a BRAND principal is 403 AUDIENCE_PRINCIPAL_MISMATCH -- the creator executors are never"
                    + " reached with a brand identity even when the token's scope resolved")
    void testBrandPrincipalIs403() {
        stubResolverFor(
                CreatorToolName.get_my_deals,
                new OnBehalfContext("01HBRANDUSER123456789", CREATOR_USER_ID, UserType.BRAND, "c"));

        ApiException ex = assertThrows(ApiException.class, () -> controller.getMyDeals(JWT, BODY));

        assertEquals("AUDIENCE_PRINCIPAL_MISMATCH", ex.getCode());
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        verifyNoInteractions(getMyDealsExecutor);
        // Consent is checked AFTER the principal, so a brand token never triggers a creator
        // preferences lookup keyed on a brand user id.
        verify(preferencesService, never()).isConsentAccepted(anyString());
        // [SEC: Kabir Wave 2, finding 3] Audited against the BRAND user id the token proved --
        // that is who tried the creator surface, and it is the answer to "who probed this".
        assertRejectionRow("01HBRANDUSER123456789", "get_my_deals", "AUDIENCE_PRINCIPAL_MISMATCH");
    }

    @Test
    @DisplayName(
            "an unconsented creator is 403 CONSENT_REQUIRED and NOTHING is read -- the DPDP gate"
                    + " runs before the executor touches her deals")
    void testUnconsentedCreatorIs403() {
        stubResolverFor(CreatorToolName.get_my_deals, creatorContext());
        when(preferencesService.isConsentAccepted(CREATOR_USER_ID)).thenReturn(false);

        ApiException ex = assertThrows(ApiException.class, () -> controller.getMyDeals(JWT, BODY));

        assertEquals("CONSENT_REQUIRED", ex.getCode());
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        verifyNoInteractions(getMyDealsExecutor);
        // The validator is still never reached -- the consent gate runs ahead of it.
        verify(creatorToolCallValidator, never()).validateAndResolve(anyString(), anyString());
        assertRejectionRow(CREATOR_USER_ID, "get_my_deals", "CONSENT_REQUIRED");
    }

    @Test
    @DisplayName(
            "get_my_deals happy path: the executor runs with the JWT-verified user id (never the"
                    + " body's) and an ALLOWED audit row is written with the tool's real tier")
    void testGetMyDealsHappyPath() {
        stubResolverFor(CreatorToolName.get_my_deals, creatorContext());
        when(preferencesService.isConsentAccepted(CREATOR_USER_ID)).thenReturn(true);
        when(creatorToolCallValidator.validateAndResolve("get_my_deals", CREATOR_USER_ID))
                .thenReturn(CreatorToolName.get_my_deals);
        when(creatorToolCallValidator.tierOf(CreatorToolName.get_my_deals)).thenReturn(MeeraToolTier.R);
        GetMyDealsResult expected = new GetMyDealsResult(List.of(), 0, 0);
        when(getMyDealsExecutor.execute(CREATOR_USER_ID, BODY)).thenReturn(expected);

        ResponseEntity<ApiResponse<GetMyDealsResult>> response = controller.getMyDeals(JWT, BODY);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(expected, response.getBody().data());
        verify(getMyDealsExecutor).execute(CREATOR_USER_ID, BODY);
        verify(auditLogService)
                .recordToolCall(
                        eq(CREATOR_USER_ID),
                        eq("get_my_deals"),
                        eq("R"),
                        eq(AuditLogService.OUTCOME_ALLOWED),
                        eq((String) null),
                        eq((String) null),
                        eq((BigDecimal) null),
                        any());
    }

    @Test
    @DisplayName(
            "get_my_metrics requires its OWN scope name -- the route asks the resolver for"
                    + " get_my_metrics, so a token scoped only to get_my_deals cannot reach it")
    void testGetMyMetricsRequiresItsOwnScope() {
        stubResolverFor(CreatorToolName.get_my_metrics, creatorContext());
        when(preferencesService.isConsentAccepted(CREATOR_USER_ID)).thenReturn(true);
        when(creatorToolCallValidator.validateAndResolve("get_my_metrics", CREATOR_USER_ID))
                .thenReturn(CreatorToolName.get_my_metrics);
        when(creatorToolCallValidator.tierOf(CreatorToolName.get_my_metrics)).thenReturn(MeeraToolTier.R);
        GetMyMetricsResult expected =
                new GetMyMetricsResult(
                        new MetricsResult(false, null, null, null, null, null, null, "NANO", null));
        when(getMyMetricsExecutor.execute(CREATOR_USER_ID, BODY)).thenReturn(expected);

        ResponseEntity<ApiResponse<GetMyMetricsResult>> response = controller.getMyMetrics(JWT, BODY);

        assertEquals(expected, response.getBody().data());
        verify(onBehalfAuthResolver)
                .resolveForWorkspaceRequiringScope(JWT, CREATOR_USER_ID, "get_my_metrics");
        // ... and never under the other tool's name, which would let one scope open both routes.
        verify(onBehalfAuthResolver, never())
                .resolveForWorkspaceRequiringScope(anyString(), anyString(), eq("get_my_deals"));
    }

    @Test
    @DisplayName(
            "a resolver rejection (bad scope, workspace mismatch, expired token) propagates"
                    + " untouched -- no executor runs and no ALLOWED row is written")
    void testResolverRejectionPropagates() {
        when(onBehalfAuthResolver.resolveForWorkspaceRequiringScope(JWT, CREATOR_USER_ID, "get_my_deals"))
                .thenThrow(
                        new ApiException(
                                "ON_BEHALF_SCOPE_INSUFFICIENT", "nope", HttpStatus.FORBIDDEN));

        ApiException ex = assertThrows(ApiException.class, () -> controller.getMyDeals(JWT, BODY));

        assertEquals("ON_BEHALF_SCOPE_INSUFFICIENT", ex.getCode());
        verifyNoInteractions(getMyDealsExecutor);
        verify(auditLogService, never())
                .recordToolCall(
                        any(), any(), any(), eq(AuditLogService.OUTCOME_ALLOWED), any(), any(), any(),
                        any());
        assertRejectionRow(CREATOR_USER_ID, "get_my_deals", "ON_BEHALF_SCOPE_INSUFFICIENT");
    }

    @Test
    @DisplayName(
            "[SEC: Kabir Wave 2, finding 3] THE POINT: probing another creator's workspace_id"
                    + " leaves a REJECTED row naming the id that was probed -- it used to leave"
                    + " nothing at all")
    void testWorkspaceMismatchIsAudited() {
        String victimWorkspaceId = "01HVICTIMCREATOR98765";
        Map<String, Object> probe = Map.of("workspace_id", victimWorkspaceId);
        when(onBehalfAuthResolver.resolveForWorkspaceRequiringScope(
                        JWT, victimWorkspaceId, "get_my_metrics"))
                .thenThrow(
                        new ApiException(
                                "ON_BEHALF_WORKSPACE_MISMATCH", "nope", HttpStatus.FORBIDDEN));

        ApiException ex =
                assertThrows(ApiException.class, () -> controller.getMyMetrics(JWT, probe));

        assertEquals("ON_BEHALF_WORKSPACE_MISMATCH", ex.getCode());
        verifyNoInteractions(getMyMetricsExecutor);
        // The row is keyed on the PROBED id, which is the whole forensic value: it answers
        // "was anyone poking at this creator" from the audit table alone.
        assertRejectionRow(victimWorkspaceId, "get_my_metrics", "ON_BEHALF_WORKSPACE_MISMATCH");
    }

    @Test
    @DisplayName(
            "[SEC: Kabir Wave 2, finding 3] an executor that throws writes a FAILED row, not a"
                    + " REJECTED one -- the caller was entitled to the read and the server could not"
                    + " serve it")
    void testExecutorFailureWritesFailedRow() {
        stubResolverFor(CreatorToolName.get_my_deals, creatorContext());
        when(preferencesService.isConsentAccepted(CREATOR_USER_ID)).thenReturn(true);
        when(creatorToolCallValidator.validateAndResolve("get_my_deals", CREATOR_USER_ID))
                .thenReturn(CreatorToolName.get_my_deals);
        when(creatorToolCallValidator.tierOf(CreatorToolName.get_my_deals)).thenReturn(MeeraToolTier.R);
        when(getMyDealsExecutor.execute(CREATOR_USER_ID, BODY))
                .thenThrow(new IllegalStateException("deal projection blew up"));

        assertThrows(IllegalStateException.class, () -> controller.getMyDeals(JWT, BODY));

        verify(auditLogService)
                .recordToolCall(
                        eq(CREATOR_USER_ID),
                        eq("get_my_deals"),
                        eq("R"),
                        eq(AuditLogService.OUTCOME_FAILED),
                        eq("EXECUTOR_ERROR"),
                        eq((String) null),
                        eq((BigDecimal) null),
                        any());
        verify(auditLogService, never())
                .recordToolCall(
                        any(), any(), any(), eq(AuditLogService.OUTCOME_ALLOWED), any(), any(), any(),
                        any());
    }

    @Test
    @DisplayName(
            "[SEC: Kabir Wave 2, finding 3] the outcome written is one of the three real constants"
                    + " -- there is no OUTCOME_OK, and a row with an invented outcome would be"
                    + " invisible to every query over this table")
    void testRejectionOutcomeIsARealConstant() {
        when(featureProperties.isCreatorEnabled()).thenReturn(false);

        assertThrows(ApiException.class, () -> controller.getMyDeals(JWT, BODY));

        ArgumentCaptor<String> outcome = ArgumentCaptor.forClass(String.class);
        verify(auditLogService)
                .recordToolCall(
                        any(), any(), any(), outcome.capture(), any(), any(), any(), any());
        assertEquals(AuditLogService.OUTCOME_REJECTED, outcome.getValue());
        assertTrue(
                List.of(
                                AuditLogService.OUTCOME_ALLOWED,
                                AuditLogService.OUTCOME_REJECTED,
                                AuditLogService.OUTCOME_FAILED)
                        .contains(outcome.getValue()));
    }

    // =====================================================================================
    // Wave 3 - the two routes added with RateQuoteService and DealRiskService
    // =====================================================================================

    @Test
    @DisplayName(
            "estimate_my_rate happy path: the executor runs with the JWT-verified user id and an"
                    + " ALLOWED row is written under the tool's own name and tier")
    void testEstimateMyRateHappyPath() {
        stubResolverFor(CreatorToolName.estimate_my_rate, creatorContext());
        when(preferencesService.isConsentAccepted(CREATOR_USER_ID)).thenReturn(true);
        when(creatorToolCallValidator.validateAndResolve("estimate_my_rate", CREATOR_USER_ID))
                .thenReturn(CreatorToolName.estimate_my_rate);
        when(creatorToolCallValidator.tierOf(CreatorToolName.estimate_my_rate))
                .thenReturn(MeeraToolTier.R);
        EstimateMyRateResult expected = new EstimateMyRateResult(emptyQuote());
        when(estimateMyRateExecutor.execute(CREATOR_USER_ID, BODY)).thenReturn(expected);

        ResponseEntity<ApiResponse<EstimateMyRateResult>> response =
                controller.estimateMyRate(JWT, BODY);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(expected, response.getBody().data());
        verify(estimateMyRateExecutor).execute(CREATOR_USER_ID, BODY);
        verify(onBehalfAuthResolver)
                .resolveForWorkspaceRequiringScope(JWT, CREATOR_USER_ID, "estimate_my_rate");
        verify(auditLogService)
                .recordToolCall(
                        eq(CREATOR_USER_ID),
                        eq("estimate_my_rate"),
                        eq("R"),
                        eq(AuditLogService.OUTCOME_ALLOWED),
                        eq((String) null),
                        eq((String) null),
                        eq((BigDecimal) null),
                        any());
    }

    @Test
    @DisplayName(
            "check_deal_risks happy path: flags reach the caller unaltered and the ALLOWED row"
                    + " names check_deal_risks, not the route it was copied from")
    void testCheckDealRisksHappyPath() {
        stubResolverFor(CreatorToolName.check_deal_risks, creatorContext());
        when(preferencesService.isConsentAccepted(CREATOR_USER_ID)).thenReturn(true);
        when(creatorToolCallValidator.validateAndResolve("check_deal_risks", CREATOR_USER_ID))
                .thenReturn(CreatorToolName.check_deal_risks);
        when(creatorToolCallValidator.tierOf(CreatorToolName.check_deal_risks))
                .thenReturn(MeeraToolTier.R);
        RiskFlag flag =
                new RiskFlag(
                        "BELOW_FLOOR",
                        "CRITICAL",
                        "Offer is below your floor",
                        "Offer is 20,000 against your floor of 36,000.",
                        null,
                        "Counter at your floor of 36,000.",
                        Map.of(),
                        true);
        CheckDealRisksResult expected =
                new CheckDealRisksResult(List.of(flag), "CRITICAL", "DEAL", "01HDEAL123");
        when(checkDealRisksExecutor.execute(CREATOR_USER_ID, BODY)).thenReturn(expected);

        ResponseEntity<ApiResponse<CheckDealRisksResult>> response =
                controller.checkDealRisks(JWT, BODY);

        assertEquals(expected, response.getBody().data());
        assertEquals("CRITICAL", response.getBody().data().highestSeverity());
        verify(onBehalfAuthResolver)
                .resolveForWorkspaceRequiringScope(JWT, CREATOR_USER_ID, "check_deal_risks");
        verify(auditLogService)
                .recordToolCall(
                        eq(CREATOR_USER_ID),
                        eq("check_deal_risks"),
                        eq("R"),
                        eq(AuditLogService.OUTCOME_ALLOWED),
                        eq((String) null),
                        eq((String) null),
                        eq((BigDecimal) null),
                        any());
    }

    @Test
    @DisplayName(
            "the two new routes refuse a BRAND principal exactly like the first two -- these read"
                    + " the creator's floors, which is precisely what the info barrier contains")
    void testBrandPrincipalIs403OnTheWave3Routes() {
        OnBehalfContext brand =
                new OnBehalfContext("01HBRANDUSER123456789", CREATOR_USER_ID, UserType.BRAND, "c");
        stubResolverFor(CreatorToolName.estimate_my_rate, brand);
        stubResolverFor(CreatorToolName.check_deal_risks, brand);

        assertEquals(
                "AUDIENCE_PRINCIPAL_MISMATCH",
                assertThrows(ApiException.class, () -> controller.estimateMyRate(JWT, BODY)).getCode());
        assertEquals(
                "AUDIENCE_PRINCIPAL_MISMATCH",
                assertThrows(ApiException.class, () -> controller.checkDealRisks(JWT, BODY)).getCode());

        verifyNoInteractions(estimateMyRateExecutor, checkDealRisksExecutor);
        verify(preferencesService, never()).isConsentAccepted(anyString());
        assertRejectionRow(
                "01HBRANDUSER123456789", "estimate_my_rate", "AUDIENCE_PRINCIPAL_MISMATCH");
        assertRejectionRow(
                "01HBRANDUSER123456789", "check_deal_risks", "AUDIENCE_PRINCIPAL_MISMATCH");
    }

    @Test
    @DisplayName("the two new routes refuse an unconsented creator before any pricing or rule runs")
    void testUnconsentedCreatorIs403OnTheWave3Routes() {
        stubResolverFor(CreatorToolName.estimate_my_rate, creatorContext());
        stubResolverFor(CreatorToolName.check_deal_risks, creatorContext());
        when(preferencesService.isConsentAccepted(CREATOR_USER_ID)).thenReturn(false);

        assertEquals(
                "CONSENT_REQUIRED",
                assertThrows(ApiException.class, () -> controller.estimateMyRate(JWT, BODY)).getCode());
        assertEquals(
                "CONSENT_REQUIRED",
                assertThrows(ApiException.class, () -> controller.checkDealRisks(JWT, BODY)).getCode());

        verifyNoInteractions(estimateMyRateExecutor, checkDealRisksExecutor);
        verify(creatorToolCallValidator, never()).validateAndResolve(anyString(), anyString());
        assertRejectionRow(CREATOR_USER_ID, "estimate_my_rate", "CONSENT_REQUIRED");
        assertRejectionRow(CREATOR_USER_ID, "check_deal_risks", "CONSENT_REQUIRED");
    }

    @Test
    @DisplayName(
            "each Wave 3 route asks the resolver for its OWN scope name -- a token scoped to"
                    + " estimate_my_rate alone cannot reach check_deal_risks")
    void testWave3RoutesAreScopedIndividually() {
        when(onBehalfAuthResolver.resolveForWorkspaceRequiringScope(
                        JWT, CREATOR_USER_ID, "check_deal_risks"))
                .thenThrow(
                        new ApiException("ON_BEHALF_SCOPE_INSUFFICIENT", "nope", HttpStatus.FORBIDDEN));

        ApiException ex =
                assertThrows(ApiException.class, () -> controller.checkDealRisks(JWT, BODY));

        assertEquals("ON_BEHALF_SCOPE_INSUFFICIENT", ex.getCode());
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        verifyNoInteractions(checkDealRisksExecutor, estimateMyRateExecutor);
        verify(auditLogService, never())
                .recordToolCall(
                        any(), any(), any(), eq(AuditLogService.OUTCOME_ALLOWED), any(), any(), any(),
                        any());
        assertRejectionRow(CREATOR_USER_ID, "check_deal_risks", "ON_BEHALF_SCOPE_INSUFFICIENT");
    }

    // =====================================================================================
    // get_brief - the read half of "paste and read"
    // =====================================================================================

    private void stubGetBriefAllowed() {
        stubResolverFor(CreatorToolName.get_brief, creatorContext());
        when(preferencesService.isConsentAccepted(CREATOR_USER_ID)).thenReturn(true);
        when(creatorToolCallValidator.validateAndResolve("get_brief", CREATOR_USER_ID))
                .thenReturn(CreatorToolName.get_brief);
        when(creatorToolCallValidator.tierOf(CreatorToolName.get_brief)).thenReturn(MeeraToolTier.R);
    }

    private static GetBriefResult briefResult(String dealId) {
        return new GetBriefResult(
                "01HBRIEF0000000000000A",
                dealId == null ? "PASTED" : "PLATFORM",
                "ANALYZED",
                dealId,
                null,
                List.of(),
                emptyQuote(),
                "AI");
    }

    @Test
    @DisplayName(
            "get_brief by brief_id: the executor runs with the JWT-verified user id and the body as"
                    + " sent, under its OWN scope name, and an ALLOWED row is written with tier R")
    void testGetBriefByBriefIdHappyPath() {
        stubGetBriefAllowed();
        Map<String, Object> body =
                Map.of("workspace_id", CREATOR_USER_ID, "brief_id", "01HBRIEF0000000000000A");
        GetBriefResult expected = briefResult(null);
        when(getBriefExecutor.execute(CREATOR_USER_ID, body)).thenReturn(expected);

        ResponseEntity<ApiResponse<GetBriefResult>> response = controller.getBrief(JWT, body);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(expected, response.getBody().data());
        verify(onBehalfAuthResolver).resolveForWorkspaceRequiringScope(JWT, CREATOR_USER_ID, "get_brief");
        verify(getBriefExecutor).execute(CREATOR_USER_ID, body);
        verifyNoInteractions(
                getMyDealsExecutor, getMyMetricsExecutor, estimateMyRateExecutor, checkDealRisksExecutor);
        verify(auditLogService)
                .recordToolCall(
                        eq(CREATOR_USER_ID),
                        eq("get_brief"),
                        eq("R"),
                        eq(AuditLogService.OUTCOME_ALLOWED),
                        eq((String) null),
                        eq((String) null),
                        eq((BigDecimal) null),
                        any());
    }

    @Test
    @DisplayName("get_brief by deal_id: the deal id reaches the executor untouched and the read is ALLOWED")
    void testGetBriefByDealIdHappyPath() {
        stubGetBriefAllowed();
        Map<String, Object> body =
                Map.of("workspace_id", CREATOR_USER_ID, "deal_id", "01HDEAL00000000000000A");
        GetBriefResult expected = briefResult("01HDEAL00000000000000A");
        when(getBriefExecutor.execute(CREATOR_USER_ID, body)).thenReturn(expected);

        ResponseEntity<ApiResponse<GetBriefResult>> response = controller.getBrief(JWT, body);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("01HDEAL00000000000000A", response.getBody().data().dealId());
        verify(getBriefExecutor).execute(CREATOR_USER_ID, body);
        verify(auditLogService)
                .recordToolCall(
                        eq(CREATOR_USER_ID),
                        eq("get_brief"),
                        eq("R"),
                        eq(AuditLogService.OUTCOME_ALLOWED),
                        eq((String) null),
                        eq((String) null),
                        eq((BigDecimal) null),
                        any());
    }

    @Test
    @DisplayName(
            "get_brief refuses a BRAND principal with 403 AUDIENCE_PRINCIPAL_MISMATCH -- the result"
                    + " carries the creator's floor, and no brand identity may reach the executor")
    void testGetBriefBrandPrincipalIs403() {
        stubResolverFor(
                CreatorToolName.get_brief,
                new OnBehalfContext("01HBRANDUSER123456789", CREATOR_USER_ID, UserType.BRAND, "c"));

        ApiException ex = assertThrows(ApiException.class, () -> controller.getBrief(JWT, BODY));

        assertEquals("AUDIENCE_PRINCIPAL_MISMATCH", ex.getCode());
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        verifyNoInteractions(getBriefExecutor);
        verify(preferencesService, never()).isConsentAccepted(anyString());
        verify(creatorToolCallValidator, never()).validateAndResolve(anyString(), anyString());
        assertRejectionRow("01HBRANDUSER123456789", "get_brief", "AUDIENCE_PRINCIPAL_MISMATCH");
        assertNoAllowedRow();
    }

    @Test
    @DisplayName(
            "get_brief refuses an unconsented creator with 403 CONSENT_REQUIRED before the validator"
                    + " or the executor -- no brief is read, and no platform brief is created")
    void testGetBriefUnconsentedCreatorIs403() {
        stubResolverFor(CreatorToolName.get_brief, creatorContext());
        when(preferencesService.isConsentAccepted(CREATOR_USER_ID)).thenReturn(false);

        ApiException ex = assertThrows(ApiException.class, () -> controller.getBrief(JWT, BODY));

        assertEquals("CONSENT_REQUIRED", ex.getCode());
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        verifyNoInteractions(getBriefExecutor);
        verify(creatorToolCallValidator, never()).validateAndResolve(anyString(), anyString());
        assertRejectionRow(CREATOR_USER_ID, "get_brief", "CONSENT_REQUIRED");
        assertNoAllowedRow();
    }

    @Test
    @DisplayName("get_brief with no workspace_id is 400 WORKSPACE_ID_REQUIRED, audited, token never parsed")
    void testGetBriefMissingWorkspaceIdIs400() {
        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> controller.getBrief(JWT, Map.of("brief_id", "01HBRIEF0000000000000A")));

        assertEquals("WORKSPACE_ID_REQUIRED", ex.getCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        verifyNoInteractions(onBehalfAuthResolver, getBriefExecutor);
        assertRejectionRow(null, "get_brief", "WORKSPACE_ID_REQUIRED");
    }

    @Test
    @DisplayName(
            "get_brief asks the resolver for get_brief ITSELF -- a token whose scope lacks it is 403"
                    + " ON_BEHALF_SCOPE_INSUFFICIENT with a row, and no other tool's scope is consulted")
    void testGetBriefScopeInsufficientIsAudited() {
        when(onBehalfAuthResolver.resolveForWorkspaceRequiringScope(JWT, CREATOR_USER_ID, "get_brief"))
                .thenThrow(new ApiException("ON_BEHALF_SCOPE_INSUFFICIENT", "nope", HttpStatus.FORBIDDEN));

        ApiException ex = assertThrows(ApiException.class, () -> controller.getBrief(JWT, BODY));

        assertEquals("ON_BEHALF_SCOPE_INSUFFICIENT", ex.getCode());
        verify(onBehalfAuthResolver, never())
                .resolveForWorkspaceRequiringScope(anyString(), anyString(), eq("get_my_deals"));
        verifyNoInteractions(getBriefExecutor);
        assertRejectionRow(CREATOR_USER_ID, "get_brief", "ON_BEHALF_SCOPE_INSUFFICIENT");
        assertNoAllowedRow();
    }

    @Test
    @DisplayName(
            "get_brief: a validator that resolves a DIFFERENT tool is 403 TOOL_ROUTE_MISMATCH with the"
                    + " controller's own row; a validator rejection is 403 with its code and NO second row")
    void testGetBriefValidatorRefusals() {
        stubResolverFor(CreatorToolName.get_brief, creatorContext());
        when(preferencesService.isConsentAccepted(CREATOR_USER_ID)).thenReturn(true);
        when(creatorToolCallValidator.validateAndResolve("get_brief", CREATOR_USER_ID))
                .thenReturn(CreatorToolName.get_my_deals)
                .thenThrow(new ToolCallRejectedException("FORBIDDEN_TIER", "nope"));

        ApiException mismatch = assertThrows(ApiException.class, () -> controller.getBrief(JWT, BODY));
        assertEquals("TOOL_ROUTE_MISMATCH", mismatch.getCode());
        assertEquals(HttpStatus.FORBIDDEN, mismatch.getStatus());
        assertRejectionRow(CREATOR_USER_ID, "get_brief", "TOOL_ROUTE_MISMATCH");

        ApiException rejected = assertThrows(ApiException.class, () -> controller.getBrief(JWT, BODY));
        assertEquals("FORBIDDEN_TIER", rejected.getCode());
        assertEquals(HttpStatus.FORBIDDEN, rejected.getStatus());
        // The validator writes that row itself (CreatorToolCallValidator#validateAndResolve); the
        // controller adding one would double-count every rejection.
        verify(auditLogService, never())
                .recordToolCall(any(), any(), any(), any(), eq("FORBIDDEN_TIER"), any(), any(), any());

        verifyNoInteractions(getBriefExecutor);
        assertNoAllowedRow();
    }

    @Test
    @DisplayName(
            "get_brief on a brief or deal that is not hers: the executor's 404 reaches the caller"
                    + " unchanged (never turned into a 403) and is audited with its code")
    void testGetBriefNotFoundPropagatesAs404() {
        stubGetBriefAllowed();
        when(getBriefExecutor.execute(CREATOR_USER_ID, BODY))
                .thenThrow(new ApiException("BRIEF_NOT_FOUND", "Brief not found", HttpStatus.NOT_FOUND));

        ApiException ex = assertThrows(ApiException.class, () -> controller.getBrief(JWT, BODY));

        assertEquals("BRIEF_NOT_FOUND", ex.getCode());
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        // handleRead records an executor refusal as FAILED carrying the executor's code -- the same
        // shape every other route on this controller writes for it.
        verify(auditLogService)
                .recordToolCall(
                        eq(CREATOR_USER_ID),
                        eq("get_brief"),
                        eq("R"),
                        eq(AuditLogService.OUTCOME_FAILED),
                        eq("BRIEF_NOT_FOUND"),
                        eq((String) null),
                        eq((BigDecimal) null),
                        any());
        assertNoAllowedRow();
    }

    /**
     * What earns this controller its entry in {@code FloorBarrierTest.FLOOR_PERMITTED_CONTROLLERS}.
     * That permit rests on "every route runs {@code requireCreatorPrincipal}", and until this test the
     * claim was proven route by route by hand-written tests -- which never covered
     * {@code get_my_metrics} at all. This one drives EVERY {@code @PostMapping} handler found by
     * reflection, so a route that skipped the principal check would turn it red without anyone
     * remembering to write its brand test. The refusal itself is real code in this controller, not a
     * stub: the resolver hands back a BRAND context and the controller has to refuse it.
     */
    @Test
    @DisplayName(
            "EVERY @PostMapping route refuses a BRAND principal with 403 AUDIENCE_PRINCIPAL_MISMATCH and"
                    + " asks the resolver for its own tool name -- the property FloorBarrierTest's permit"
                    + " for this controller rests on")
    void testBrandPrincipalIsRefusedOnEveryRoute() throws Exception {
        OnBehalfContext brand =
                new OnBehalfContext("01HBRANDUSER123456789", CREATOR_USER_ID, UserType.BRAND, "c");
        when(onBehalfAuthResolver.resolveForWorkspaceRequiringScope(
                        eq(JWT), eq(CREATOR_USER_ID), anyString()))
                .thenReturn(brand);

        TreeSet<String> routes = new TreeSet<>();
        for (Method method : CreatorMeeraToolController.class.getDeclaredMethods()) {
            PostMapping mapping = method.getAnnotation(PostMapping.class);
            if (mapping == null) {
                continue;
            }
            String tool = mapping.value()[0].substring(1);
            routes.add(tool);

            InvocationTargetException thrown =
                    assertThrows(
                            InvocationTargetException.class, () -> method.invoke(controller, JWT, BODY));
            ApiException ex = (ApiException) thrown.getCause();
            assertEquals("AUDIENCE_PRINCIPAL_MISMATCH", ex.getCode(), "route " + tool);
            assertEquals(HttpStatus.FORBIDDEN, ex.getStatus(), "route " + tool);
            verify(onBehalfAuthResolver).resolveForWorkspaceRequiringScope(JWT, CREATOR_USER_ID, tool);
            assertRejectionRow("01HBRANDUSER123456789", tool, "AUDIENCE_PRINCIPAL_MISMATCH");
        }

        // Non-vacuity: the five wired routes, by name. A reflection that found nothing would pass
        // every assertion in the loop above.
        assertEquals(
                new TreeSet<>(
                        List.of(
                                "get_my_deals",
                                "get_brief",
                                "estimate_my_rate",
                                "get_my_metrics",
                                "check_deal_risks")),
                routes);
        verifyNoInteractions(
                getMyDealsExecutor,
                getMyMetricsExecutor,
                estimateMyRateExecutor,
                checkDealRisksExecutor,
                getBriefExecutor);
        verify(preferencesService, never()).isConsentAccepted(anyString());
        assertNoAllowedRow();
    }

    private void assertNoAllowedRow() {
        verify(auditLogService, never())
                .recordToolCall(
                        any(), any(), any(), eq(AuditLogService.OUTCOME_ALLOWED), any(), any(), any(),
                        any());
    }

    /** Shape only -- the controller never reads a quote's contents. */
    private static PackageQuote emptyQuote() {
        return new PackageQuote(
                List.of(), List.of(), null, null, "12,000", new BigDecimal("12000"), null, null,
                null, null, null, null, "INR", null, 2, "your floor", 0, null, null, false, null);
    }

    /**
     * Asserts exactly one REJECTED row exists for this tool with this reason code and tenant key.
     * The tool name is asserted too: a rejection row that does not say which tool was refused
     * cannot be joined to the rest of the series.
     */
    private void assertRejectionRow(String expectedTenantKey, String tool, String reasonCode) {
        verify(auditLogService)
                .recordToolCall(
                        eq(expectedTenantKey),
                        eq(tool),
                        any(),
                        eq(AuditLogService.OUTCOME_REJECTED),
                        eq(reasonCode),
                        eq((String) null),
                        eq((BigDecimal) null),
                        any());
    }
}
