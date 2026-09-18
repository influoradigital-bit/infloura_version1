package com.influora.service.trendspark.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
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

/** T-GOLIVE-0918 [vikram · 2026-09-18] — real RestClient bound to MockRestServiceServer; see
 * {@link NewsApiTopHeadlinesClientTest} for the full "why wire-level" rationale. */
class TmdbUpcomingClientTest {

    private TmdbUpcomingClient buildClient(TrendIngestProperties props, RestClient restClient)
            throws Exception {
        Constructor<TmdbUpcomingClient> ctor =
                TmdbUpcomingClient.class.getDeclaredConstructor(TrendIngestProperties.class, RestClient.class);
        ctor.setAccessible(true);
        return ctor.newInstance(props, restClient);
    }

    @Test
    @DisplayName("request hits /movie/upcoming with region=IN, language=en-IN, page=1 and the key"
            + " in the query string (api_key, per TMDb's own auth convention)")
    void sendsCorrectRequestShape() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server
                .expect(
                        requestTo(
                                "https://api.themoviedb.org/3/movie/upcoming"
                                        + "?region=IN&language=en-IN&page=1&api_key=test-tmdb-key"))
                .andRespond(
                        withSuccess(
                                "{\"results\":[{\"title\":\"Upcoming Film Two\"}]}",
                                MediaType.APPLICATION_JSON));

        TrendIngestProperties props = new TrendIngestProperties();
        props.setTmdbApiKey("test-tmdb-key");
        TmdbUpcomingClient client = buildClient(props, builder.build());

        List<RawTrend> results = client.fetch();

        server.verify();
        assertEquals(1, results.size());
        assertEquals("Upcoming Film Two", results.get(0).text());
        assertEquals("tmdb", results.get(0).source());
    }

    @Test
    @DisplayName("blank TMDB key: isConfigured() false, fetch() returns empty with no request")
    void skipsCleanlyWhenNotConfigured() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        TmdbUpcomingClient client = buildClient(new TrendIngestProperties(), builder.build());

        assertTrue(client.fetch().isEmpty());
        server.verify();
    }
}
