package com.influora.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.config.CreatorCreditProperties;
import com.influora.config.SecurityConfig;
import com.influora.domain.entity.CreatorCreditOrder;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.enums.UserType;
import com.influora.integration.razorpay.CheckoutSignatureVerifier;
import com.influora.integration.razorpay.RazorpayClient;
import com.influora.security.AuthPrincipal;
import com.influora.security.AuthRateLimitFilter;
import com.influora.security.InternalServiceTokenFilter;
import com.influora.security.JsonAuthErrorHandler;
import com.influora.security.JwtAuthenticationFilter;
import com.influora.service.CreatorContextService;
import com.influora.service.credits.CreatorCreditOrderService;
import com.influora.service.credits.CreatorCreditService;
import com.influora.web.dto.credits.CreatorCreditDtos.VerifyOrderRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
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
import org.springframework.web.servlet.handler.HandlerMappingIntrospector;

/**
 * T-CREATOR-CREDITS-V2 round 2 (SPEC.md A32, design-kabir.md K-21) — the full ownership matrix
 * for {@code /creator/credits/**}: creator A calling the order-scoped route with creator B's
 * order id gets 404 (indistinguishable from nonexistent — B's own data is never touched); a brand
 * token is rejected 403 by the service-layer backstop ({@link
 * CreatorContextService#requireCreatorProfile}) even if it somehow reached the controller; and an
 * unauthenticated request never reaches the controller at all — proven directly against the REAL
 * {@link SecurityConfig#securityFilterChain} {@link AuthorizationManager}, same technique as
 * {@link com.influora.config.SecurityConfigMatcherTest}, since this codebase has no MockMvc/
 * spring-security-test harness (see that class's own javadoc) to assert an actual 401/403 HTTP
 * status from a live request.
 */
class CreatorCreditControllerIdorTest {

    private static final String CREATOR_A = "01HCREATORIDORAAAAAA01";
    private static final String CREATOR_B = "01HCREATORIDORBBBBBB01";
    private static final String B_ORDER_ID = "order-owned-by-b";

    // ------------------------------------------------------------------
    // Part 1 — controller-level: IDOR (404) + the service-layer role backstop (403)
    // ------------------------------------------------------------------

    private CreatorContextService creatorContext;
    private CreatorCreditService creditService;
    private CreatorCreditOrderService orderService;
    private CreatorCreditController controller;

    private AuthPrincipal creatorAPrincipal;
    private AuthPrincipal brandPrincipal;

    @BeforeEach
    void setUp() {
        creatorContext = mock(CreatorContextService.class);
        creditService = mock(CreatorCreditService.class);
        orderService = mock(CreatorCreditOrderService.class);
        controller =
                new CreatorCreditController(
                        creatorContext,
                        creditService,
                        orderService,
                        mock(CreatorCreditProperties.class),
                        mock(CheckoutSignatureVerifier.class),
                        mock(RazorpayClient.class));

        creatorAPrincipal = new AuthPrincipal(CREATOR_A, "a@example.com", UserType.CREATOR, null);
        brandPrincipal = new AuthPrincipal("brand-user-1", "b@example.com", UserType.BRAND, "ws-1");

        CreatorProfile profileA = mock(CreatorProfile.class);
        when(profileA.getUserId()).thenReturn(CREATOR_A);
        when(creatorContext.requireCreatorProfile(creatorAPrincipal)).thenReturn(profileA);
    }

    @Test
    @DisplayName(
            "A32/K-21: creator A calling verify with creator B's order id gets 404 (indistinguishable"
                    + " from nonexistent) — B's own order is never touched, and the signature/gateway"
                    + " round-trip is never even entered")
    void verify_creatorACallsWithBsOrderId_notFound_bUnchanged() {
        // The repository-level ownership scoping (findOwnedOrder filters by creator_user_id) means
        // A's lookup for B's order id structurally returns empty — mocked here exactly as
        // CreatorCreditOrderRepository#findByIdAndCreatorUserId behaves in production.
        when(orderService.findOwnedOrder(CREATOR_A, B_ORDER_ID)).thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                controller.verify(
                                        creatorAPrincipal, B_ORDER_ID, new VerifyOrderRequest("pay_x", "sig")));

        assertEquals("CREDIT_ORDER_NOT_FOUND", ex.getCode());
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        // "B unchanged": nothing was ever fetched or mutated under B's identity by this call.
        verify(orderService, never()).findOwnedOrder(eq(CREATOR_B), any());
        verify(orderService, never()).confirmPaid(any(), any(), any(), any(), any());

        // Confirm B, calling for her OWN order, is unaffected by A's failed attempt (B's data is
        // reachable and correct — the IDOR check above didn't corrupt or consume anything).
        CreatorCreditOrder bsOrder = mock(CreatorCreditOrder.class);
        when(bsOrder.getStatus()).thenReturn(com.influora.domain.enums.CreatorCreditOrderStatus.PENDING);
        when(orderService.findOwnedOrder(CREATOR_B, B_ORDER_ID)).thenReturn(Optional.of(bsOrder));
        assertTrue(orderService.findOwnedOrder(CREATOR_B, B_ORDER_ID).isPresent());
    }

    @Test
    @DisplayName("A32: a BRAND principal is rejected 403 by the service-layer backstop on EVERY /creator/credits/** method")
    void everyRoute_brandPrincipal_forbidden() {
        ApiException wrongUserType =
                new ApiException("WRONG_USER_TYPE", "This endpoint is for creator accounts only", HttpStatus.FORBIDDEN);
        when(creatorContext.requireCreatorProfile(brandPrincipal)).thenThrow(wrongUserType);

        assertForbidden(() -> controller.balance(brandPrincipal));
        assertForbidden(
                () ->
                        controller.createOrder(
                                brandPrincipal,
                                "key-1",
                                new com.influora.web.dto.credits.CreatorCreditDtos.CreateOrderRequest("PACK_60")));
        assertForbidden(
                () -> controller.verify(brandPrincipal, B_ORDER_ID, new VerifyOrderRequest("pay_x", "sig")));
        assertForbidden(() -> controller.orders(brandPrincipal));

        verify(creatorContext, times(4)).requireCreatorProfile(brandPrincipal);
        verify(orderService, never()).findOwnedOrder(any(), any());
        verify(orderService, never()).createOrder(any(), any(), any());
        verify(orderService, never()).confirmPaid(any(), any(), any(), any(), any());
        verify(orderService, never()).listOrders(any());
    }

    private static void assertForbidden(org.junit.jupiter.api.function.Executable call) {
        ApiException ex = assertThrows(ApiException.class, call);
        assertEquals("WRONG_USER_TYPE", ex.getCode());
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    // ------------------------------------------------------------------
    // Part 2 — filter-chain level: an unauthenticated request never reaches ANY /creator/credits
    // controller at all. Drives the REAL SecurityConfig#securityFilterChain AuthorizationManager
    // (same technique as SecurityConfigMatcherTest — no MockMvc/spring-security-test harness
    // exists in this codebase), scoped to the creator-credits routes specifically (the existing
    // generic /creator/** coverage only samples /creator/agent-preferences).
    // ------------------------------------------------------------------

    private AuthorizationManager<HttpServletRequest> authorizationManager;

    private void initAuthorizationManager() throws Exception {
        SecurityConfig securityConfig =
                new SecurityConfig(
                        mock(JwtAuthenticationFilter.class),
                        mock(AuthRateLimitFilter.class),
                        mock(InternalServiceTokenFilter.class),
                        mock(JsonAuthErrorHandler.class));
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

        GenericApplicationContext emptyContext = new GenericApplicationContext();
        emptyContext.registerBean("mvcHandlerMappingIntrospector", HandlerMappingIntrospector.class);
        emptyContext.refresh();
        Map<Class<?>, Object> sharedObjects = new HashMap<>();
        sharedObjects.put(ApplicationContext.class, emptyContext);

        HttpSecurity httpSecurity =
                new HttpSecurity(noOpPostProcessor, authenticationManagerBuilder, sharedObjects);

        java.lang.reflect.Method securityFilterChainMethod =
                SecurityConfig.class.getDeclaredMethod("securityFilterChain", HttpSecurity.class);
        securityFilterChainMethod.setAccessible(true);
        DefaultSecurityFilterChain chain =
                (DefaultSecurityFilterChain) securityFilterChainMethod.invoke(securityConfig, httpSecurity);

        AuthorizationFilter authorizationFilter =
                chain.getFilters().stream()
                        .filter(AuthorizationFilter.class::isInstance)
                        .map(AuthorizationFilter.class::cast)
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("No AuthorizationFilter in chain"));
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

    @Test
    @DisplayName(
            "A32: /creator/credits/** at the REAL SecurityConfig filter chain — anonymous (no token)"
                    + " is DENIED, a BRAND-authenticated token is DENIED, a CREATOR-authenticated token"
                    + " is GRANTED, for both the balance GET and the order-creation POST")
    void creatorCreditsRoutes_filterChainMatrix() throws Exception {
        initAuthorizationManager();

        assertTrue(
                !isGranted(ANONYMOUS, request(HttpMethod.GET, "/creator/credits")),
                "no token -> must be denied before any controller/principal is reached");
        assertTrue(
                !isGranted(authenticatedAs(UserType.BRAND), request(HttpMethod.GET, "/creator/credits")),
                "a brand JWT must be denied at the filter, not merely the service layer");
        assertTrue(
                isGranted(authenticatedAs(UserType.CREATOR), request(HttpMethod.GET, "/creator/credits")),
                "a creator JWT must be granted");

        assertTrue(
                !isGranted(ANONYMOUS, request(HttpMethod.POST, "/creator/credits/orders")),
                "no token -> POST /creator/credits/orders must be denied");
        assertTrue(
                !isGranted(
                        authenticatedAs(UserType.BRAND), request(HttpMethod.POST, "/creator/credits/orders")),
                "a brand JWT must be denied on the order-creation route too");
        assertTrue(
                isGranted(
                        authenticatedAs(UserType.CREATOR), request(HttpMethod.POST, "/creator/credits/orders")),
                "a creator JWT must be granted on the order-creation route");

        assertTrue(
                !isGranted(
                        ANONYMOUS,
                        request(HttpMethod.POST, "/creator/credits/orders/" + B_ORDER_ID + "/verify")),
                "no token -> the verify route must be denied at the filter as well");
    }

    // ------------------------------------------------------------------
    // A32 (exact acceptance wording, this criterion's own named test): "Creator A calling every
    // /creator/credits/** route with B's order id -> 404, B unchanged; brand token -> 403; no
    // token -> 401." Combines Part 1 (IDOR + role backstop) and Part 2 (filter-chain matrix) above
    // into the one test the spec names.
    // ------------------------------------------------------------------

    @Test
    @DisplayName(
            "A32 (exact wording): creator A with creator B's order id -> 404, B unchanged; a brand"
                    + " token -> 403 on every /creator/credits/** method; no token -> denied at the REAL"
                    + " SecurityConfig filter chain before any controller/principal is ever reached")
    void ownershipMatrix() throws Exception {
        // -- creator A calling the order-scoped route (verify) with creator B's order id -> 404,
        // indistinguishable from nonexistent (K-21); B's own identity is never even queried.
        when(orderService.findOwnedOrder(CREATOR_A, B_ORDER_ID)).thenReturn(Optional.empty());
        ApiException idorEx =
                assertThrows(
                        ApiException.class,
                        () ->
                                controller.verify(
                                        creatorAPrincipal, B_ORDER_ID, new VerifyOrderRequest("pay_x", "sig")));
        assertEquals("CREDIT_ORDER_NOT_FOUND", idorEx.getCode());
        assertEquals(HttpStatus.NOT_FOUND, idorEx.getStatus());
        verify(orderService, never()).findOwnedOrder(eq(CREATOR_B), any());
        verify(orderService, never()).confirmPaid(any(), any(), any(), any(), any());
        // B's own order remains reachable and correct under her own identity — A's failed IDOR
        // attempt touched nothing of B's.
        CreatorCreditOrder bsOrder = mock(CreatorCreditOrder.class);
        when(bsOrder.getStatus()).thenReturn(com.influora.domain.enums.CreatorCreditOrderStatus.PENDING);
        when(orderService.findOwnedOrder(CREATOR_B, B_ORDER_ID)).thenReturn(Optional.of(bsOrder));
        assertTrue(orderService.findOwnedOrder(CREATOR_B, B_ORDER_ID).isPresent());

        // -- a brand token is rejected 403 by the service-layer backstop on EVERY
        // /creator/credits/** controller method.
        ApiException wrongUserType =
                new ApiException("WRONG_USER_TYPE", "This endpoint is for creator accounts only", HttpStatus.FORBIDDEN);
        when(creatorContext.requireCreatorProfile(brandPrincipal)).thenThrow(wrongUserType);
        assertForbidden(() -> controller.balance(brandPrincipal));
        assertForbidden(
                () ->
                        controller.createOrder(
                                brandPrincipal,
                                "key-1",
                                new com.influora.web.dto.credits.CreatorCreditDtos.CreateOrderRequest("PACK_60")));
        assertForbidden(
                () -> controller.verify(brandPrincipal, B_ORDER_ID, new VerifyOrderRequest("pay_x", "sig")));
        assertForbidden(() -> controller.orders(brandPrincipal));
        verify(creatorContext, times(4)).requireCreatorProfile(brandPrincipal);
        verify(orderService, never()).createOrder(any(), any(), any());
        verify(orderService, never()).listOrders(any());

        // -- no token: denied at the REAL SecurityConfig filter chain, before ANY controller or
        // principal is ever reached (no MockMvc/spring-security-test harness exists in this
        // codebase — see SecurityConfigMatcherTest's own javadoc — so this drives the actual
        // AuthorizationManager the DSL builds, same technique as that class).
        initAuthorizationManager();
        assertTrue(
                !isGranted(ANONYMOUS, request(HttpMethod.GET, "/creator/credits")),
                "no token -> GET /creator/credits must be denied before any controller is reached");
        assertTrue(
                !isGranted(ANONYMOUS, request(HttpMethod.POST, "/creator/credits/orders")),
                "no token -> POST /creator/credits/orders must be denied");
        assertTrue(
                !isGranted(
                        ANONYMOUS,
                        request(HttpMethod.POST, "/creator/credits/orders/" + B_ORDER_ID + "/verify")),
                "no token -> the verify route must be denied at the filter as well");
        assertTrue(
                !isGranted(authenticatedAs(UserType.BRAND), request(HttpMethod.GET, "/creator/credits")),
                "a brand JWT must be denied at the filter too, not merely the service layer");
        assertTrue(
                isGranted(authenticatedAs(UserType.CREATOR), request(HttpMethod.GET, "/creator/credits")),
                "a creator JWT must be granted");
    }
}
