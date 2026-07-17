package com.influora.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.Plan;
import com.influora.domain.enums.PlanFeature;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

/**
 * Task 22 — real object test of {@link PlanGateInterceptor#preHandle}. No {@code @RequiresPlan}
 * endpoint exists yet in this codebase (export/campaign-template write endpoints aren't built),
 * so this test builds a throwaway annotated handler method the same way Spring MVC would resolve
 * a real one, and drives the real interceptor against it — proving the mechanism itself is
 * correctly wired for whichever future endpoint adds the annotation.
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
