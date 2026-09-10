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
import com.influora.service.meera.tool.creator.CreatorToolCallValidator;
import com.influora.service.meera.tool.creator.GetMyDealsExecutor;
import com.influora.service.meera.tool.creator.GetMyMetricsExecutor;
import com.influora.web.dto.meera.CreatorToolDtos.GetMyDealsResult;
import com.influora.web.dto.meera.CreatorToolDtos.GetMyMetricsResult;
import com.influora.web.dto.meera.CreatorToolDtos.MetricsResult;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

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
                        getMyMetricsExecutor);
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

        assertEquals("FEATURE_DISABLED", deals.getCode());
        assertEquals(HttpStatus.NOT_FOUND, deals.getStatus());
        assertEquals("FEATURE_DISABLED", metrics.getCode());
        assertEquals(HttpStatus.NOT_FOUND, metrics.getStatus());

        verifyNoInteractions(
                onBehalfAuthResolver, preferencesService, getMyDealsExecutor, getMyMetricsExecutor);
        // [SEC: Kabir Wave 2, finding 3] The refusal is audited, with a null tenant key: the flag
        // is checked before anything about the caller is proven, so there is no identity to record.
        assertRejectionRow(null, "get_my_deals", "FEATURE_DISABLED");
        assertRejectionRow(null, "get_my_metrics", "FEATURE_DISABLED");
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
