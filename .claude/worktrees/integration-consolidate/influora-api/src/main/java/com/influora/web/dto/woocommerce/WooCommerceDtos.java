package com.influora.web.dto.woocommerce;

/** WooCommerce connect request/response records, grouped per the {@code ShopifyDtos} convention. */
public final class WooCommerceDtos {

    private WooCommerceDtos() {}

    /**
     * Request body for {@code POST /woocommerce/connect} -- the brand submits their own site URL
     * and the webhook secret they generated/entered when creating the webhook in their WooCommerce
     * admin (Settings -> Advanced -> Webhooks), pointed at our {@code /webhooks/woocommerce}
     * endpoint. There is no OAuth code/state here -- see {@code WooCommerceConnectController}
     * javadoc for why this integration has no authorize/callback round trip.
     */
    public record WooCommerceConnectRequest(String siteUrl, String webhookSecret) {}

    /** Response for {@code POST /woocommerce/connect} once the secret has been validated, normalized, and stored encrypted. */
    public record WooCommerceConnectResponse(boolean connected, String siteUrl) {}
}
