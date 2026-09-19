package com.influora.service.trendspark.ingest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.influora.config.TrendIngestProperties;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * T-GOLIVE-0918 [vikram · 2026-09-18] — NewsAPI {@code /v2/top-headlines} source for
 * {@code TrendPullJob}. Source: .proof-os/tasks/T-COPILOT-ON-0910/job-design.md step 6, request
 * shape verified against trendspark/n8n/trend-pull-workflow.json node id {@code http-newsapi}.
 *
 * <p>The key authenticates via the {@code X-Api-Key} HEADER, not the query string — the one
 * source of the three that does. {@code sourceId()} is exactly {@code "news"}, not
 * {@code "newsapi"}, matching the n8n normalizer's persisted value (trends.source is a JSON
 * array read by downstream consumers; drift there makes old and new rows incomparable).
 */
@Component
public class NewsApiTopHeadlinesClient implements TrendSourceClient {

    private static final Logger log = LoggerFactory.getLogger(NewsApiTopHeadlinesClient.class);
    private static final String BASE_URL = "https://newsapi.org/v2/top-headlines";
    private static final int CONNECT_TIMEOUT_SECONDS = 5;
    private static final int REQUEST_TIMEOUT_SECONDS = 15;

    private final TrendIngestProperties props;
    private volatile RestClient restClient;

    // EV-175 [vikram · 2026-09-19] — two constructors (this one and the package-private test
    // seam below) and neither @Autowired, so Spring fell back to a missing no-arg constructor
    // ("No default constructor found") and the API failed to boot. Guarded by
    // TrendSourceClientsWiringTest.
    @Autowired
    public NewsApiTopHeadlinesClient(TrendIngestProperties props) {
        this.props = props;
    }

    /** Package-visible constructor for tests — injects a REAL {@code RestClient} bound to
     * {@code MockRestServiceServer}. */
    NewsApiTopHeadlinesClient(TrendIngestProperties props, RestClient restClient) {
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
        return "news";
    }

    @Override
    public boolean isConfigured() {
        return props.hasNewsapiKey();
    }

    @Override
    public List<RawTrend> fetch() {
        if (!isConfigured()) {
            return List.of();
        }
        try {
            String country = props.getRegion() == null ? "in" : props.getRegion().toLowerCase(Locale.ROOT);
            String query = "country=" + enc(country) + "&category=entertainment&pageSize=20";
            URI uri = URI.create(BASE_URL + "?" + query);
            NewsApiResponse response =
                    restClient()
                            .get()
                            .uri(uri)
                            .header("X-Api-Key", props.getNewsapiApiKey())
                            .retrieve()
                            .body(NewsApiResponse.class);
            if (response == null || response.articles() == null) {
                return List.of();
            }
            List<RawTrend> out = new ArrayList<>();
            for (Article a : response.articles()) {
                if (a.title() != null && !a.title().isBlank()) {
                    out.add(new RawTrend(a.title(), sourceId(), ""));
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("NewsApiTopHeadlinesClient: fetch failed: {}", e.getMessage());
            return List.of();
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record NewsApiResponse(List<Article> articles) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Article(String title) {}
}
