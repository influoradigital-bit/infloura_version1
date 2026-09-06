package com.influora.integration.meta.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards the URI this client actually puts on the wire.
 *
 * <p>Every pre-existing test around Business Discovery ({@code ExternalCreatorServiceTest},
 * {@code AdminCreatorConnectionServiceTest}) stubs {@code InstagramInsightsClient} with Mockito,
 * so no test ever built a real Graph URL — which is exactly how a path that could not be built at
 * all reached production and returned {@code 500 INTERNAL_ERROR} on {@code GET
 * /creators/external/lookup?username=…}. These assertions are on the string, deliberately: the
 * whole failure was in string handling, so a test that mocks the builder proves nothing.
 */
class MetaGraphApiClientUriTest {

    private static final String BASE = "https://graph.facebook.com/v21.0";

    @Test
    @DisplayName("Business Discovery: nested-field braces survive as %7B/%7D instead of throwing")
    void businessDiscoveryPathBuilds() {
        // The exact path InstagramInsightsClient.businessDiscovery assembles. Under the old
        // uriBuilder.path(...) form this threw IllegalArgumentException ("Not enough variable
        // values available to expand 'id,username,...'") because {...} was read as a URI template
        // variable — a plain RuntimeException that no catch(MetaApiException) could see.
        String path =
                "/17841400000000000?fields=business_discovery.username(tejas)"
                        + "{id,username,name,biography,profile_picture_url,followers_count,media_count}";

        URI uri = MetaGraphApiClient.buildUri(BASE, path, "TOKEN");

        assertThat(uri.toASCIIString())
                .isEqualTo(
                        "https://graph.facebook.com/v21.0/17841400000000000"
                                + "?fields=business_discovery.username(tejas)"
                                + "%7Bid,username,name,biography,profile_picture_url,followers_count,media_count%7D"
                                + "&access_token=TOKEN");
        assertThat(uri.getRawPath()).isEqualTo("/v21.0/17841400000000000");
    }

    @Test
    @DisplayName("A caller-supplied query string stays a query string, not a %3F path segment")
    void queryStringIsNotEncodedIntoThePath() {
        // Every call site in InstagramInsightsClient / FacebookPageClient / CreatorMarketplaceClient
        // passes its query inside `path`. UriBuilder.path() encoded the '?' to %3F, so Graph saw a
        // nonexistent path node and zero parameters.
        URI uri =
                MetaGraphApiClient.buildUri(
                        BASE, "/17841400000000000/insights?metric=reach,impressions&period=day", "TOKEN");

        assertThat(uri.getRawPath()).isEqualTo("/v21.0/17841400000000000/insights");
        assertThat(uri.getRawQuery()).isEqualTo("metric=reach,impressions&period=day&access_token=TOKEN");
        assertThat(uri.toASCIIString()).doesNotContain("%3F");
    }

    @Test
    @DisplayName("A path with no query of its own still gets access_token")
    void pathWithoutQuery() {
        URI uri = MetaGraphApiClient.buildUri(BASE, "/me/accounts", "TOKEN");

        assertThat(uri.toASCIIString()).isEqualTo("https://graph.facebook.com/v21.0/me/accounts?access_token=TOKEN");
    }

    @Test
    @DisplayName("Token characters that are unsafe in a query are escaped")
    void accessTokenIsEncoded() {
        // Meta tokens are URL-safe today, but the token is the one component we do not control
        // the shape of; an unescaped '&' in it would silently truncate the request.
        URI uri = MetaGraphApiClient.buildUri(BASE, "/me/accounts", "a&b=c d");

        assertThat(uri.getRawQuery()).isEqualTo("access_token=a%26b%3Dc%20d");
        assertThat(uri.getQuery()).isEqualTo("access_token=a&b=c d");
    }

    @Test
    @DisplayName("The instagram.com host is honoured for Instagram-Login tokens")
    void instagramHost() {
        URI uri =
                MetaGraphApiClient.buildUri(
                        "https://graph.instagram.com/v21.0", "/me?fields=id,username", "TOKEN");

        assertThat(uri.getHost()).isEqualTo("graph.instagram.com");
        assertThat(uri.getRawQuery()).isEqualTo("fields=id,username&access_token=TOKEN");
    }
}
