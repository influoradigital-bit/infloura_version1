package com.influora.service.trendspark.ingest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.influora.config.TrendIngestProperties;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * T-GOLIVE-0918 [vikram · 2026-09-18] — YouTube Data API {@code /v3/videos?chart=mostPopular}
 * source for {@code TrendPullJob}. Source: .proof-os/tasks/T-COPILOT-ON-0910/job-design.md
 * step 7, request shape verified against trendspark/n8n/trend-pull-workflow.json node id
 * {@code http-youtube}. Query param is {@code key} (not {@code api_key}, unlike TMDb).
 */
@Component
public class YouTubeMostPopularClient implements TrendSourceClient {

    private static final Logger log = LoggerFactory.getLogger(YouTubeMostPopularClient.class);
    private static final String BASE_URL = "https://www.googleapis.com/youtube/v3/videos";
    private static final int CONNECT_TIMEOUT_SECONDS = 5;
    private static final int REQUEST_TIMEOUT_SECONDS = 15;

    private final TrendIngestProperties props;
    private volatile RestClient restClient;

    // EV-175 [vikram · 2026-09-19] — two constructors (this one and the package-private test
    // seam below) and neither @Autowired, so Spring fell back to a missing no-arg constructor
    // ("No default constructor found") and the API failed to boot. Guarded by
    // TrendSourceClientsWiringTest.
    @Autowired
    public YouTubeMostPopularClient(TrendIngestProperties props) {
        this.props = props;
    }

    /** Package-visible constructor for tests — injects a REAL {@code RestClient} bound to
     * {@code MockRestServiceServer}. */
    YouTubeMostPopularClient(TrendIngestProperties props, RestClient restClient) {
        this.props = props;
        this.restClient = restClient;
    }

    private RestClient restClient() {
        RestClient client = restClient;
        if (client == null) {
            synchronized (this) {
                if (restClient == null) {
                    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
                    factory.setConnectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS));
                    factory.setReadTimeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS));
                    restClient = RestClient.builder().requestFactory(factory).build();
                }
                client = restClient;
            }
        }
        return client;
    }

    @Override
    public String sourceId() {
        return "youtube_trending";
    }

    @Override
    public boolean isConfigured() {
        return props.hasYoutubeKey();
    }

    @Override
    public List<RawTrend> fetch() {
        if (!isConfigured()) {
            return List.of();
        }
        try {
            String query =
                    "part=snippet&chart=mostPopular&regionCode=" + enc(props.getRegion())
                            + "&maxResults=20&key=" + enc(props.getYoutubeApiKey());
            URI uri = URI.create(BASE_URL + "?" + query);
            YouTubeVideos response = restClient().get().uri(uri).retrieve().body(YouTubeVideos.class);
            if (response == null || response.items() == null) {
                return List.of();
            }
            List<RawTrend> out = new ArrayList<>();
            for (Item item : response.items()) {
                if (item.snippet() != null
                        && item.snippet().title() != null
                        && !item.snippet().title().isBlank()) {
                    out.add(new RawTrend(item.snippet().title(), sourceId(), ""));
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("YouTubeMostPopularClient: fetch failed: {}", e.getMessage());
            return List.of();
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record YouTubeVideos(List<Item> items) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Item(Snippet snippet) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Snippet(String title) {}
}
