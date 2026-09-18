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
class YouTubeMostPopularClientTest {

    private YouTubeMostPopularClient buildClient(TrendIngestProperties props, RestClient restClient)
            throws Exception {
        Constructor<YouTubeMostPopularClient> ctor =
                YouTubeMostPopularClient.class.getDeclaredConstructor(
                        TrendIngestProperties.class, RestClient.class);
        ctor.setAccessible(true);
        return ctor.newInstance(props, restClient);
    }

    @Test
    @DisplayName("request hits /v3/videos?chart=mostPopular with regionCode=IN, maxResults=20 and"
            + " the key param named `key` (not `api_key`, unlike TMDb)")
    void sendsCorrectRequestShape() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server
                .expect(
                        requestTo(
                                "https://www.googleapis.com/youtube/v3/videos"
                                        + "?part=snippet&chart=mostPopular&regionCode=IN&maxResults=20"
                                        + "&key=test-yt-key"))
                .andRespond(
                        withSuccess(
                                "{\"items\":[{\"snippet\":{\"title\":\"Trending Video One\"}}]}",
                                MediaType.APPLICATION_JSON));

        TrendIngestProperties props = new TrendIngestProperties();
        props.setYoutubeApiKey("test-yt-key");
        YouTubeMostPopularClient client = buildClient(props, builder.build());

        List<RawTrend> results = client.fetch();

        server.verify();
        assertEquals(1, results.size());
        assertEquals("Trending Video One", results.get(0).text());
        assertEquals("youtube_trending", results.get(0).source());
    }

    @Test
    @DisplayName("blank YouTube key: isConfigured() false, fetch() returns empty with no request")
    void skipsCleanlyWhenNotConfigured() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        YouTubeMostPopularClient client = buildClient(new TrendIngestProperties(), builder.build());

        assertTrue(client.fetch().isEmpty());
        server.verify();
    }
}
