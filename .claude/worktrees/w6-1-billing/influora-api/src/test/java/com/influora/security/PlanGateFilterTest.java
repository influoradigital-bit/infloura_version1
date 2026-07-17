package com.influora.security;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.Plan;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.UserType;
import com.influora.service.BrandContextService;
import com.influora.service.billing.SubscriptionService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Task 22 — real object test of {@link PlanGateFilter#doFilterInternal}, not a re-implementation
 * of its resolution logic. Drives the actual filter with a real {@link MockHttpServletRequest}
 * (spring-test, no MockMvc/full context needed) and mocked collaborators, asserting the request
 * attribute {@link PlanGateInterceptor} / {@link AnalyticsUsageCapInterceptor} depend on is
 * genuinely set (or genuinely left unset) by the real filter code.
 */
@ExtendWith(MockitoExtension.class)
class PlanGateFilterTest {

    @Mock private BrandContextService brandContextService;
    @Mock private SubscriptionService subscriptionService;
    @Mock private Workspace workspace;
    @Mock private Plan plan;
    @Mock private FilterChain chain;

    private PlanGateFilter filter;

    @BeforeEach
    void setUp() {
        filter = new PlanGateFilter(brandContextService, subscriptionService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("BRAND principal: resolves workspace + plan and publishes both as request attributes")
    void testBrandPrincipalResolvesPlanAttribute() throws Exception {
        AuthPrincipal principal = new AuthPrincipal("user-1", "brand@x.com", UserType.BRAND, "ws-1");
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        when(brandContextService.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(workspace.getId()).thenReturn("ws-1");
        when(subscriptionService.getActivePlanForWorkspace("ws-1")).thenReturn(plan);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/analytics/creators/c1/metrics");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, chain);

        assertSame(plan, request.getAttribute(PlanGateFilter.RESOLVED_PLAN_ATTR));
        assertSame("ws-1", request.getAttribute(PlanGateFilter.RESOLVED_WORKSPACE_ID_ATTR));
        verify(chain).doFilter(request, response);
    }

    @Test
    @DisplayName("CREATOR principal: never resolves a plan — attribute stays unset")
    void testCreatorPrincipalSkipsResolution() throws Exception {
        AuthPrincipal principal = new AuthPrincipal("user-2", "creator@x.com", UserType.CREATOR, null);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/creator/analytics/me/metrics");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, chain);

        assertNull(request.getAttribute(PlanGateFilter.RESOLVED_PLAN_ATTR));
        verify(brandContextService, never()).requireBrandWorkspace(principal);
        verify(chain).doFilter(request, response);
    }

    @Test
    @DisplayName("Unauthenticated request: no SecurityContext principal — attribute stays unset, request still proceeds")
    void testUnauthenticatedRequestSkipsResolution() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, chain);

        assertNull(request.getAttribute(PlanGateFilter.RESOLVED_PLAN_ATTR));
        verify(chain).doFilter(request, response);
    }

    @Test
    @DisplayName("Resolution failure (e.g. brand mid-onboarding, no workspace yet): swallowed, fails CLOSED (attribute unset), request still proceeds to downstream gates")
    void testWorkspaceResolutionFailureIsSwallowedNotThrown() throws Exception {
        AuthPrincipal principal = new AuthPrincipal("user-3", "brand2@x.com", UserType.BRAND, null);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        when(brandContextService.requireBrandWorkspace(principal))
                .thenThrow(new com.influora.common.ApiException(
                        "WORKSPACE_NOT_FOUND", "No workspace found for this user", org.springframework.http.HttpStatus.NOT_FOUND));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/analytics/creators/c1/metrics");
        MockHttpServletResponse response = new MockHttpServletResponse();

        // Must not throw out of the filter — an uncaught exception here would bypass
        // GlobalExceptionHandler entirely (this filter runs before Spring MVC dispatch).
        filter.doFilterInternal(request, response, chain);

        assertNull(request.getAttribute(PlanGateFilter.RESOLVED_PLAN_ATTR));
        verify(chain).doFilter(request, response);
    }
}
