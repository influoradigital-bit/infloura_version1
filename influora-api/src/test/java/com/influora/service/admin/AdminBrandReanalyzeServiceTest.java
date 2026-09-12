package com.influora.service.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.BrandProfile;
import com.influora.domain.enums.AdminRole;
import com.influora.domain.enums.AnalysisStatus;
import com.influora.repository.BrandProfileRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.brand.AnalyzeSiteTriggerService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * FIX 2 (2026-09-12 analyze-site prod incident) — {@link AdminBrandReanalyzeService}.
 *
 * <p><b>Why every test here is red before the fix.</b> {@code AdminBrandReanalyzeService} did not
 * exist, and neither did {@code POST /admin/brands/{workspaceId}/reanalyze}; {@code
 * BrandProfileRepository} exposed only {@code findByWorkspaceId}, nothing in {@code
 * com.influora.job} touched {@code BrandProfile}, and {@code FAILED} was terminal with no retry
 * anywhere. So pre-fix this file does not compile — the class under test is absent. That is the
 * strongest possible red: there is no version of the old code these assertions can pass against,
 * and each test below additionally states the specific behaviour it pins so it stays honest if the
 * class is later changed rather than deleted.
 *
 * <p><b>Mockito note for this repo:</b> {@code anyString()} is type-matching and does NOT match
 * {@code null} — a {@code never()}-verify has silently passed here before against code that called
 * the mock with {@code null}. The no-URL test below therefore verifies {@code trigger} was never
 * called with EITHER a string or {@code null} in the url position, using {@code isNull()} for the
 * second, so it cannot pass vacuously.
 */
class AdminBrandReanalyzeServiceTest {

    private static final String WORKSPACE_ID = "ws_stuck_brand";
    private static final String WEBSITE_URL = "https://acme.example.com";

    private AdminContextService adminContext;
    private AdminAuditLogService adminAuditLogService;
    private BrandProfileRepository brandProfileRepository;
    private AnalyzeSiteTriggerService analyzeSiteTrigger;
    private AuthPrincipal principal;
    private HttpServletRequest request;

    private AdminBrandReanalyzeService service;

    @BeforeEach
    void setUp() {
        adminContext = mock(AdminContextService.class);
        adminAuditLogService = mock(AdminAuditLogService.class);
        brandProfileRepository = mock(BrandProfileRepository.class);
        analyzeSiteTrigger = mock(AnalyzeSiteTriggerService.class);
        principal = mock(AuthPrincipal.class);
        request = mock(HttpServletRequest.class);

        service =
                new AdminBrandReanalyzeService(
                        adminContext,
                        adminAuditLogService,
                        brandProfileRepository,
                        analyzeSiteTrigger);
    }

    private static BrandProfile profile(String websiteUrl, AnalysisStatus status) {
        return BrandProfile.builder()
                .id("bp_1")
                .workspaceId(WORKSPACE_ID)
                .websiteUrl(websiteUrl)
                .analysisStatus(status)
                .build();
    }

    /**
     * The headline case: one of the 6 live brands sitting terminally FAILED gets re-driven with the
     * URL already on its profile.
     *
     * <p>RED PRE-FIX: no {@code reanalyze} entry point existed at all — a FAILED row could only be
     * re-driven by CHANGING the website url, and both trigger call sites ({@code
     * OnboardingService:75,202-212}, {@code WorkspaceService:126}) gate on {@code hasChanged}, so
     * re-saving the same url did nothing. This assertion has no pre-fix code path.
     */
    @Test
    @DisplayName("FAILED profile is re-triggered with the URL already on file")
    void testFailedProfileIsRetriggeredWithStoredUrl() {
        when(brandProfileRepository.findByWorkspaceId(WORKSPACE_ID))
                .thenReturn(Optional.of(profile(WEBSITE_URL, AnalysisStatus.FAILED)));

        Map<String, Object> body = service.reanalyze(principal, request, WORKSPACE_ID);

        // The URL is taken from the profile, never from the caller — an admin endpoint that
        // accepted a URL would be an SSRF-shaped surface.
        verify(analyzeSiteTrigger).trigger(WORKSPACE_ID, WEBSITE_URL);
        assertEquals(AnalysisStatus.FAILED.name(), body.get("previousStatus"));
        assertEquals(AnalysisStatus.ANALYZING.name(), body.get("analysisStatus"));
        assertEquals(true, body.get("success"));
    }

    /**
     * The status has to actually LEAVE {@code FAILED}, or the existing polling UI never notices a
     * retry is in flight. This is asserted through the real {@code trigger} contract rather than by
     * checking that this class wrote the column: the service deliberately does not write
     * {@code analysisStatus} itself (that would be a second, divergeable definition of "retry in
     * flight") — {@code AnalyzeSiteTriggerService#markAnalyzing} owns it.
     *
     * <p>RED PRE-FIX: same as above — nothing existed to move the row off FAILED, and the
     * reported status therefore could not be ANALYZING.
     */
    @Test
    @DisplayName("reported status leaves FAILED (and is delegated, not hand-written)")
    void testStatusLeavesFailed() {
        when(brandProfileRepository.findByWorkspaceId(WORKSPACE_ID))
                .thenReturn(Optional.of(profile(WEBSITE_URL, AnalysisStatus.FAILED)));

        Map<String, Object> body = service.reanalyze(principal, request, WORKSPACE_ID);

        assertNotEquals(
                AnalysisStatus.FAILED.name(),
                body.get("analysisStatus"),
                "a retry that leaves the row FAILED is invisible to the polling UI");
        assertEquals(AnalysisStatus.ANALYZING.name(), body.get("analysisStatus"));
    }

    /**
     * RED PRE-FIX: no refusal existed because no endpoint existed. Post-fix this pins that a
     * URL-less profile is REFUSED rather than silently accepted — {@code trigger()} no-ops on a
     * blank url by design, so a 200 here would tell an operator a retry is running when nothing
     * was scheduled.
     *
     * <p>The never()-verify is doubled deliberately: {@code anyString()} would not match a {@code
     * null} url, which is exactly how a never()-verify has passed vacuously in this repo before.
     */
    @Test
    @DisplayName("profile with no website URL is refused 400, and nothing is triggered")
    void testNoWebsiteUrlIsRefused() {
        when(brandProfileRepository.findByWorkspaceId(WORKSPACE_ID))
                .thenReturn(Optional.of(profile(null, AnalysisStatus.FAILED)));

        ApiException thrown =
                assertThrows(
                        ApiException.class, () -> service.reanalyze(principal, request, WORKSPACE_ID));

        assertEquals(HttpStatus.BAD_REQUEST, thrown.getStatus());
        assertEquals("BRAND_HAS_NO_WEBSITE_URL", thrown.getCode());
        verify(analyzeSiteTrigger, never()).trigger(anyString(), anyString());
        verify(analyzeSiteTrigger, never())
                .trigger(anyString(), org.mockito.ArgumentMatchers.isNull());
        verify(analyzeSiteTrigger, never())
                .trigger(
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.isNull());
    }

    /** A blank (whitespace) url is the same refusal as a null one — {@code trigger()} no-ops on
     * both, so both must be refused rather than reported as a running retry. */
    @Test
    @DisplayName("profile with a blank website URL is refused the same way")
    void testBlankWebsiteUrlIsRefused() {
        when(brandProfileRepository.findByWorkspaceId(WORKSPACE_ID))
                .thenReturn(Optional.of(profile("   ", AnalysisStatus.FAILED)));

        ApiException thrown =
                assertThrows(
                        ApiException.class, () -> service.reanalyze(principal, request, WORKSPACE_ID));

        assertEquals("BRAND_HAS_NO_WEBSITE_URL", thrown.getCode());
        verify(analyzeSiteTrigger, never()).trigger(anyString(), anyString());
        verify(analyzeSiteTrigger, never())
                .trigger(anyString(), org.mockito.ArgumentMatchers.isNull());
    }

    /**
     * No existence oracle: an unknown workspace id and a known workspace with no {@code
     * brand_profiles} row must be INDISTINGUISHABLE to the caller — same code, same message, same
     * status — so the endpoint cannot be used to enumerate which workspace ids exist.
     *
     * <p>RED PRE-FIX: there was no endpoint, so there was no 404 to compare. Post-fix the two are
     * the same branch by construction (the service resolves only the profile and never looks a
     * workspace up separately), and this test is the regression lock on that: adding a
     * "workspace exists?" pre-check later would make one of the two responses differ and turn this
     * red.
     */
    @Test
    @DisplayName("unknown workspace and profile-less workspace 404 indistinguishably")
    void test404sAreIndistinguishable() {
        String unknownWorkspace = "ws_does_not_exist_at_all";
        String profilelessWorkspace = "ws_exists_but_has_no_profile";
        when(brandProfileRepository.findByWorkspaceId(unknownWorkspace)).thenReturn(Optional.empty());
        when(brandProfileRepository.findByWorkspaceId(profilelessWorkspace))
                .thenReturn(Optional.empty());

        ApiException unknown =
                assertThrows(
                        ApiException.class,
                        () -> service.reanalyze(principal, request, unknownWorkspace));
        ApiException profileless =
                assertThrows(
                        ApiException.class,
                        () -> service.reanalyze(principal, request, profilelessWorkspace));

        assertEquals(HttpStatus.NOT_FOUND, unknown.getStatus());
        assertEquals(unknown.getStatus(), profileless.getStatus(), "status must not differ");
        assertEquals(unknown.getCode(), profileless.getCode(), "error code must not differ");
        assertEquals(
                unknown.getMessage(), profileless.getMessage(), "error message must not differ");
        // And it matches what AdminBrandService#requireBrandWorkspace already returns, so the new
        // endpoint is not a distinguishable oracle relative to its siblings either.
        assertEquals("BRAND_NOT_FOUND", unknown.getCode());
        assertEquals("Brand not found", unknown.getMessage());
        verify(analyzeSiteTrigger, never()).trigger(anyString(), anyString());
    }

    /**
     * Idempotent-safe: two calls must both go through. There is one {@code brand_profiles} row per
     * workspace and {@code markAnalyzing} is a find-then-save on it, so no duplicate row is
     * possible; the second analysis simply overwrites the first with the same content. Refusing the
     * second call would rebuild the "stuck with no operator recourse" trap this class removes —
     * a row can sit in ANALYZING forever if the JVM died mid-call.
     *
     * <p>RED PRE-FIX: no class to call twice.
     */
    @Test
    @DisplayName("calling twice is safe — the second call re-triggers rather than being refused")
    void testCallingTwiceIsSafe() {
        when(brandProfileRepository.findByWorkspaceId(WORKSPACE_ID))
                .thenReturn(Optional.of(profile(WEBSITE_URL, AnalysisStatus.FAILED)))
                .thenReturn(Optional.of(profile(WEBSITE_URL, AnalysisStatus.ANALYZING)));

        Map<String, Object> first = service.reanalyze(principal, request, WORKSPACE_ID);
        Map<String, Object> second = service.reanalyze(principal, request, WORKSPACE_ID);

        assertEquals(AnalysisStatus.FAILED.name(), first.get("previousStatus"));
        assertEquals(AnalysisStatus.ANALYZING.name(), second.get("previousStatus"));
        assertEquals(AnalysisStatus.ANALYZING.name(), second.get("analysisStatus"));
        verify(analyzeSiteTrigger, times(2)).trigger(WORKSPACE_ID, WEBSITE_URL);
    }

    /**
     * The admin gate runs BEFORE anything is read or triggered. Asserted by making the gate throw
     * and proving no repository read and no trigger happened — verifying "requireRole was called"
     * against a mock would only prove the call, never the ordering (this repo has been burned by
     * exactly that shape: mocking the collaborator you are trying to verify proves the call, never
     * the effect).
     */
    @Test
    @DisplayName("role gate runs before any read or trigger")
    void testRoleGateRunsFirst() {
        // Explicit args, not matchers: requireRoleWithMfaSatisfied is varargs, and an any()-based
        // stub in the varargs position is ambiguous about arity. This also pins the exact gate the
        // service asks for (SUPER_ADMIN or ADMIN — never SUPPORT, which is view-only).
        when(adminContext.requireRoleWithMfaSatisfied(
                        principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN))
                .thenThrow(new ApiException("FORBIDDEN", "Forbidden", HttpStatus.FORBIDDEN));

        assertThrows(
                ApiException.class, () -> service.reanalyze(principal, request, WORKSPACE_ID));

        verify(brandProfileRepository, never()).findByWorkspaceId(anyString());
        verify(analyzeSiteTrigger, never()).trigger(anyString(), anyString());
        verify(adminAuditLogService, never())
                .record(any(), any(), anyString(), anyString(), anyString(), any(), any(), any());
    }

    /**
     * The audit trail must record the re-run against entity type BRAND with an action and fields
     * that {@code AdminAuditLogService}'s own allow-lists accept. This test cannot prove the row
     * persists (the service is mocked here — {@code AdminAuditLogEntityTypeAllowlistTest} is what
     * guards the allow-lists themselves), so it asserts only the argument shape that test keys
     * off: action UPDATE, entityType BRAND, entityId = the workspace id.
     */
    @Test
    @DisplayName("re-run is audited as UPDATE on BRAND with the workspace id")
    void testAuditTrailShape() {
        when(brandProfileRepository.findByWorkspaceId(WORKSPACE_ID))
                .thenReturn(Optional.of(profile(WEBSITE_URL, AnalysisStatus.FAILED)));

        service.reanalyze(principal, request, WORKSPACE_ID);

        verify(adminAuditLogService)
                .record(
                        eq(principal),
                        eq(request),
                        eq("UPDATE"),
                        eq("BRAND"),
                        eq(WORKSPACE_ID),
                        any(),
                        any(),
                        any());
    }
}
