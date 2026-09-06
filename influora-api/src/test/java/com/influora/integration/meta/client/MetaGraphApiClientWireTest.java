package com.influora.integration.meta.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.influora.config.MetaApiProperties;
import com.influora.domain.entity.MetaAuthPath;
import com.influora.integration.meta.dto.BusinessDiscoveryResponse;
import com.influora.integration.meta.service.MetaRateLimitTracker;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;

/**
 * End-to-end gate on the URI {@link MetaGraphApiClient#get} actually puts on the wire, driven
 * through the real {@code RestClient} rather than through {@link MetaGraphApiClient#buildUri}
 * directly.
 *
 * <p>This is the test that fails against the pre-fix code. {@code MetaGraphApiClientUriTest}
 * cannot: {@code buildUri} did not exist before the fix, so a unit test of it can only ever
 * confirm the new code agrees with itself. Here the entry point ({@code get}) is unchanged, so
 * running this class against the old {@code uriBuilder.path(path).queryParam(...)} body reproduces
 * both original defects — {@code IllegalArgumentException: Not enough variable values available to
 * expand 'id,username,...'} on the Business Discovery path, and a {@code %3F} in the path on the
 * insights path.
 *
 * <p>No socket is opened: a recording {@link ClientHttpRequestFactory} captures the URI and
 * returns a canned 200.
 */
@ExtendWith(MockitoExtension.class)
class MetaGraphApiClientWireTest {

    @Mock private MetaApiProperties props;
    @Mock private MetaRateLimitTracker rateLimitTracker;

    private MetaGraphApiClient client;
    private final RecordingRequestFactory requestFactory = new RecordingRequestFactory();

    @BeforeEach
    void setUp() throws Exception {
        when(props.getGraphApiVersion()).thenReturn("v21.0");
        when(props.getRateLimitThrottleThreshold()).thenReturn(95);
        when(props.getRateLimitAlertThreshold()).thenReturn(80);
        when(rateLimitTracker.getCurrentUsage("17841400000000000")).thenReturn(0);

        client = new MetaGraphApiClient(props, rateLimitTracker);

        // Pre-seed the lazily built client map so the request never leaves the JVM. Same baseUrl
        // the production code configures, so the pre-fix uriBuilder path would behave identically
        // here to how it behaved in production.
        Map<MetaAuthPath, RestClient> clients = new EnumMap<>(MetaAuthPath.class);
        clients.put(
                MetaAuthPath.FACEBOOK_LOGIN,
                RestClient.builder()
                        .baseUrl("https://graph.facebook.com/v21.0")
                        .requestFactory(requestFactory)
                        .build());
        Field field = MetaGraphApiClient.class.getDeclaredField("restClients");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<MetaAuthPath, RestClient> target = (Map<MetaAuthPath, RestClient>) field.get(client);
        target.putAll(clients);
    }

    @Test
    @DisplayName("Business Discovery reaches the wire at all, with the nested fields intact")
    void businessDiscoveryReachesTheWire() {
        String path =
                "/17841400000000000?fields=business_discovery.username(tejas)"
                        + "{id,username,name,biography,profile_picture_url,followers_count,media_count}";

        client.get(path, "TOKEN", BusinessDiscoveryResponse.class, "17841400000000000");

        assertThat(requestFactory.lastUri)
                .isEqualTo(
                        "https://graph.facebook.com/v21.0/17841400000000000"
                                + "?fields=business_discovery.username(tejas)"
                                + "%7Bid,username,name,biography,profile_picture_url,followers_count,media_count%7D"
                                + "&access_token=TOKEN");
    }

    @Test
    @DisplayName("An insights call sends its metrics as query parameters, not as path text")
    void insightsQueryStaysAQuery() {
        client.get(
                "/17841400000000000/insights?metric=reach,impressions&period=day",
                "TOKEN",
                BusinessDiscoveryResponse.class,
                "17841400000000000");

        assertThat(requestFactory.lastUri)
                .isEqualTo(
                        "https://graph.facebook.com/v21.0/17841400000000000/insights"
                                + "?metric=reach,impressions&period=day&access_token=TOKEN")
                .doesNotContain("%3F");
    }

    /** Captures the outgoing URI and answers every request with an empty JSON object. */
    private static final class RecordingRequestFactory implements ClientHttpRequestFactory {

        private String lastUri;

        @Override
        public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) {
            return new ClientHttpRequest() {
                private final HttpHeaders headers = new HttpHeaders();
                private final ByteArrayOutputStream body = new ByteArrayOutputStream();

                @Override
                public HttpMethod getMethod() {
                    return httpMethod;
                }

                @Override
                public URI getURI() {
                    return uri;
                }

                @Override
                public HttpHeaders getHeaders() {
                    return headers;
                }

                @Override
                public OutputStream getBody() {
                    return body;
                }

                @Override
                public ClientHttpResponse execute() {
                    lastUri = uri.toASCIIString();
                    return new ClientHttpResponse() {
                        @Override
                        public HttpStatusCode getStatusCode() {
                            return HttpStatusCode.valueOf(200);
                        }

                        @Override
                        public String getStatusText() {
                            return "OK";
                        }

                        @Override
                        public void close() {}

                        @Override
                        public InputStream getBody() {
                            return new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8));
                        }

                        @Override
                        public HttpHeaders getHeaders() {
                            HttpHeaders responseHeaders = new HttpHeaders();
                            responseHeaders.setContentType(MediaType.APPLICATION_JSON);
                            return responseHeaders;
                        }
                    };
                }
            };
        }
    }
}
