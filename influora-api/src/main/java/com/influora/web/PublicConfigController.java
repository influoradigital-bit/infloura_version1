package com.influora.web;

import com.influora.common.ApiResponse;
import com.influora.config.RazorpayProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Non-secret, environment-scoped config the SPA needs to construct client-side SDK calls. Never
 * put anything here that a browser shouldn't hold — this is served over the standard
 * authenticated JSON API (no bearer token bypass), but the response body itself carries no
 * authorization boundary of its own.
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

    public PublicConfigController(RazorpayProperties razorpayProperties) {
        this.razorpayProperties = razorpayProperties;
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
}
