package com.influora.web;

import com.influora.common.ApiResponse;
import com.influora.domain.entity.Workspace;
import com.influora.integration.tracking.webhook.ConversionWebhookSecretService;
import com.influora.security.AuthPrincipal;
import com.influora.service.BrandContextService;
import com.influora.web.dto.tracking.ConversionWebhookSecretDtos.ConversionWebhookSecretResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Brand-facing secret-generation endpoint for {@code ConversionWebhookController}'s HMAC
 * signature verification — Wave E4 capstone red-team fix ({@code
 * wiki/errors/wave-e4-full-redteam-signoff.md} Part D / Condition 1). See {@code
 * ConversionWebhookSecret} class javadoc for the full trust-model rationale (server-generated,
 * per-workspace, unlike Shopify's app-level secret or WooCommerce's brand-generated-in-a-
 * third-party-admin-panel secret).
 *
 * <p>Brand-authenticated, same resolve-then-scope pattern as every other brand-facing connect
 * controller in this codebase ({@link BrandContextService#requireBrandWorkspace}).
 *
 * <p><b>One-time reveal.</b> {@code POST /webhook-secret/generate} is the ONLY response that will
 * ever carry the plaintext secret — calling it again ROTATES the secret (the previous one stops
 * verifying immediately) and returns a NEW plaintext value; there is no "show me my existing
 * secret again" endpoint, by design, mirroring the one-way nature of every other secret this
 * codebase stores encrypted.
 */
@RestController
@RequestMapping("/webhook-secret")
public class ConversionWebhookSecretController {

    private final ConversionWebhookSecretService secretService;
    private final BrandContextService brandContextService;

    public ConversionWebhookSecretController(
            ConversionWebhookSecretService secretService, BrandContextService brandContextService) {
        this.secretService = secretService;
        this.brandContextService = brandContextService;
    }

    /**
     * Generates (or rotates) the calling workspace's HMAC signing secret and returns it in
     * plaintext exactly once. The brand must configure their own commerce backend to send it back
     * as an {@code X-Influora-Signature} header on every conversion/redemption webhook call.
     */
    @PostMapping("/generate")
    public ApiResponse<ConversionWebhookSecretResponse> generate(@AuthenticationPrincipal AuthPrincipal principal) {
        Workspace workspace = brandContextService.requireBrandWorkspace(principal);
        String plaintext = secretService.generate(workspace.getId());
        return ApiResponse.ok(new ConversionWebhookSecretResponse(plaintext));
    }

    /**
     * Revokes the calling workspace's secret. After this call, every subsequent {@code
     * /webhooks/conversion}/{@code /webhooks/redemption} request claiming to be from this
     * workspace is rejected {@code INVALID_WEBHOOK_SIGNATURE} until a new secret is generated —
     * same fail-closed behavior as a workspace that never configured one.
     */
    @DeleteMapping
    public ApiResponse<Void> revoke(@AuthenticationPrincipal AuthPrincipal principal) {
        Workspace workspace = brandContextService.requireBrandWorkspace(principal);
        secretService.revoke(workspace.getId());
        return ApiResponse.ok(null);
    }
}
