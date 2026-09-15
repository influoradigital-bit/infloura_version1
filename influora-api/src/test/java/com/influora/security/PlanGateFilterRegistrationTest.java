package com.influora.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.Filter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Proves {@link PlanGateFilter} is actually registered AND actually runs after authentication.
 *
 * <p><b>Why this test exists.</b> {@code PlanGateFilter}'s own class javadoc states it is
 * "registered in {@code SecurityConfig} via {@code addFilterAfter(planGateFilter,
 * JwtAuthenticationFilter.class)}". <b>That is not true.</b> {@code SecurityConfig} registers
 * exactly three filters — {@code rateLimitFilter}, {@code internalServiceTokenFilter} and {@code
 * jwtFilter} — and {@code PlanGateFilter} is not among them. It is instead picked up by Spring
 * Boot's servlet-filter auto-registration because it is a {@link Component} that is a {@link
 * Filter}, which places it in the main servlet chain rather than inside the Spring Security chain.
 *
 * <p>That distinction is not cosmetic. Everything downstream — {@link PlanGateInterceptor} (the
 * {@code @RequiresPlan} gate on report export and campaign templates) and {@link
 * AnalyticsUsageCapInterceptor} (the Free-tier 1-view/month analytics cap) — reads the {@link
 * com.influora.domain.entity.Plan} this filter publishes as a request attribute. If this filter
 * ever runs BEFORE the security chain populates {@code SecurityContextHolder}, the attribute is
 * never set and the two gates fail in OPPOSITE directions:
 *
 * <ul>
 *   <li>{@code PlanGateInterceptor} fails CLOSED — {@code PLAN_NOT_RESOLVED} 403 on export and
 *       campaign templates for <em>every</em> brand, including paying Pro subscribers.
 *   <li>{@code AnalyticsUsageCapInterceptor} fails OPEN — it deliberately steps aside when no plan
 *       is resolved, so every Free workspace silently gets unlimited creator deep-dive analytics,
 *       which is one of the four things a Pro subscription actually sells.
 * </ul>
 *
 * <p><b>Why this shape, and not {@code @SpringBootTest} + MockMvc.</b> A real-context test would be
 * the stronger proof, and it cannot run here: as {@code ConfigurationPropertiesRegistrationTest}
 * already documents, every {@code @SpringBootTest} class in this module errors out on
 * Testcontainers/Docker discovery before the context boots. A gate that cannot execute is not a
 * gate. This test therefore asserts the same invariant through plain reflection with NO Spring
 * context, so it runs green or red on a machine with no Docker — same discipline, same reason.
 *
 * <p><b>Falsification (run these before trusting a green result):</b>
 *
 * <ol>
 *   <li>Delete {@code @Component} from {@code PlanGateFilter} → {@link
 *       #planGateFilterIsRegisteredAsAServletFilter()} must go RED.
 *   <li>Add {@code @Order(-200)} to {@code PlanGateFilter} → {@link
 *       #planGateFilterRunsAfterTheSecurityFilterChain()} must go RED.
 * </ol>
 */
class PlanGateFilterRegistrationTest {

    /**
     * The order Spring Boot assigns a {@link Filter} bean that declares no {@link Order} and does
     * not implement {@link Ordered} — see {@code ServletContextInitializerBeans}, which defaults an
     * unordered filter bean to the lowest precedence in the servlet chain.
     */
    private static final int SPRING_BOOT_DEFAULT_FILTER_ORDER = Ordered.LOWEST_PRECEDENCE;

    @Test
    @DisplayName("PlanGateFilter is registered as a servlet Filter bean (its javadoc's SecurityConfig claim is false)")
    void planGateFilterIsRegisteredAsAServletFilter() {
        assertThat(Filter.class.isAssignableFrom(PlanGateFilter.class))
                .as(
                        "PlanGateFilter must be a servlet Filter for Spring Boot to auto-register it."
                                + " If this ever stops being true, the @RequiresPlan gate and the"
                                + " Free-tier analytics cap both lose the request attribute they"
                                + " depend on.")
                .isTrue();

        assertThat(AnnotatedElementUtils.hasAnnotation(PlanGateFilter.class, Component.class))
                .as(
                        "PlanGateFilter carries no stereotype, so nothing registers it: SecurityConfig"
                                + " does NOT register it (contrary to its own class javadoc — that"
                                + " file registers only rateLimitFilter, internalServiceTokenFilter"
                                + " and jwtFilter), and there is no FilterRegistrationBean for it"
                                + " either. Unregistered, RESOLVED_PLAN_ATTR is never set on any"
                                + " request: report export and campaign templates 403"
                                + " PLAN_NOT_RESOLVED for every brand including Pro, and the"
                                + " Free-tier creator-analytics cap silently stops applying"
                                + " altogether.")
                .isTrue();
    }

    @Test
    @DisplayName("PlanGateFilter runs AFTER the Spring Security chain, so the principal is populated when it reads it")
    void planGateFilterRunsAfterTheSecurityFilterChain() {
        int planGateOrder = effectiveFilterOrder(PlanGateFilter.class);

        assertThat(planGateOrder)
                .as(
                        "PlanGateFilter resolves the caller's Plan from the AuthPrincipal on"
                                + " SecurityContextHolder, which only the Spring Security chain"
                                + " (registered at SecurityProperties.DEFAULT_FILTER_ORDER = %d)"
                                + " populates. A servlet filter ordered at or before that value runs"
                                + " with an empty SecurityContext, silently publishes no plan"
                                + " attribute, and breaks both downstream gates in opposite"
                                + " directions — @RequiresPlan fails CLOSED (403 for everyone,"
                                + " including paying Pro brands), the analytics cap fails OPEN"
                                + " (unlimited deep-dives on Free). The failure is invisible to"
                                + " every existing test, because PlanGateWiringTest constructs the"
                                + " filter and interceptor by hand with `new` and never exercises"
                                + " registration or ordering at all.",
                        SecurityProperties.DEFAULT_FILTER_ORDER)
                .isGreaterThan(SecurityProperties.DEFAULT_FILTER_ORDER);
    }

    /**
     * The order Spring Boot will place {@code filterClass} at in the servlet chain: its {@link
     * Order} value if it declares one, else Spring Boot's unordered-filter default.
     *
     * <p>Uses Spring's merged-annotation lookup rather than {@code Class#getAnnotation} so an
     * {@code @Order} inherited through a meta-annotation is still seen — the same trap {@code
     * ConfigurationPropertiesRegistrationTest} documents for {@code @Component}.
     */
    private static int effectiveFilterOrder(Class<?> filterClass) {
        Order declared = AnnotatedElementUtils.findMergedAnnotation(filterClass, Order.class);
        if (declared != null) {
            return declared.value();
        }
        if (Ordered.class.isAssignableFrom(filterClass)) {
            // OncePerRequestFilter does not implement Ordered, but a future subclass might; fail
            // loudly rather than silently reporting the default for a filter that self-orders.
            throw new AssertionError(
                    filterClass.getSimpleName()
                            + " implements Ordered, so its order is decided at runtime by"
                            + " getOrder() and cannot be read reflectively here. Re-derive this"
                            + " test against the instance before trusting it.");
        }
        return SPRING_BOOT_DEFAULT_FILTER_ORDER;
    }
}
