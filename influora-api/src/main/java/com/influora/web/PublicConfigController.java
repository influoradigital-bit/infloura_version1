package com.influora.web;

import com.influora.common.ApiResponse;
import com.influora.config.RazorpayProperties;
import com.influora.config.TrendFeatureGate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Non-secret, environment-scoped config the SPA needs to construct client-side SDK calls. Never
 * put anything here that a browser shouldn't hold — no response in this controller carries an
 * authorization boundary of its own.
 *
 * <p>Two exposure levels: {@code /config/razorpay} requires a normal authenticated session, while
 * {@code /config/public} is {@code permitAll} in {@code SecurityConfig} because the signup pages
 * read it before any account exists. Anything added here must be classified into one or the other
 * deliberately — defaulting a new field into {@code /config/public} makes it world-readable.
 *
 * <p>[SEC: MF-1 / no-secret-in-frontend] {@link RazorpayProperties#getKeyId()} is Razorpay's
 * public "Key ID" (their own docs: safe to embed in client-side checkout code — it identifies the
 * merchant, it does not authorize a charge). {@code keySecret} is NEVER exposed here or anywhere
 * else outside {@link com.influora.integration.razorpay.RazorpayClient} /
 * {@link com.influora.integration.razorpay.WebhookSignatureVerifier}, which call it server-side
 * only.
 */
@RestController
@RequestMapping("/config")
public class PublicConfigController {

    private final RazorpayProperties razorpayProperties;

    /**
     * T-TSOFF-0920 — source of {@code trendsEnabled}. Reading the gate (rather than a second
     * boolean of its own) is what makes the SPA and the endpoints agree by construction: the
     * value published here is literally the same predicate {@link TrendFeatureGate#requireEnabled()}
     * refuses on.
     */
    private final TrendFeatureGate trendFeatureGate;

    /**
     * Mirrors {@link com.influora.service.AuthService}'s flag of the same name. Exposed so the
     * unauthenticated register pages can decide whether to render an OTP step — see {@link
     * #getPublicConfig()}.
     */
    @Value("${influora.auth.require-email-otp-before-register:false}")
    private boolean requireEmailOtpBeforeRegister;

    public PublicConfigController(
            RazorpayProperties razorpayProperties, TrendFeatureGate trendFeatureGate) {
        this.razorpayProperties = razorpayProperties;
        this.trendFeatureGate = trendFeatureGate;
    }

    /**
     * GET /config/public — the only {@code permitAll} entry in this controller, because it is read
     * by the signup pages BEFORE any account (and therefore any token) exists.
     *
     * <p>{@code requireEmailOtp} is the client-side mirror of {@code
     * influora.auth.require-email-otp-before-register}, which {@code AuthService.brandRegister} /
     * {@code creatorRegister} enforce server-side. Without this the SPA had no way to know the gate
     * was on: flipping the flag would have made every registration 400 with {@code
     * EMAIL_NOT_VERIFIED} and no UI able to satisfy it. The flag is not a secret — it describes a
     * rule the server enforces regardless of what the client believes, and the client is free to
     * send an unverified registration and be rejected.
     *
     * <p>{@code trendsEnabled} (T-TSOFF-0920) is classified into {@code /public} deliberately, per
     * this class's header rule. It is the same class of value as {@code requireEmailOtp}: it
     * describes which surfaces the server will serve, it is enforced server-side by {@link
     * TrendFeatureGate} regardless of what the client believes, and a browser learning "trend
     * suggestions are off" leaks nothing — it is the honest answer we want every visitor to have.
     * It is NOT authenticated-only because the surfaces it gates (and the marketing claims that
     * must stay consistent with it) are reachable before login.
     */
    @GetMapping("/public")
    public ApiResponse<PublicConfigResponse> getPublicConfig() {
        return ApiResponse.ok(
                new PublicConfigResponse(requireEmailOtpBeforeRegister, trendFeatureGate.isEnabled()));
    }

    /**
     * GET /config/razorpay — the Razorpay checkout launcher's only source for the {@code key}
     * param passed to {@code window.Razorpay({key: ..., order_id: ...}).open()}. Standard
     * authenticated-JWT endpoint (no {@code permitAll} added to {@code SecurityConfig}) — any
     * logged-in brand or creator session can read it, matching the fact that this value is not
     * secret, only environment-specific.
     */
    @GetMapping("/razorpay")
    public ApiResponse<RazorpayConfigResponse> getRazorpayConfig() {
        return ApiResponse.ok(new RazorpayConfigResponse(razorpayProperties.getKeyId()));
    }

    public record RazorpayConfigResponse(String keyId) {}

    /**
     * @param requireEmailOtp see {@link #getPublicConfig()}.
     * @param trendsEnabled T-TSOFF-0920 — whether trend-derived surfaces (the brand Trend-Spark
     *     nudge and the creator Co-pilot daily idea) can produce anything at all. False means the
     *     SPA hides those surfaces outright rather than rendering an empty or "check back
     *     tomorrow" state for a feature with no tomorrow, and the matching endpoints answer
     *     {@code 404 TRENDS_DISABLED}. Turning the feature back on is a server config change
     *     ({@code TREND_INGEST_ENABLED} + a source key + {@code
     *     TREND_INGEST_CLASSIFIER_WORKSPACE_ID}); the UI follows with no frontend release.
     */
    public record PublicConfigResponse(boolean requireEmailOtp, boolean trendsEnabled) {}
}
