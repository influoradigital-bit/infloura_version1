package com.influora.service.meera.tool.creator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.MediaMetric;
import com.influora.domain.entity.MetaOAuthToken;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.MediaMetricsRepository;
import com.influora.repository.MetaOAuthTokenRepository;
import com.influora.service.CreatorAgentPreferencesService;
import com.influora.service.creatorcopilot.ConnectedInstagramAccount;
import com.influora.service.creatorcopilot.CreatorIntelligenceService;
import com.influora.web.dto.creator.CreatorAgentDtos.PreferencesResponse;
import com.influora.web.dto.meera.CreatorToolDtos.GetMyContentPatternsResult;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

/**
 * Meera intelligence v1 (T20) -- the REAL wire shape of {@code get_my_content_patterns}, checked
 * in as {@code influora-ai/tests/fixtures/creator_tools/get_my_content_patterns.real.json}.
 *
 * <p>WHY. influora-ai's model copy and the frontend's type guard are built against this payload.
 * If each side tested against its own hand-built dict, a renamed {@code @JsonProperty} would stay
 * green on every side and break at the seam (it has happened twice on this codebase). So the
 * fixture is not typed by hand: this test builds a real result through {@link
 * CreatorIntelligenceService} and {@link GetMyContentPatternsExecutor} from {@code
 * MediaMetric.builder()} rows, serialises it with Spring Boot's OWN configured {@link ObjectMapper},
 * and compares it with the checked-in file. The Python (T21) and TypeScript (T22) tests load that
 * file.
 *
 * <p>On a mismatch the fresh JSON is printed. To regenerate deliberately, run with {@code
 * -DupdateContentPatternsFixture=true}; T21/T22 then go red until Python and TS follow.
 * Everything is deterministic: a fixed {@code now}, fixed ids, fixed numbers. Line endings are
 * normalised to {@code \n} before comparing, so a CRLF checkout on Windows compares equal.
 */
@JsonTest
class GetMyContentPatternsWireShapeTest {

    static final Path FIXTURE =
            Path.of("..", "influora-ai", "tests", "fixtures", "creator_tools", "get_my_content_patterns.real.json");

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    /** Friday 25 Sep 2026, 18:00 IST. */
    private static final Instant NOW = LocalDate.of(2026, 9, 25).atTime(18, 0).atZone(IST).toInstant();
    private static final String USER = "01HWIRESHAPECREATOR0000A";
    private static final String PROFILE_ID = "01HWIRESHAPEPROFILE000A";
    private static final String ACCOUNT = "17841400000000001";

    @Autowired private ObjectMapper objectMapper;

    private static Instant ist(int day, int hour, int minute) {
        return LocalDate.of(2026, 9, day).atTime(hour, minute).atZone(IST).toInstant();
    }

    private static MediaMetric post(
            String mediaId, String type, Instant postedAt, Duration readAfter, long reach, long views, Long interactions) {
        String path = "VIDEO".equals(type) || "REELS".equals(type) ? "reel" : "p";
        return MediaMetric.builder()
                .id("01HWIRE" + mediaId)
                .mediaId(mediaId)
                .creatorProfileId(PROFILE_ID)
                .igAccountId(ACCOUNT)
                .platform("INSTAGRAM")
                .mediaType(type)
                .postedAt(postedAt)
                .time(postedAt.plus(readAfter))
                .fetchedAt(postedAt.plus(readAfter))
                .reach(reach)
                .impressions(views)
                .engagement(interactions)
                .likes(null)
                .caption("never read, never sent")
                .permalink("https://www.instagram.com/" + path + "/C" + mediaId.substring(mediaId.length() - 4) + "/")
                .build();
    }

    private static List<MediaMetric> rows() {
        Duration threeDays = Duration.ofDays(3);
        List<MediaMetric> rows = new ArrayList<>();
        // Reels and videos, weekday mornings.
        rows.add(post("18040000000000101", "REELS", ist(1, 8, 30), threeDays, 4200, 9800, 380L));
        rows.add(post("18040000000000102", "VIDEO", ist(3, 8, 30), threeDays, 3900, 8700, 350L));
        rows.add(post("18040000000000103", "REELS", ist(8, 8, 30), threeDays, 5100, 12100, 470L));
        rows.add(post("18040000000000104", "REELS", ist(10, 8, 30), threeDays, 4600, 10400, 410L));
        // Photo posts, weekday evenings.
        rows.add(post("18040000000000201", "IMAGE", ist(2, 19, 40), threeDays, 1100, 1800, 66L));
        rows.add(post("18040000000000202", "IMAGE", ist(9, 19, 40), threeDays, 950, 1500, 52L));
        rows.add(post("18040000000000203", "IMAGE", ist(15, 19, 40), threeDays, 1240, 2100, 81L));
        rows.add(post("18040000000000204", "IMAGE", ist(17, 19, 40), threeDays, 1020, 1650, 58L));
        // Carousels, weekend evenings.
        rows.add(post("18040000000000301", "CAROUSEL_ALBUM", ist(5, 18, 15), threeDays, 820, 1300, 98L));
        rows.add(post("18040000000000302", "CAROUSEL_ALBUM", ist(6, 18, 15), threeDays, 760, 1200, 91L));
        rows.add(post("18040000000000303", "CAROUSEL_ALBUM", ist(12, 18, 15), threeDays, 900, 1450, 117L));
        rows.add(post("18040000000000304", "CAROUSEL_ALBUM", ist(13, 18, 15), threeDays, 870, 1400, 104L));
        // Meta sent no media type, and no interactions (hidden): OTHER, no engagement rate.
        rows.add(post("18040000000000401", "UNKNOWN", ist(16, 20, 10), threeDays, 700, 1100, null));
        // Unsettled: posted 20 h ago; and a 10-day-old post whose newest reading is 6 h after posting.
        rows.add(post("18040000000000501", "REELS", ist(24, 22, 0), Duration.ofHours(19), 300, 600, 20L));
        rows.add(post("18040000000000502", "IMAGE", ist(15, 21, 0), Duration.ofHours(6), 200, 350, 9L));
        return rows;
    }

    private GetMyContentPatternsResult realResult() {
        MediaMetricsRepository media = mock(MediaMetricsRepository.class);
        CreatorProfileRepository profiles = mock(CreatorProfileRepository.class);
        MetaOAuthTokenRepository tokens = mock(MetaOAuthTokenRepository.class);
        CreatorAgentPreferencesService preferences = mock(CreatorAgentPreferencesService.class);

        CreatorProfile profile = mock(CreatorProfile.class);
        when(profile.getId()).thenReturn(PROFILE_ID);
        when(profiles.findByUserId(USER)).thenReturn(Optional.of(profile));
        when(tokens.findByCreatorProfileIdAndWorkspaceIdIsNullAndRevokedFalse(PROFILE_ID))
                .thenReturn(
                        Optional.of(
                                MetaOAuthToken.builder()
                                        .id("tok")
                                        .creatorProfileId(PROFILE_ID)
                                        .igBusinessAccountId(ACCOUNT)
                                        .encryptedAccessToken("enc")
                                        .expiresAt(NOW.plus(Duration.ofDays(40)))
                                        .build()));
        when(media.findNewestSnapshotPerPostSinceForAccount(eq(PROFILE_ID), any(), eq(ACCOUNT))).thenReturn(rows());
        when(preferences.getOrCreatePreferences(USER))
                .thenReturn(
                        new PreferencesResponse(
                                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, "INR", List.of(), List.of(), 0,
                                "en-IN", "FRIENDLY", null, null, "Asia/Kolkata", List.of(), null, false, null,
                                true, "v1", false, null, false, 0, false));

        CreatorIntelligenceService service =
                new CreatorIntelligenceService(media, profiles, new ConnectedInstagramAccount(tokens), tokens);
        return new GetMyContentPatternsExecutor(service, preferences).executeAt(USER, NOW);
    }

    private String serialise(GetMyContentPatternsResult result) throws IOException {
        DefaultIndenter lf = new DefaultIndenter("  ", "\n");
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter().withObjectIndenter(lf).withArrayIndenter(lf);
        return objectMapper.writer(printer).writeValueAsString(result) + "\n";
    }

    @Test
    @DisplayName(
            "T20: the real get_my_content_patterns JSON (service + executor + the app's ObjectMapper)"
                    + " equals the checked-in fixture influora-ai and the frontend test against")
    void realPayloadMatchesCheckedInFixture() throws IOException {
        GetMyContentPatternsResult result = realResult();
        // Non-vacuity: the fixture must exercise every list and both claim kinds.
        assertTrue(result.enoughData());
        assertTrue(!result.bestPosts().isEmpty() && !result.weakPosts().isEmpty());
        assertTrue(result.whatWorks().stream().anyMatch(w -> w.kind().equals("POST_TYPE")));
        assertTrue(result.whatWorks().stream().anyMatch(w -> w.kind().equals("POSTING_WINDOW")));
        assertEquals(2, result.unsettledPosts());

        String fresh = serialise(result);
        if (Boolean.getBoolean("updateContentPatternsFixture")) {
            Files.createDirectories(FIXTURE.getParent());
            Files.writeString(FIXTURE, fresh, StandardCharsets.UTF_8);
        }
        if (!Files.exists(FIXTURE)) {
            System.out.println("---- fresh get_my_content_patterns JSON ----\n" + fresh);
            throw new AssertionError("fixture missing: " + FIXTURE.toAbsolutePath() + "\nfresh JSON:\n" + fresh);
        }
        String checkedIn = Files.readString(FIXTURE, StandardCharsets.UTF_8).replace("\r\n", "\n");
        if (!checkedIn.equals(fresh)) {
            System.out.println("---- fresh get_my_content_patterns JSON ----\n" + fresh);
        }
        assertEquals(
                checkedIn,
                fresh,
                "the real wire shape differs from " + FIXTURE + " -- if the change is intended, regenerate with"
                        + " -DupdateContentPatternsFixture=true and update influora-ai + the frontend to match");
        // Captions never reach the payload.
        assertTrue(!fresh.contains("never read, never sent"));
    }
}
