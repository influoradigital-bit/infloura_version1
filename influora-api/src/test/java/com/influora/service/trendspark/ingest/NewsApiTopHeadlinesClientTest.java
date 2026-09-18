package com.influora.service.trendspark.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.influora.config.TrendIngestProperties;
import com.influora.service.trendspark.ingest.TrendSourceClient.RawTrend;
import java.lang.reflect.Constructor;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * T-GOLIVE-0918 [vikram · 2026-09-18] — real {@code RestClient} bound to {@code
 * MockRestServiceServer}, not a Mockito mock of this client — proves the actual request shape
 * (URL, header auth) that goes on the wire, per this go-live task's rule 5 ("Use real HTTP
 * clients mocked at the wire ... so request shape bugs are caught") and this codebase's own
 * precedent (see {@code MetaOAuthServiceUriEncodingTest}'s F-0813 writeup: a Mockito-mocked
 * {@code RestClient} would stub away exactly the layer a request-shape bug lives in).
 */
class NewsApiTopHeadlinesClientTest {

    private static final String RESPONSE_JSON =
            "{\"status\":\"ok\",\"articles\":[{\"title\":\"Diwali fashion haul trending\"},"
                    + "{\"title\":\"\"}]}";

    private NewsApiTopHeadlinesClient buildClient(TrendIngestProperties props, RestClient restClient)
            throws Exception {
        Constructor<NewsApiTopHeadlinesClient> ctor =
                NewsApiTopHeadlinesClient.class.getDeclaredConstructor(
                        TrendIngestProperties.class, RestClient.class);
        ctor.setAccessible(true);
        return ctor.newInstance(props, restClient);
    }

    private TrendIngestProperties propsWithKey() {
        TrendIngestProperties props = new TrendIngestProperties();
        props.setNewsapiApiKey("test-news-key");
        props.setRegion("IN");
        return props;
    }

    @Test
    @DisplayName("request hits top-headlines with country=in, category=entertainment, and the key"
            + " in the X-Api-Key HEADER, not the query string")
    void sendsCorrectRequestShape() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server
                .expect(
                        requestTo(
                                "https://newsapi.org/v2/top-headlines"
                                        + "?country=in&category=entertainment&pageSize=20"))
                .andExpect(header("X-Api-Key", "test-news-key"))
                .andRespond(withSuccess(RESPONSE_JSON, MediaType.APPLICATION_JSON));

        NewsApiTopHeadlinesClient client = buildClient(propsWithKey(), builder.build());
        List<RawTrend> results = client.fetch();

        server.verify();
        assertEquals(1, results.size());
        assertEquals("Diwali fashion haul trending", results.get(0).text());
        assertEquals("news", results.get(0).source());
    }

    @Test
    @DisplayName("a transport/HTTP failure never throws — fetch() returns an empty list")
    void neverThrowsOnFailure() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://newsapi.org")))
                .andRespond(withServerError());

        NewsApiTopHeadlinesClient client = buildClient(propsWithKey(), builder.build());
        List<RawTrend> results = client.fetch();

        assertTrue(results.isEmpty());
    }

    @Test
    @DisplayName("isConfigured() is false with a blank key, and fetch() then makes NO request")
    void skipsCleanlyWhenNotConfigured() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // No .expect(...) registered at all — .verify() below fails if any request went out.

        TrendIngestProperties props = new TrendIngestProperties(); // blank newsapiApiKey
        NewsApiTopHeadlinesClient client = buildClient(props, builder.build());

        assertTrue(client.fetch().isEmpty());
        assertEquals(false, client.isConfigured());
        server.verify();
    }
}
