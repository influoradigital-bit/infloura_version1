package com.influora.integration.woocommerce;

import com.influora.common.ApiException;
import java.net.URI;
import java.net.URISyntaxException;
import org.springframework.http.HttpStatus;

/**
 * Normalizes and validates a brand-supplied WooCommerce site URL, Wave D task D2.
 *
 * <p>Unlike {@code ShopifyOAuthService#validateShopDomain} (which validates against a fixed {@code
 * *.myshopify.com} suffix because Shopify hosts every store on its own domain), a WooCommerce site
 * is self-hosted at an arbitrary domain the brand controls -- there is no fixed suffix to check
 * against. This class is therefore NOT an SSRF guard the way Shopify's domain pattern is (this
 * integration never makes an outbound call to the brand's site at all -- see {@code
 * WooCommerceConnectController} javadoc: this is a webhook-receive-only integration, there is no
 * OAuth callback or API call that interpolates this value into a URL this server dials out to).
 * Its job is narrower: reject obviously-malformed input and produce a STABLE, CANONICAL string
 * (scheme + host [+ port], lower-cased, no path/query/fragment, no trailing slash) so the same
 * site is never accidentally stored as two different {@code site_url} rows (e.g. {@code
 * "https://Shop.example.com/"} vs {@code "https://shop.example.com"}), since {@code
 * woocommerce_integrations.site_url} is the ONLY identifier {@code WooCommerceWebhookController}
 * trusts to resolve an integration from the {@code X-WC-Webhook-Source} header WooCommerce sends
 * with every delivery -- see that controller's class javadoc for the full trust model. A brand
 * whose stored {@code site_url} doesn't byte-for-byte match what WooCommerce sends in that header
 * would otherwise see every webhook rejected as {@code SITE_NOT_CONNECTED}, a real functional bug
 * this normalization exists to prevent, not just a cosmetic one.
 */
public final class WooCommerceSiteUrl {

    private WooCommerceSiteUrl() {}

    /**
     * @throws ApiException {@code INVALID_SITE_URL} (400) if {@code rawUrl} is null/blank, not a
     *     well-formed absolute URL, or not {@code http}/{@code https}
     */
    public static String normalize(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw invalid();
        }
        URI uri;
        try {
            uri = new URI(rawUrl.trim());
        } catch (URISyntaxException e) {
            throw invalid();
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw invalid();
        }
        int port = uri.getPort();
        String portSuffix = (port == -1) ? "" : ":" + port;
        return scheme.toLowerCase() + "://" + host.toLowerCase() + portSuffix;
    }

    private static ApiException invalid() {
        return new ApiException(
                "INVALID_SITE_URL", "site_url must be a valid absolute http(s) URL", HttpStatus.BAD_REQUEST);
    }
}
