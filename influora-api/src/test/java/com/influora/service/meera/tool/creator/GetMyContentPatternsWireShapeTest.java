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
import com.influora.domain.entity.CreatorRecommendation;
import com.influora.domain.entity.MediaMetric;
import com.influora.domain.entity.MetaOAuthToken;
import com.influora.domain.enums.ChallengeDayType;
import com.influora.domain.enums.CreatorRecommendationSource;
import com.influora.domain.enums.CreatorRecommendationStatus;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.CreatorRecommendationRepository;
import com.influora.repository.MediaMetricsRepository;
import com.influora.repository.MetaOAuthTokenRepository;
import com.influora.service.CreatorAgentPreferencesService;
import com.influora.service.creatorcopilot.ConnectedInstagramAccount;
import com.influora.service.creatorcopilot.CreatorIntelligenceService;
import com.influora.service.creatorcopilot.CreatorRecommendationOutcomeService;
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
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
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
                .caption(captionFor(mediaId))
                .permalink("https://www.instagram.com/" + path + "/C" + mediaId.substring(mediaId.length() - 4) + "/")
                .build();
    }

    /**
     * Her own caption: blank lines first (not a line), then a first line carrying an @handle and a
     * link, then a second line. Only "Post NNNN: my morning routine" may reach the payload (ADR
     * 2026-09-26-creator-own-caption-to-meera). The UNKNOWN-type post has no caption at all.
     */
    private static String captionFor(String mediaId) {
        if (mediaId.endsWith("0401")) {
            return null;
        }
        return "\n \t\nPost " + mediaId.substring(mediaId.length() - 4)
                + ": my morning routine @brand.partner https://linktr.ee/someone\n"
                + "second line never sent #ad";
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
        // Slice 2: June posts, before the profile's 90-day window (27 Jun) so the profile ignores
        // them, but inside each September post's own 90-day "as of the post" baseline window.
        int[] juneDays = {12, 14, 15, 16, 18, 19, 20, 22, 23, 25};
        for (int i = 0; i < juneDays.length; i++) {
            Instant postedAt = LocalDate.of(2026, 6, juneDays[i]).atTime(19, 0).atZone(IST).toInstant();
            rows.add(post(String.format("180400000000000%02d", i + 1), "IMAGE", postedAt, threeDays, 1800 + 100L * i, 2500, 90L));
        }
        return rows;
    }

    private static final String CONVERSATION = "01HWIRESHAPECONVERSATION";
    private static final String MESSAGE = "01HWIRESHAPEMESSAGE00000A";
    private static final String CHALLENGE = "01HWIRESHAPECHALLENGE000A";

    private static CreatorRecommendation rec(
            CreatorRecommendationSource source,
            String ref,
            LocalDate day,
            LocalDate matchUntil,
            ChallengeDayType type,
            String label,
            LocalTime from,
            LocalTime to,
            Instant createdAt) {
        return CreatorRecommendation.open(
                "01HWIREREC" + source.name().charAt(0) + ref.substring(ref.length() - 1),
                USER,
                PROFILE_ID,
                source,
                ref,
                source == CreatorRecommendationSource.CHALLENGE ? null : CONVERSATION,
                day,
                matchUntil,
                type,
                label,
                from,
                to,
                null,
                null,
                null,
                null,
                "meera-2026.09.25.15",
                null,
                createdAt);
    }

    /**
     * Slice 2 recommendations, all OPEN, evaluated for real by {@link
     * CreatorRecommendationOutcomeService} at {@link #NOW}: five decided plan lines (three
     * followed and settled, one filled by a photo when a carousel was planned, one missed) plus
     * one still ahead; one challenge day followed; one script card filled by a photo.
     */
    private static List<CreatorRecommendation> recommendations() {
        Instant planned = LocalDate.of(2026, 8, 31).atTime(20, 0).atZone(IST).toInstant();
        LocalTime morning = LocalTime.of(5, 0);
        LocalTime noon = LocalTime.of(12, 0);
        List<CreatorRecommendation> recs = new ArrayList<>();
        CreatorRecommendationSource plan = CreatorRecommendationSource.PLAN_MY_WEEK;
        recs.add(rec(plan, MESSAGE + ":0", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2), ChallengeDayType.REEL, "weekday morning", morning, noon, planned));
        recs.add(rec(plan, MESSAGE + ":1", LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 3), ChallengeDayType.CAROUSEL, "weekday evening", null, null, planned));
        recs.add(rec(plan, MESSAGE + ":2", LocalDate.of(2026, 9, 3), LocalDate.of(2026, 9, 4), ChallengeDayType.REEL, "weekday evening", null, null, planned));
        recs.add(rec(plan, MESSAGE + ":3", LocalDate.of(2026, 9, 4), LocalDate.of(2026, 9, 5), ChallengeDayType.POST, null, null, null, planned));
        recs.add(rec(plan, MESSAGE + ":4", LocalDate.of(2026, 9, 8), LocalDate.of(2026, 9, 9), ChallengeDayType.REEL, "weekday morning", morning, noon, planned));
        recs.add(rec(plan, MESSAGE + ":5", LocalDate.of(2026, 9, 26), LocalDate.of(2026, 9, 27), ChallengeDayType.REEL, null, null, null, planned));
        recs.add(
                rec(
                        CreatorRecommendationSource.CHALLENGE,
                        CHALLENGE + ":0",
                        LocalDate.of(2026, 9, 5),
                        LocalDate.of(2026, 9, 6),
                        ChallengeDayType.CAROUSEL,
                        "evening",
                        LocalTime.of(17, 0),
                        LocalTime.of(22, 0),
                        ist(5, 6, 0)));
        recs.add(
                rec(
                        CreatorRecommendationSource.SCRIPT_CARD,
                        MESSAGE + ":6",
                        null,
                        LocalDate.of(2026, 9, 21),
                        ChallengeDayType.REEL,
                        null,
                        null,
                        null,
                        ist(14, 10, 0)));
        return recs;
    }

    /** An in-memory {@link CreatorRecommendationRepository} over {@code store}: rows mutate in place. */
    static CreatorRecommendationRepository recommendationRepository(List<CreatorRecommendation> store) {
        CreatorRecommendationRepository repo = mock(CreatorRecommendationRepository.class);
        Comparator<CreatorRecommendation> order =
                Comparator.comparing(CreatorRecommendation::getCreatedAt).thenComparing(CreatorRecommendation::getId);
        when(repo.findByCreatorProfileIdAndStatusInOrderByCreatedAtAscIdAsc(any(), any()))
                .thenAnswer(
                        inv -> {
                            Collection<CreatorRecommendationStatus> statuses = inv.getArgument(1);
                            return store.stream()
                                    .filter(r -> r.getCreatorProfileId().equals(inv.getArgument(0)))
                                    .filter(r -> statuses.contains(r.getStatus()))
                                    .sorted(order)
                                    .toList();
                        });
        when(repo.findClaimedMediaIds(any()))
                .thenAnswer(
                        inv ->
                                store.stream()
                                        .filter(r -> r.getCreatorProfileId().equals(inv.getArgument(0)))
                                        .map(CreatorRecommendation::getMatchedMediaId)
                                        .filter(java.util.Objects::nonNull)
                                        .toList());
        when(repo.findByCreatorProfileIdAndCreatedAtGreaterThanEqualOrderByCreatedAtAscIdAsc(any(), any()))
                .thenAnswer(
                        inv -> {
                            Instant since = inv.getArgument(1);
                            return store.stream()
                                    .filter(r -> r.getCreatorProfileId().equals(inv.getArgument(0)))
                                    .filter(r -> !r.getCreatedAt().isBefore(since))
                                    .sorted(order)
                                    .toList();
                        });
        when(repo.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        // The per-row outcome writer re-reads each row by id and saves it: rows mutate in place.
        when(repo.findById(any()))
                .thenAnswer(inv -> store.stream().filter(r -> r.getId().equals(inv.getArgument(0))).findFirst());
        when(repo.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        return repo;
    }

    static CreatorRecommendationOutcomeService outcomeService(
            CreatorRecommendationRepository repo, MediaMetricsRepository media) {
        return new CreatorRecommendationOutcomeService(
                repo, media, new com.influora.service.creatorcopilot.CreatorRecommendationOutcomeWriter(repo));
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
                new CreatorIntelligenceService(
                        media,
                        profiles,
                        new ConnectedInstagramAccount(tokens),
                        tokens,
                        outcomeService(recommendationRepository(recommendations()), media));
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
        // Slice 2 non-vacuity: at least one group carries a median, and one is below the floor.
        assertTrue(result.followedRecommendations().stream().anyMatch(g -> g.medianReachVsUsual() != null));
        assertTrue(result.followedRecommendations().stream().anyMatch(g -> g.medianReachVsUsual() == null));

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
        // Only the cleaned FIRST line of her own caption reaches the payload: never a later line,
        // an @handle or a link, and a post with no caption has no caption_first_line key.
        assertTrue(fresh.contains("\"caption_first_line\" : \"Post "), "the first line must be sent");
        assertTrue(!fresh.contains("never sent"), "a second caption line leaked");
        assertTrue(!fresh.contains("@brand.partner"), "an @handle leaked");
        assertTrue(!fresh.contains("linktr.ee"), "a link leaked");
        assertTrue(
                result.weakPosts().stream()
                        .filter(p -> p.postId().endsWith("0401"))
                        .allMatch(p -> p.captionFirstLine() == null),
                "a post without a caption carries none");
    }
}
