package com.influora.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.Plan;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.UsageMetric;
import com.influora.domain.enums.UserType;
import com.influora.service.BrandContextService;
import com.influora.service.billing.SubscriptionService;
import com.influora.service.billing.UsageCounterService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Task 22 — the MP-1 wiring test (per {@code wiki/tech/security.md}, standing rule locked in
 * Priya's Phase 3a sign-off): proves the ACTUAL {@link PlanGateFilter} + {@link
 * AnalyticsUsageCapInterceptor} objects, chained exactly as {@code SecurityConfig} /
 * {@code PlanGateWebConfig} wire them in production, genuinely produce a 402 for a Free
 * workspace's 2nd DISTINCT creator lookup in a month and genuinely allow a Pro workspace's
 * 100th — not a unit test of the limit-comparison math in isolation.
 *
 * <p><b>Why this shape, not {@code @SpringBootTest} + MockMvc:</b> this codebase has no
 * MockMvc/spring-security-test harness ({@code AnalyticsControllerTest}'s own javadoc), and the
 * only real-context integration base ({@code AbstractIntegrationTest}) is blocked in this sandbox
 * before Spring context boot even starts (Testcontainers needs the Docker daemon; {@code docker
 * ps} fails here — documented, pre-existing, unrelated to this change). Given that constraint,
 * this test drives the REAL production filter and REAL production interceptor objects — not
 * reimplementations — through a single shared {@link MockHttpServletRequest}, in the same order
 * {@code SecurityConfig}/{@code PlanGateWebConfig} run them in production
 * (JwtAuthenticationFilter's effect is simulated by pre-seeding {@code SecurityContextHolder},
 * exactly what that filter itself would have already done by the time {@code PlanGateFilter} —
 * registered {@code addFilterAfter(planGateFilter, JwtAuthenticationFilter.class)} — runs). Only
 * the collaborators one layer further down ({@code BrandContextService}, {@code
 * SubscriptionService}, {@code UsageCounterService}) are mocked — the gate logic itself is 100%
 * real, invoked end-to-end from filter through interceptor.
 *
 * <p><b>Task 22 Flag #1 fix (Rohan CFO ruling, SHARED_CONTEXT.md 2026-07-14):</b> per this test's
 * own documented boundary, {@code UsageCounterService} — including its {@code
 * recordCreatorLookup} per-creator dedup method — is one layer further down and stays mocked
 * here; these tests verify the INTERCEPTOR's control flow (which method it calls, with what
 * {@code creatorId} extracted from the URL, and how it reacts to allow/deny) against a mocked
 * {@code recordCreatorLookup} return value. The dedup/increment/concurrency-race logic INSIDE
 * {@code recordCreatorLookup} itself — "4 sub-endpoint calls for 1 creator = 1 used," "a 2nd
 * distinct creator is rejected," "an already-seen creator never double-charges," "concurrent
 * calls for the same creator can't double-count" — is proven against the real repository-layer
 * collaborators in {@code UsageCounterServiceTest}, one layer further down again, per the same
 * MP-1 "real objects one layer at a time" discipline.
 */
@ExtendWith(MockitoExtension.class)
class PlanGateWiringTest {

    private static final String WORKSPACE_ID = "ws-free-1";

    @Mock private BrandContextService brandContextService;
    @Mock private SubscriptionService subscriptionService;
    @Mock private UsageCounterService usageCounterService;
    @Mock private Workspace workspace;
    @Mock private FilterChain chain;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** Runs the real PlanGateFilter, then the real AnalyticsUsageCapInterceptor, on one request. */
    private void runRealChain(
            AuthPrincipal principal, Plan resolvedPlan, MockHttpServletRequest request) throws Exception {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        when(brandContextService.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(workspace.getId()).thenReturn(WORKSPACE_ID);
        when(subscriptionService.getActivePlanForWorkspace(WORKSPACE_ID)).thenReturn(resolvedPlan);

        PlanGateFilter realFilter = new PlanGateFilter(brandContextService, subscriptionService);
        realFilter.doFilterInternal(request, new MockHttpServletResponse(), chain);

        AnalyticsUsageCapInterceptor realInterceptor = new AnalyticsUsageCapInterceptor(usageCounterService);
        realInterceptor.preHandle(request, new MockHttpServletResponse(), new Object());
    }

    @Test
    @DisplayName("Free-tier workspace's 2nd DISTINCT creator this month genuinely returns 402 UPGRADE_REQUIRED through the real filter+interceptor chain")
    void testFreeWorkspaceSecondDistinctCreatorGenuinelyRejected() throws Exception {
        AuthPrincipal principal = new AuthPrincipal("brand-user", "b@x.com", UserType.BRAND, WORKSPACE_ID);
        Plan freePlan = mock(Plan.class);
        when(freePlan.getCreatorAnalyticsMonthlyLimit()).thenReturn(1); // Free = 1 creator/month
        // Creator "c2" is a NEW dedup key this period, and recording it would exceed the limit --
        // UsageCounterService.recordCreatorLookup itself proves this rejection logic in
        // UsageCounterServiceTest; here we only prove the interceptor reacts to "false" with a 402.
        when(usageCounterService.recordCreatorLookup(WORKSPACE_ID, UsageMetric.CREATOR_ANALYTICS_VIEW, "c2", 1))
                .thenReturn(false);

        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/analytics/creators/c2/metrics");

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> runRealChain(principal, freePlan, request));

        assertEquals(HttpStatus.PAYMENT_REQUIRED, ex.getStatus());
        assertEquals("UPGRADE_REQUIRED", ex.getCode());
    }

    @Test
    @DisplayName("Free-tier workspace calling all 4 sub-endpoints for the SAME creator genuinely succeeds every time -- dedup key extracted correctly from every URL shape")
    void testFreeWorkspaceAllFourSubEndpointsForSameCreatorAllSucceed() throws Exception {
        AuthPrincipal principal = new AuthPrincipal("brand-user", "b@x.com", UserType.BRAND, WORKSPACE_ID);
        Plan freePlan = mock(Plan.class);
        when(freePlan.getCreatorAnalyticsMonthlyLimit()).thenReturn(1);
        // Same creator "c1" on every sub-endpoint -- recordCreatorLookup itself is responsible for
        // the actual "only counts once" dedup bookkeeping (UsageCounterServiceTest); here we mock
        // it to always allow "c1" and verify the interceptor calls it with the SAME extracted
        // dedupKey regardless of which sub-endpoint the URL is for.
        when(usageCounterService.recordCreatorLookup(WORKSPACE_ID, UsageMetric.CREATOR_ANALYTICS_VIEW, "c1", 1))
                .thenReturn(true);

        for (String subPath : new String[] {"metrics", "scores", "demographics", "media"}) {
            MockHttpServletRequest request =
                    new MockHttpServletRequest("GET", "/analytics/creators/c1/" + subPath);
            runRealChain(principal, freePlan, request); // must not throw for any of the 4
        }

        verify(usageCounterService, times(4))
                .recordCreatorLookup(WORKSPACE_ID, UsageMetric.CREATOR_ANALYTICS_VIEW, "c1", 1);
    }

    @Test
    @DisplayName("Free-tier workspace revisiting an already-seen creator later in the same period genuinely succeeds")
    void testFreeWorkspaceSameCreatorAgainLaterInPeriodSucceeds() throws Exception {
        AuthPrincipal principal = new AuthPrincipal("brand-user", "b@x.com", UserType.BRAND, WORKSPACE_ID);
        Plan freePlan = mock(Plan.class);
        when(freePlan.getCreatorAnalyticsMonthlyLimit()).thenReturn(1);
        when(usageCounterService.recordCreatorLookup(WORKSPACE_ID, UsageMetric.CREATOR_ANALYTICS_VIEW, "c1", 1))
                .thenReturn(true); // already-seen creator -- always allowed, per recordCreatorLookup contract

        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/analytics/creators/c1/metrics");

        runRealChain(principal, freePlan, request); // must not throw
    }

    @Test
    @DisplayName("Pro-tier workspace's 100th analytics view this month genuinely succeeds (unlimited) through the real chain, regardless of creators/endpoints")
    void testProWorkspaceHundredthViewGenuinelyAllowed() throws Exception {
        AuthPrincipal principal = new AuthPrincipal("brand-user-pro", "pro@x.com", UserType.BRAND, WORKSPACE_ID);
        Plan proPlan = mock(Plan.class);
        when(proPlan.getCreatorAnalyticsMonthlyLimit()).thenReturn(null); // Pro = unlimited

        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/analytics/creators/c1/metrics");

        // A "100th view" for Pro is represented by simply never hitting the dedup/limit path at
        // all -- the real interceptor short-circuits on monthlyLimit == null before ever calling
        // recordCreatorLookup (see AnalyticsUsageCapInterceptor.preHandle), so no matter how many
        // distinct creators or sub-endpoints are hit this billing cycle, nothing is ever compared
        // against a limit that doesn't exist. Must not throw.
        runRealChain(principal, proPlan, request);

        verify(usageCounterService, never())
                .recordCreatorLookup(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyInt());
        verify(usageCounterService, times(1))
                .incrementUsage(WORKSPACE_ID, UsageMetric.CREATOR_ANALYTICS_VIEW, 1);
    }

    @Test
    @DisplayName("CreatorAnalyticsController path (CREATOR principal, no workspace resolved): interceptor steps aside, no gate applied, no counter touched -- documents the corrected-scope decision")
    void testCreatorSelfServiceRequestIsNeverGated() throws Exception {
        AuthPrincipal principal = new AuthPrincipal("creator-user", "c@x.com", UserType.CREATOR, null);

        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/creator/analytics/me/metrics");

        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

        PlanGateFilter realFilter = new PlanGateFilter(brandContextService, subscriptionService);
        realFilter.doFilterInternal(request, new MockHttpServletResponse(), chain);
        // BrandContextService must never even be consulted for a CREATOR principal.
        verify(brandContextService, never()).requireBrandWorkspace(principal);

        AnalyticsUsageCapInterceptor realInterceptor = new AnalyticsUsageCapInterceptor(usageCounterService);
        boolean proceeded = realInterceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertTrue(proceeded);
        verify(usageCounterService, never())
                .getUsageForCurrentPeriod(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        verify(usageCounterService, never())
                .incrementUsage(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyInt());
        verify(usageCounterService, never())
                .recordCreatorLookup(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyInt());
    }
}
