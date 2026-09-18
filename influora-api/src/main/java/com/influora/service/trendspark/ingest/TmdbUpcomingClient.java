package com.influora.service.trendspark.ingest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.influora.config.TrendIngestProperties;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * T-GOLIVE-0918 [vikram · 2026-09-18] — TMDb {@code /movie/upcoming} source for
 * {@code TrendPullJob}. Source: .proof-os/tasks/T-COPILOT-ON-0910/job-design.md step 5, request
 * shape verified against trendspark/n8n/trend-pull-workflow.json node id {@code http-tmdb}.
 *
 * <p>HTTP idiom: lazily-built {@code RestClient} (same lazy-synchronized pattern as {@code
 * MetaGraphApiClient}), absolute {@link URI} built by hand with URL-encoded query values and
 * passed to {@code RestClient.uri(URI)} — that overload uses the URI as-is and does NOT re-encode
 * it, which is the precedent's documented fix for the '?'-as-%3F / double-encoding trap that
 * {@code RestClient.uri(String)} has (see {@code MetaOAuthServiceUriEncodingTest}, F-0813).
 * Unlike {@code MetaGraphApiClient}, an explicit connect+read timeout IS set here — the n8n
 * workflow set {@code options.timeout=15000} on this node, and copying the Meta client's
 * unbounded-read-timeout default would let one hung TMDb socket hold TrendPullJob's ShedLock for
 * its full {@code lockAtMostFor}.
 */
@Component
public class TmdbUpcomingClient implements TrendSourceClient {

    private static final Logger log = LoggerFactory.getLogger(TmdbUpcomingClient.class);
    private static final String BASE_URL = "https://api.themoviedb.org/3/movie/upcoming";
    private static final int CONNECT_TIMEOUT_SECONDS = 5;
    private static final int REQUEST_TIMEOUT_SECONDS = 15;

    private final TrendIngestProperties props;
    private volatile RestClient restClient;

    public TmdbUpcomingClient(TrendIngestProperties props) {
        this.props = props;
    }

    /** Package-visible constructor for tests — injects a REAL {@code RestClient} bound to
     * {@code MockRestServiceServer}, never a Mockito mock of this class's own client. */
    TmdbUpcomingClient(TrendIngestProperties props, RestClient restClient) {
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
        return "tmdb";
    }

    @Override
    public boolean isConfigured() {
        return props.hasTmdbKey();
    }

    @Override
    public List<RawTrend> fetch() {
        if (!isConfigured()) {
            return List.of();
        }
        try {
            String query =
                    "region=" + enc(props.getRegion())
                            + "&language=en-IN"
                            + "&page=1"
                            + "&api_key=" + enc(props.getTmdbApiKey());
            URI uri = URI.create(BASE_URL + "?" + query);
            TmdbUpcoming response = restClient().get().uri(uri).retrieve().body(TmdbUpcoming.class);
            if (response == null || response.results() == null) {
                return List.of();
            }
            List<RawTrend> out = new ArrayList<>();
            for (Result r : response.results()) {
                if (r.title() != null && !r.title().isBlank()) {
                    out.add(new RawTrend(r.title(), sourceId(), ""));
                }
            }
            return out;
        } catch (Exception e) {
            // Never throws — one dead source must not sink the run (F-0781).
            log.warn("TmdbUpcomingClient: fetch failed: {}", e.getMessage());
            return List.of();
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TmdbUpcoming(List<Result> results) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Result(String title, @JsonProperty("release_date") String releaseDate) {}
}
