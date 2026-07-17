package com.influora.config;

import com.influora.security.AuthRateLimitFilter;
import com.influora.security.InternalServiceTokenFilter;
import com.influora.security.JwtAuthenticationFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;
    private final AuthRateLimitFilter rateLimitFilter;
    private final InternalServiceTokenFilter internalServiceTokenFilter;

    /**
     * Content-Security-Policy served with API responses. The primary CSP belongs on the SPA's own
     * host (it serves the HTML/JS); this API returns JSON, so a locked-down policy here mainly hardens
     * any error/HTML surface. Kabir audit B4 explicitly asked for CSP + frame-ancestors, and a strong
     * CSP is the best secondary mitigation for the A1 localStorage-token XSS risk.
     */
    @Value("${influora.security.content-security-policy:default-src 'none'; frame-ancestors 'none'; base-uri 'none'}")
    private String contentSecurityPolicy;

    public SecurityConfig(
            JwtAuthenticationFilter jwtFilter,
            AuthRateLimitFilter rateLimitFilter,
            InternalServiceTokenFilter internalServiceTokenFilter) {
        this.jwtFilter = jwtFilter;
        this.rateLimitFilter = rateLimitFilter;
        this.internalServiceTokenFilter = internalServiceTokenFilter;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // CSRF stays disabled by design: authorization is the Bearer header (browsers never attach it
        // cross-site) and the only cookie — the refresh token — is HttpOnly + SameSite=Strict and
        // path-scoped to /auth (see AuthCookieService), so there is no ambient session cookie to forge
        // against. Do NOT move authorization to a cookie without also enabling CSRF protection here.
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> {})
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(
                        headers ->
                                headers
                                        .httpStrictTransportSecurity(
                                                hsts ->
                                                        hsts.includeSubDomains(true)
                                                                .maxAgeInSeconds(31_536_000))
                                        .frameOptions(frame -> frame.deny())
                                        .referrerPolicy(
                                                ref ->
                                                        ref.policy(
                                                                ReferrerPolicyHeaderWriter.ReferrerPolicy
                                                                        .NO_REFERRER))
                                        .contentSecurityPolicy(
                                                csp -> csp.policyDirectives(contentSecurityPolicy)))
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers("/health")
                                        .permitAll()
                                        .requestMatchers(HttpMethod.POST, "/auth/**")
                                        .permitAll()
                                        .requestMatchers(HttpMethod.POST, "/auth/brand/send-email-otp")
                                        .permitAll()
                                        .requestMatchers(HttpMethod.POST, "/auth/brand/verify-email")
                                        .permitAll()
                                        .requestMatchers(HttpMethod.GET, "/auth/verify-email")
                                        .permitAll()
                                        .requestMatchers(HttpMethod.GET, "/workspaces/slug-check")
                                        .permitAll()
                                        // Razorpay webhook: trust boundary is HMAC signature verification
                                        // (WebhookSignatureVerifier), not a Bearer JWT — Razorpay is not a
                                        // logged-in user. See RazorpayWebhookController.
                                        .requestMatchers(HttpMethod.POST, "/webhooks/razorpay")
                                        .permitAll()
                                        // Wave-1 S2 (Kabir C-8 port) — every other commerce/tracking
                                        // webhook has the SAME non-JWT trust boundary as
                                        // /webhooks/razorpay above: a per-delivery HMAC signature
                                        // verified inside each controller BEFORE any parsing/dispatch
                                        // (Shopify X-Shopify-Hmac-Sha256, WooCommerce
                                        // X-WC-Webhook-Signature, conversion/redemption per-workspace
                                        // HMAC). The sender is a merchant platform webhook caller, never
                                        // a logged-in user, so these were unreachable (fell through to
                                        // anyRequest().authenticated()) until this fix. See
                                        // ShopifyWebhookController, WooCommerceWebhookController,
                                        // ConversionWebhookController.
                                        .requestMatchers(HttpMethod.POST, "/webhooks/shopify")
                                        .permitAll()
                                        .requestMatchers(HttpMethod.POST, "/webhooks/woocommerce")
                                        .permitAll()
                                        .requestMatchers(HttpMethod.POST, "/webhooks/conversion")
                                        .permitAll()
                                        .requestMatchers(HttpMethod.POST, "/webhooks/redemption")
                                        .permitAll()
                                        // JWKS discovery — serves ONLY public key material (public by
                                        // design, RFC 7517-adjacent convention). See JwksController.
                                        .requestMatchers(HttpMethod.GET, "/.well-known/jwks.json")
                                        .permitAll()
                                        // Tracking-link click redirect — hit by an anonymous visitor's
                                        // browser following a creator's posted link; a visitor cannot
                                        // sign a request. Increments a click counter and 302-redirects.
                                        // See ConversionWebhookController#trackClick.
                                        .requestMatchers(HttpMethod.GET, "/track/click/**")
                                        .permitAll()
                                        // Wave-1 S3 — AdminAuthController is @RequestMapping("/admin/auth")
                                        // and was unreachable unauthenticated (fell through to
                                        // anyRequest().authenticated()), so an admin could never obtain a
                                        // token in the first place. Only login+refresh are permitted;
                                        // /admin/auth/logout, /me, /mfa/** all stay behind the S8
                                        // hasRole(ADMIN) matcher below (they require an already-issued
                                        // admin JWT).
                                        .requestMatchers(HttpMethod.POST, "/admin/auth/login")
                                        .permitAll()
                                        .requestMatchers(HttpMethod.POST, "/admin/auth/refresh")
                                        .permitAll()
                                        // Wave-1 S8 — defense-in-depth: block a non-admin (BRAND/CREATOR)
                                        // JWT from reaching ANY /admin/** route at the filter, not just
                                        // at the service layer. AuthPrincipal#getAuthorities() grants
                                        // "ROLE_" + userType.name(), so an admin access token carries
                                        // ROLE_ADMIN (see AuthPrincipal, JwtAuthenticationFilter);
                                        // hasRole("ADMIN") matches that authority. MUST be ordered after
                                        // the S3 permits above — Spring evaluates requestMatchers in
                                        // order, first match wins — or login/refresh would themselves be
                                        // blocked before a token even exists.
                                        .requestMatchers("/admin/**")
                                        .hasRole("ADMIN")
                                        .anyRequest()
                                        .authenticated())
                .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                // Dual-credential internal gate runs BEFORE the public JWT filter so a request to
                // /internal/** is authorized (or rejected) by the mesh contract, never by a stray
                // Authorization: Bearer header falling through to the human-JWT path.
                .addFilterBefore(internalServiceTokenFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
