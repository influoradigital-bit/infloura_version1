package com.influora.config;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.influora.domain.enums.UserType;
import com.influora.security.AuthPrincipal;
import com.influora.security.AuthRateLimitFilter;
import com.influora.security.InternalServiceTokenFilter;
import com.influora.security.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.handler.HandlerMappingIntrospector;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.ObjectPostProcessor;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * [SEC: Wave-1 S2/S3/S8] Proves the REAL {@link SecurityConfig#securityFilterChain(HttpSecurity)}
 * bean — not a reimplementation of its matcher strings — actually grants/denies the way the
 * Wave-1 fix intends, by extracting the real {@link AuthorizationManager} the DSL builds and
 * calling it directly with representative requests. This codebase has no MockMvc/spring-security-
 * test harness (see {@code AuthControllerTest}, {@code PlanGateWiringTest} javadocs) and the only
 * full-context integration base ({@code AbstractIntegrationTest}) needs a Docker-backed MySQL this
 * sandbox cannot reach — so, same "drive the real production object one layer at a time" style as
 * {@code PlanGateWiringTest}, {@link HttpSecurity} is hand-constructed (its constructor is public;
 * {@code HttpSecurityConfiguration}'s usual preset defaults — anonymous/session/exception-handling
 * configurers — are irrelevant here because we call the resulting {@link AuthorizationManager}
 * directly instead of running the full filter chain, so their absence doesn't affect what's being
 * proven). The three collaborator filters are mocked — they're only ever added to the chain, never
 * invoked by this test.
 */
class SecurityConfigMatcherTest {

    private AuthorizationManager<HttpServletRequest> authorizationManager;

    @BeforeEach
    void setUp() throws Exception {
        SecurityConfig securityConfig =
                new SecurityConfig(
                        mock(JwtAuthenticationFilter.class),
                        mock(AuthRateLimitFilter.class),
                        mock(InternalServiceTokenFilter.class));
        setField(
                securityConfig,
                "contentSecurityPolicy",
                "default-src 'none'; frame-ancestors 'none'; base-uri 'none'");

        ObjectPostProcessor<Object> noOpPostProcessor =
                new ObjectPostProcessor<>() {
                    @Override
                    public <O> O postProcess(O object) {
                        return object;
                    }
                };
        AuthenticationManagerBuilder authenticationManagerBuilder =
                new AuthenticationManagerBuilder(noOpPostProcessor);

        // AuthorizeHttpRequestsConfigurer unconditionally probes the shared ApplicationContext for
        // a HandlerMappingIntrospector bean (to decide AntPathRequestMatcher vs
        // PathPatternRequestMatcher) -- a bare empty, refreshed context satisfies that probe
        // (finds none, falls back to AntPathRequestMatcher) without needing the real application's
        // full bean graph (which would need a live DB -- unavailable in this sandbox).
        GenericApplicationContext emptyContext = new GenericApplicationContext();
        // CorsConfigurer's default (empty) customizer builds an MvcCorsFilter, which requires this
        // exact bean name to resolve path patterns -- register a real HandlerMappingIntrospector so
        // context.getBean("mvcHandlerMappingIntrospector", ...) resolves without needing the app's
        // full Spring MVC bean graph.
        emptyContext.registerBean(
                "mvcHandlerMappingIntrospector", HandlerMappingIntrospector.class);
        emptyContext.refresh();
        Map<Class<?>, Object> sharedObjects = new HashMap<>();
        sharedObjects.put(ApplicationContext.class, emptyContext);

        HttpSecurity httpSecurity =
                new HttpSecurity(noOpPostProcessor, authenticationManagerBuilder, sharedObjects);

        DefaultSecurityFilterChain chain =
                (DefaultSecurityFilterChain) securityConfig.securityFilterChain(httpSecurity);

        AuthorizationFilter authorizationFilter =
                chain.getFilters().stream()
                        .filter(AuthorizationFilter.class::isInstance)
                        .map(AuthorizationFilter.class::cast)
                        .findFirst()
                        .orElseThrow(
                                () -> new IllegalStateException("No AuthorizationFilter in chain"));
        authorizationManager = authorizationFilter.getAuthorizationManager();
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static MockHttpServletRequest request(HttpMethod method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method.name(), path);
        request.setServletPath(path);
        return request;
    }

    private static final Supplier<Authentication> ANONYMOUS =
            () ->
                    new AnonymousAuthenticationToken(
                            "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

    private static Supplier<Authentication> authenticatedAs(UserType userType) {
        AuthPrincipal principal = new AuthPrincipal("user-1", "u@x.com", userType, "ws-1");
        return () -> new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    private boolean isGranted(Supplier<Authentication> authentication, HttpServletRequest request) {
        AuthorizationDecision decision = authorizationManager.check(authentication, request);
        return decision != null && decision.isGranted();
    }

    // ---- S2: commerce/tracking webhooks + JWKS permitAll ----------------------------------

    @Test
    @DisplayName("S2: anonymous POST /webhooks/shopify is permitted")
    void shopifyWebhookPermittedAnonymous() {
        assertTrue(isGranted(ANONYMOUS, request(HttpMethod.POST, "/webhooks/shopify")));
    }

    @Test
    @DisplayName("S2: anonymous POST /webhooks/woocommerce is permitted")
    void wooCommerceWebhookPermittedAnonymous() {
        assertTrue(isGranted(ANONYMOUS, request(HttpMethod.POST, "/webhooks/woocommerce")));
    }

    @Test
    @DisplayName("S2: anonymous POST /webhooks/conversion is permitted")
    void conversionWebhookPermittedAnonymous() {
        assertTrue(isGranted(ANONYMOUS, request(HttpMethod.POST, "/webhooks/conversion")));
    }

    @Test
    @DisplayName("S2: anonymous POST /webhooks/redemption is permitted")
    void redemptionWebhookPermittedAnonymous() {
        assertTrue(isGranted(ANONYMOUS, request(HttpMethod.POST, "/webhooks/redemption")));
    }

    @Test
    @DisplayName("S2: anonymous GET /.well-known/jwks.json is permitted")
    void jwksPermittedAnonymous() {
        assertTrue(isGranted(ANONYMOUS, request(HttpMethod.GET, "/.well-known/jwks.json")));
    }

    @Test
    @DisplayName("S2: anonymous GET /track/click/{id} is permitted")
    void trackClickPermittedAnonymous() {
        assertTrue(isGranted(ANONYMOUS, request(HttpMethod.GET, "/track/click/abc123")));
    }

    // ---- S3: admin login/refresh permitAll -------------------------------------------------

    @Test
    @DisplayName("S3: anonymous POST /admin/auth/login is permitted (admin can obtain a token)")
    void adminLoginPermittedAnonymous() {
        assertTrue(isGranted(ANONYMOUS, request(HttpMethod.POST, "/admin/auth/login")));
    }

    @Test
    @DisplayName("S3: anonymous POST /admin/auth/refresh is permitted")
    void adminRefreshPermittedAnonymous() {
        assertTrue(isGranted(ANONYMOUS, request(HttpMethod.POST, "/admin/auth/refresh")));
    }

    // ---- S8: /admin/** defense-in-depth hasRole(ADMIN) -------------------------------------

    @Test
    @DisplayName("S8: anonymous GET /admin/dashboard is DENIED")
    void adminDashboardDeniedAnonymous() {
        assertTrue(!isGranted(ANONYMOUS, request(HttpMethod.GET, "/admin/dashboard")));
    }

    @Test
    @DisplayName("S8: BRAND-authenticated GET /admin/dashboard is DENIED (non-admin JWT blocked at the filter)")
    void adminDashboardDeniedForBrand() {
        assertTrue(!isGranted(authenticatedAs(UserType.BRAND), request(HttpMethod.GET, "/admin/dashboard")));
    }

    @Test
    @DisplayName("S8: CREATOR-authenticated GET /admin/dashboard is DENIED (non-admin JWT blocked at the filter)")
    void adminDashboardDeniedForCreator() {
        assertTrue(
                !isGranted(authenticatedAs(UserType.CREATOR), request(HttpMethod.GET, "/admin/dashboard")));
    }

    @Test
    @DisplayName("S8: ADMIN-authenticated GET /admin/dashboard is permitted (ROLE_ADMIN matches hasRole(\"ADMIN\"))")
    void adminDashboardPermittedForAdmin() {
        assertTrue(isGranted(authenticatedAs(UserType.ADMIN), request(HttpMethod.GET, "/admin/dashboard")));
    }

    @Test
    @DisplayName("S3 ordering: BRAND-authenticated POST /admin/auth/login still succeeds (permitAll wins before the S8 hasRole matcher)")
    void adminLoginStillPermittedEvenWithNonAdminAuth() {
        // Ordering proof: a BRAND/CREATOR JWT presented on THIS specific path must still pass,
        // because the S3 permit is registered before the S8 catch-all -- first match wins.
        assertTrue(isGranted(authenticatedAs(UserType.BRAND), request(HttpMethod.POST, "/admin/auth/login")));
    }

    @Test
    @DisplayName("S8: BRAND-authenticated POST /admin/auth/logout is DENIED (only login+refresh are permitAll; everything else under /admin/auth/** needs ROLE_ADMIN)")
    void adminAuthLogoutDeniedForBrand() {
        assertTrue(
                !isGranted(authenticatedAs(UserType.BRAND), request(HttpMethod.POST, "/admin/auth/logout")));
    }

    // ---- Public creator portfolio permitAll (GET view + POST contact) ----------------------

    @Test
    @DisplayName("Portfolio: anonymous GET /portfolio/{username} is permitted (shareable public page)")
    void publicPortfolioGetPermittedAnonymous() {
        assertTrue(isGranted(ANONYMOUS, request(HttpMethod.GET, "/portfolio/priyacreates")));
    }

    @Test
    @DisplayName("Portfolio: anonymous POST /portfolio/{username}/contact is permitted (brand contact form)")
    void publicPortfolioContactPermittedAnonymous() {
        assertTrue(isGranted(ANONYMOUS, request(HttpMethod.POST, "/portfolio/priyacreates/contact")));
    }

    @Test
    @DisplayName("Portfolio narrowness: anonymous GET /me/portfolio is DENIED (single-'*' permit cannot leak the creator-only route)")
    void myPortfolioGetStillDeniedAnonymous() {
        assertTrue(!isGranted(ANONYMOUS, request(HttpMethod.GET, "/me/portfolio")));
    }

    @Test
    @DisplayName("Portfolio narrowness: anonymous GET /me/portfolio/analytics is DENIED (creator-only analytics stays authenticated)")
    void myPortfolioAnalyticsStillDeniedAnonymous() {
        assertTrue(!isGranted(ANONYMOUS, request(HttpMethod.GET, "/me/portfolio/analytics")));
    }

    @Test
    @DisplayName("Portfolio method-scoping: anonymous PATCH /portfolio/{username} is DENIED (only GET is opened, not writes)")
    void publicPortfolioPatchDeniedAnonymous() {
        assertTrue(!isGranted(ANONYMOUS, request(HttpMethod.PATCH, "/portfolio/priyacreates")));
    }

    // ---- Pre-existing behavior must be unchanged -------------------------------------------

    @Test
    @DisplayName("Unrelated: anonymous POST /webhooks/razorpay stays permitted (pre-existing rule untouched)")
    void razorpayWebhookStillPermittedAnonymous() {
        assertTrue(isGranted(ANONYMOUS, request(HttpMethod.POST, "/webhooks/razorpay")));
    }

    @Test
    @DisplayName("Unrelated: anonymous GET /campaigns (a generic protected route) is still DENIED")
    void genericProtectedRouteStillDeniedAnonymous() {
        assertTrue(!isGranted(ANONYMOUS, request(HttpMethod.GET, "/campaigns")));
    }
}
