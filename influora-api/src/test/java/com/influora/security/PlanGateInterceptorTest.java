package com.influora.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.Plan;
import com.influora.domain.enums.PlanFeature;
import com.influora.web.ReportExportController;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

/**
 * Task 22 — real object test of {@link PlanGateInterceptor#preHandle}.
 *
 * <p><b>Both {@link com.influora.domain.enums.PlanFeature} constants are enforced on real
 * endpoints.</b> {@code EXPORT} gates {@code ReportExportController#export} ({@code GET
 * /campaigns/&#123;campaignId&#125;/export}), which carries {@code @RequiresPlan(feature =
 * PlanFeature.EXPORT)}; {@code CAMPAIGN_TEMPLATES} gates {@code
 * CampaignTemplateController#saveAsTemplate} (BR-14) — see {@code CampaignTemplateControllerTest}
 * for that one's 402 case. Nothing about the {@code EXPORT} branch is dead code.
 *
 * <p>The {@code FakeExportController} below still exists to cover the handler shapes a real
 * controller cannot express (an UNannotated method, and a gated method reached with no plan
 * resolved upstream). The {@code realExportEndpoint*} tests below resolve the ACTUAL {@code
 * ReportExportController#export} method the way Spring MVC would, so deleting that annotation —
 * or the {@code case EXPORT} branch of the interceptor's switch — turns this class RED instead of
 * silently un-gating export for every Free workspace.
 */
class PlanGateInterceptorTest {

    private final PlanGateInterceptor interceptor = new PlanGateInterceptor();

    static class FakeExportController {
        @RequiresPlan(feature = PlanFeature.EXPORT)
        public void exportReport() {}

        public void unannotatedMethod() {}
    }

    private HandlerMethod handlerMethodFor(String methodName) throws NoSuchMethodException {
        Method method = FakeExportController.class.getMethod(methodName);
        return new HandlerMethod(new FakeExportController(), method);
    }

    @Test
    @DisplayName("No @RequiresPlan on the handler: no-op, request proceeds without touching the request attribute")
    void testUnannotatedMethodIsNoop() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        boolean result =
                interceptor.preHandle(request, new MockHttpServletResponse(), handlerMethodFor("unannotatedMethod"));
        assertTrue(result);
    }

    @Test
    @DisplayName("@RequiresPlan(EXPORT), Free plan (exportEnabled=false): genuinely throws 402 UPGRADE_REQUIRED")
    void testExportGatedRejectsFreePlan() throws Exception {
        Plan freePlan = mock(Plan.class);
        when(freePlan.isExportEnabled()).thenReturn(false);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(PlanGateFilter.RESOLVED_PLAN_ATTR, freePlan);

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> interceptor.preHandle(request, new MockHttpServletResponse(), handlerMethodFor("exportReport")));

        assertEquals(HttpStatus.PAYMENT_REQUIRED, ex.getStatus());
        assertEquals("UPGRADE_REQUIRED", ex.getCode());
    }

    @Test
    @DisplayName("@RequiresPlan(EXPORT), Pro plan (exportEnabled=true): genuinely allowed")
    void testExportGatedAllowsProPlan() throws Exception {
        Plan proPlan = mock(Plan.class);
        when(proPlan.isExportEnabled()).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(PlanGateFilter.RESOLVED_PLAN_ATTR, proPlan);

        boolean result =
                interceptor.preHandle(request, new MockHttpServletResponse(), handlerMethodFor("exportReport"));

        assertTrue(result);
    }

    /**
     * The REAL production handler method, resolved exactly as Spring MVC resolves it. No fake
     * controller: if {@code @RequiresPlan(feature = PlanFeature.EXPORT)} is ever removed from
     * {@code ReportExportController#export}, {@code getMethodAnnotation} returns null and the
     * interceptor no-ops, failing the two tests below.
     */
    private HandlerMethod realExportEndpoint() throws NoSuchMethodException {
        Method method =
                ReportExportController.class.getMethod(
                        "export", AuthPrincipal.class, String.class, String.class);
        return new HandlerMethod(new ReportExportController(null), method);
    }

    @Test
    @DisplayName("REAL ReportExportController#export, Free plan: genuinely 402 UPGRADE_REQUIRED — export IS gated, the EXPORT branch is not dead code")
    void testRealExportEndpointRejectsFreePlan() throws Exception {
        Plan freePlan = mock(Plan.class);
        when(freePlan.isExportEnabled()).thenReturn(false);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/campaigns/c1/export");
        request.setAttribute(PlanGateFilter.RESOLVED_PLAN_ATTR, freePlan);

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> interceptor.preHandle(request, new MockHttpServletResponse(), realExportEndpoint()));

        assertEquals(HttpStatus.PAYMENT_REQUIRED, ex.getStatus());
        assertEquals("UPGRADE_REQUIRED", ex.getCode());
        verify(freePlan).isExportEnabled();
    }

    @Test
    @DisplayName("REAL ReportExportController#export, Pro plan: genuinely allowed, and the decision genuinely came from Plan.isExportEnabled()")
    void testRealExportEndpointAllowsProPlan() throws Exception {
        Plan proPlan = mock(Plan.class);
        when(proPlan.isExportEnabled()).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/campaigns/c1/export");
        request.setAttribute(PlanGateFilter.RESOLVED_PLAN_ATTR, proPlan);

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), realExportEndpoint()));
        verify(proPlan).isExportEnabled();
    }

    @Test
    @DisplayName("@RequiresPlan present but no Plan resolved upstream (non-brand/unresolved workspace): fails closed with 403")
    void testGatedMethodWithNoResolvedPlanFailsClosed() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(); // no attribute set

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> interceptor.preHandle(request, new MockHttpServletResponse(), handlerMethodFor("exportReport")));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        assertEquals("PLAN_NOT_RESOLVED", ex.getCode());
    }
}
