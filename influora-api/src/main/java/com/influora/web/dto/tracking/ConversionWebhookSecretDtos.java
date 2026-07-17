package com.influora.web.dto.tracking;

/**
 * Brand-facing DTOs for {@code ConversionWebhookSecretController} — Wave E4 capstone red-team fix
 * ({@code wiki/errors/wave-e4-full-redteam-signoff.md} Part D / Condition 1). Grouped per the
 * {@code WooCommerceDtos}/{@code ShopifyDtos} convention.
 */
public final class ConversionWebhookSecretDtos {

    private ConversionWebhookSecretDtos() {}

    /**
     * Response for {@code POST /webhook-secret/generate}. {@code secret} is the PLAINTEXT HMAC
     * signing secret — this is the ONLY response that will ever carry it; it is encrypted at rest
     * immediately and cannot be retrieved again (see {@code ConversionWebhookSecretService} class
     * javadoc). The caller is responsible for storing it securely in their own commerce backend and
     * using it to compute an {@code X-Influora-Signature} HMAC-SHA256 (base64) header, over the
     * exact raw JSON body, on every {@code POST /webhooks/conversion}/{@code /webhooks/redemption}
     * request.
     */
    public record ConversionWebhookSecretResponse(String secret) {}
}
