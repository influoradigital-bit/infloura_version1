package com.influora.integration.woocommerce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.influora.common.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link WooCommerceSiteUrl#normalize} -- load-bearing for {@code
 * WooCommerceWebhookController}'s site-to-workspace resolution (see that class's javadoc): the
 * SAME normalization must be applied at connect time ({@code WooCommerceConnectController}) and at
 * webhook-receive time so a header value differing only in scheme/host case or a trailing slash
 * still resolves to the stored row.
 */
class WooCommerceSiteUrlTest {

    @Test
    @DisplayName("normalize: lower-cases scheme and host")
    void normalize_lowerCasesSchemeAndHost() {
        assertEquals("https://my-store.example.com", WooCommerceSiteUrl.normalize("HTTPS://My-Store.Example.COM"));
    }

    @Test
    @DisplayName("normalize: strips a trailing slash")
    void normalize_stripsTrailingSlash() {
        assertEquals("https://my-store.example.com", WooCommerceSiteUrl.normalize("https://my-store.example.com/"));
    }

    @Test
    @DisplayName("normalize: strips path, query, and fragment")
    void normalize_stripsPathQueryFragment() {
        assertEquals(
                "https://my-store.example.com",
                WooCommerceSiteUrl.normalize("https://my-store.example.com/wp-json/wc/v3?foo=bar#frag"));
    }

    @Test
    @DisplayName("normalize: preserves a non-default port")
    void normalize_preservesNonDefaultPort() {
        assertEquals("http://localhost:8080", WooCommerceSiteUrl.normalize("http://localhost:8080/"));
    }

    @Test
    @DisplayName("normalize: two URLs differing only by trailing slash / case normalize to the SAME value")
    void normalize_equivalentUrlsConverge() {
        String a = WooCommerceSiteUrl.normalize("https://My-Store.example.com/");
        String b = WooCommerceSiteUrl.normalize("HTTPS://my-store.EXAMPLE.com");
        assertEquals(a, b);
    }

    @Test
    @DisplayName("normalize: rejects null")
    void normalize_rejectsNull() {
        ApiException ex = assertThrows(ApiException.class, () -> WooCommerceSiteUrl.normalize(null));
        assertEquals("INVALID_SITE_URL", ex.getCode());
    }

    @Test
    @DisplayName("normalize: rejects blank")
    void normalize_rejectsBlank() {
        assertThrows(ApiException.class, () -> WooCommerceSiteUrl.normalize("   "));
    }

    @Test
    @DisplayName("normalize: rejects a malformed URL")
    void normalize_rejectsMalformed() {
        assertThrows(ApiException.class, () -> WooCommerceSiteUrl.normalize("not a url ::"));
    }

    @Test
    @DisplayName("normalize: rejects a scheme-relative / hostless value")
    void normalize_rejectsHostless() {
        assertThrows(ApiException.class, () -> WooCommerceSiteUrl.normalize("//example.com"));
    }

    @Test
    @DisplayName("normalize: rejects a non-http(s) scheme")
    void normalize_rejectsNonHttpScheme() {
        assertThrows(ApiException.class, () -> WooCommerceSiteUrl.normalize("ftp://example.com"));
    }
}
