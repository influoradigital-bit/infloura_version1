package com.influora.service.creatorcopilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.MediaMetric;
import com.influora.domain.entity.MetaOAuthToken;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.MediaMetricsRepository;
import com.influora.repository.MetaOAuthTokenRepository;
import com.influora.service.creatorcopilot.CreatorIntelligenceProfile.PostStat;
import com.influora.service.meera.tool.creator.GetMyContentPatternsExecutor;
import com.influora.web.dto.meera.CreatorToolDtos.GetMyContentPatternsResult;
import com.influora.web.dto.meera.CreatorToolDtos.PostReading;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * ADR wiki/decisions/2026-09-26-creator-own-caption-to-meera.md -- the first line of the
 * creator's OWN caption on her best and weak posts: how it is cleaned ({@link CreatorOwnCaption}),
 * that only her own rows carry it, that a missing caption is null, and that no log line ever
 * carries it.
 */
class CreatorOwnCaptionTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Instant NOW = LocalDate.of(2026, 9, 25).atTime(18, 0).atZone(IST).toInstant();
    private static final String USER = "01HCAPTIONCREATORUSER00A";
    private static final String PROFILE_ID = "01HCAPTIONPROFILE00000A";
    private static final String OTHER_PROFILE_ID = "01HOTHERCREATORPROFILE0B";
    private static final String ACCOUNT = "17841400000000077";

    /** A distinctive own-caption first line, so a leak into a log is unmistakable. */
    private static final String OWN_LINE = "Zebra-kite monsoon routine";

    // ---------------------------------------------------------------------------------------------
    // Cleaning
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("first line only; @handles, links and e-mails removed; whitespace collapsed")
    void firstLineOnlyStripped() {
        assertEquals(
                "Morning routine with #skincare",
                CreatorOwnCaption.firstLine(
                        "Morning   routine\twith @glow.brand #skincare https://shop.example.com/x?utm=1\n"
                                + "Second line must never be sent"));
        assertEquals("Shop the look at", CreatorOwnCaption.firstLine("Shop the look at www.example.in/abc"));
        assertEquals("Links in bio", CreatorOwnCaption.firstLine("Links in bio linktr.ee/someone"));
        assertEquals("Write to me at", CreatorOwnCaption.firstLine("Write to me at me.creator@gmail.com"));
        assertEquals("Collab with and", CreatorOwnCaption.firstLine("Collab with @a_b.c and @x"));
        assertEquals("Price 1.5 lakh, e.g. this one", CreatorOwnCaption.firstLine("Price 1.5 lakh, e.g. this one"));
    }

    @Test
    @DisplayName(
            "first line only: leading blank lines are not a line, but a spacer or handle-only first"
                    + " line sends nothing (never line 2); CRLF, CR and Unicode line breaks all split")
    void strictlyTheFirstLine() {
        assertEquals("Real first line", CreatorOwnCaption.firstLine("\n \n\tReal first line\nnext"));
        assertNull(CreatorOwnCaption.firstLine("\n \n.\n...\n@someone\nReal first line\nnext"));
        assertNull(CreatorOwnCaption.firstLine("@brand\nMy monsoon skincare routine"));
        assertEquals("One", CreatorOwnCaption.firstLine("One\r\nTwo"));
        assertEquals("One", CreatorOwnCaption.firstLine("One\rTwo"));
        assertEquals("One", CreatorOwnCaption.firstLine("One Two"));
        assertEquals("One", CreatorOwnCaption.firstLine("One\u0085Two"));
        assertEquals("🌧️", CreatorOwnCaption.firstLine("🌧️\nrain"), "an emoji says something");
    }

    @Test
    @DisplayName(
            "control characters (C0 and C1) become spaces; invisible bidi / zero-width characters vanish;"
                    + " the joiners inside an emoji or an Indic word are kept")
    void removesInvisibleCharacters() {
        assertEquals("Hello world", CreatorOwnCaption.firstLine("Hello‮​ world\u0007"));
        assertEquals("Hi there 31m", CreatorOwnCaption.firstLine("Hi\u0090there\u009b31m"), "C1 controls");
        assertEquals(
                "क्‌ष word",
                CreatorOwnCaption.firstLine("क्‌ष word"),
                "a ZWNJ inside a Devanagari word is kept");
        String family = "👩‍💻 coder life";
        assertEquals(family, CreatorOwnCaption.firstLine(family));
    }

    @Test
    @DisplayName("at most 100 code points with an ellipsis; never cut inside a character the reader sees as one")
    void truncatesTo100CodePoints() {
        String longLine = "a".repeat(150);
        String out = CreatorOwnCaption.firstLine(longLine);
        assertEquals(100, out.codePointCount(0, out.length()));
        assertTrue(out.endsWith("…"));

        String emojis = "😀".repeat(120); // 120 code points, 240 chars
        String cut = CreatorOwnCaption.firstLine(emojis);
        assertEquals(100, cut.codePointCount(0, cut.length()));
        assertTrue(cut.endsWith("…"));
        for (int i = 0; i < cut.length() - 1; i++) {
            if (Character.isHighSurrogate(cut.charAt(i))) {
                assertTrue(Character.isLowSurrogate(cut.charAt(i + 1)), "split surrogate at " + i);
            }
        }

        String exact = "b".repeat(100);
        assertEquals(exact, CreatorOwnCaption.firstLine(exact), "exactly 100 is kept whole");

        // A character the reader sees as ONE (a flag, a ZWJ emoji, a skin tone, a consonant with
        // its vowel sign) is never cut apart: the cut backs off to the start of that character.
        String flag = "a".repeat(98) + "🇮🇳" + "xx"; // 🇮🇳 at code points 98-99
        assertEquals("a".repeat(98) + "…", CreatorOwnCaption.firstLine(flag), "no lone regional indicator");
        String family = "a".repeat(97) + "👩‍💻" + "zz";
        assertEquals("a".repeat(97) + "…", CreatorOwnCaption.firstLine(family), "no half ZWJ sequence");
        String syllable = "a".repeat(98) + "कि" + "zz";
        assertEquals("a".repeat(98) + "…", CreatorOwnCaption.firstLine(syllable), "vowel sign stays with its consonant");
    }

    /**
     * The shared fixture influora-ai also runs against its twin cleaner
     * ({@code loop._caption_first_line_for_model}), so the two cannot drift apart again. Its
     * expected values are written by hand, never produced by either cleaner.
     */
    @Test
    @DisplayName("shared Java/Python fixture: every case in creator-own-caption-cases.json")
    void sharedFixtureWithThePythonTwin() throws Exception {
        com.fasterxml.jackson.databind.JsonNode root;
        try (java.io.InputStream in = CreatorOwnCaptionTest.class.getResourceAsStream("/creator-own-caption-cases.json")) {
            assertNotNull(in, "creator-own-caption-cases.json must be on the test classpath");
            root = new ObjectMapper().readTree(in);
        }
        List<String> failures = new ArrayList<>();
        int count = 0;
        for (com.fasterxml.jackson.databind.JsonNode c : root.get("cases")) {
            count++;
            String expected = c.get("expected").isNull() ? null : c.get("expected").asText();
            String actual = CreatorOwnCaption.firstLine(c.get("input").asText());
            if (!java.util.Objects.equals(expected, actual)) {
                failures.add(c.get("name").asText() + ": expected <" + expected + "> but was <" + actual + ">");
            }
        }
        assertTrue(count >= 30, "non-vacuity: the fixture must carry its cases, found " + count);
        assertTrue(failures.isEmpty(), failures.size() + " fixture case(s) differ:\n" + String.join("\n", failures));
    }

    @Test
    @DisplayName("null when the caption is missing, blank, or has nothing left after cleaning")
    void nullWhenNothing() {
        assertNull(CreatorOwnCaption.firstLine(null));
        assertNull(CreatorOwnCaption.firstLine(""));
        assertNull(CreatorOwnCaption.firstLine("  \n\t "));
        assertNull(CreatorOwnCaption.firstLine("@brand https://x.com/y\n.\n-"));
    }

    // ---------------------------------------------------------------------------------------------
    // Own rows only
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("ownCaptionFirstLine: her own row gets its line; another creator's row never does")
    void ownRowOnly() {
        CreatorProfile owner = mock(CreatorProfile.class);
        when(owner.getId()).thenReturn(PROFILE_ID);
        assertEquals(OWN_LINE, CreatorIntelligenceService.ownCaptionFirstLine(owner, row("m1", PROFILE_ID, 500, OWN_LINE)));
        assertNull(CreatorIntelligenceService.ownCaptionFirstLine(owner, row("m2", OTHER_PROFILE_ID, 500, "Not hers")));
        assertNull(CreatorIntelligenceService.ownCaptionFirstLine(owner, row("m3", null, 500, "No owner")));
        assertNull(CreatorIntelligenceService.ownCaptionFirstLine(owner, row("m4", PROFILE_ID, 500, null)));
    }

    @Test
    @DisplayName(
            "service + executor: her best post carries its first line, a row of another creator that"
                    + " the query wrongly returned never does, and a missing caption is omitted")
    void endToEndOwnOnlyAndNullWhenMissing() throws Exception {
        GetMyContentPatternsResult result = run(false);

        List<PostReading> all = Stream.concat(result.bestPosts().stream(), result.weakPosts().stream()).toList();
        PostReading own = find(all, "own-best");
        assertEquals(OWN_LINE, own.captionFirstLine());

        PostReading foreign = find(all, "foreign-best");
        assertNull(foreign.captionFirstLine(), "another creator's caption must never be attached");

        PostReading noCaption = find(all, "own-weak-nocaption");
        assertNull(noCaption.captionFirstLine());
        String json = new ObjectMapper().writeValueAsString(result);
        assertFalse(json.contains("Other creator's words"), "another creator's caption reached the payload");
        assertFalse(json.contains("second line"), "a later caption line reached the payload");
        assertTrue(json.contains("\"caption_first_line\":\"" + OWN_LINE + "\""));
    }

    @Test
    @DisplayName("no log line carries the caption line: every logger at TRACE, including the failure path")
    void neverLogged() {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Level before = root.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        root.setLevel(Level.TRACE);
        try {
            GetMyContentPatternsResult ok = run(false);
            GetMyContentPatternsResult failedOutcomes = run(true); // exercises the warn path
            // Logging the results or the profile must not leak it either.
            LoggerFactory.getLogger(CreatorOwnCaptionTest.class).info("result {} / {}", ok, failedOutcomes);
            LoggerFactory.getLogger(CreatorOwnCaptionTest.class).info("posts {}", ok.bestPosts());
        } finally {
            root.detachAppender(appender);
            root.setLevel(before);
        }

        List<String> lines = new ArrayList<>();
        for (ILoggingEvent event : appender.list) {
            lines.add(event.getFormattedMessage());
            if (event.getThrowableProxy() != null) {
                lines.add(event.getThrowableProxy().getMessage());
            }
        }
        for (String line : lines) {
            assertFalse(line != null && line.contains("Zebra-kite"), "caption text in a log line: " + line);
        }
        assertTrue(lines.stream().anyMatch(l -> l != null && l.contains("recommendation outcomes not evaluated")),
                "non-vacuity: the failure path must have logged");
        assertTrue(lines.stream().anyMatch(l -> l != null && l.contains("<redacted>")),
                "non-vacuity: the result was logged, redacted");
    }

    @Test
    @DisplayName("PostStat and PostReading toString redact the caption line")
    void toStringRedacts() {
        PostStat stat =
                new PostStat(
                        "p", null, NOW, "w", null, 1, 1.0, null,
                        new CreatorIntelligenceProfile.Evidence(com.influora.domain.enums.EvidenceType.CREATOR_POST_DATA, List.of("p"), 10),
                        OWN_LINE);
        assertFalse(stat.toString().contains("Zebra"));
        assertTrue(stat.toString().contains("captionFirstLine=<redacted>"));
        GetMyContentPatternsResult r =
                GetMyContentPatternsExecutor.render(
                        new CreatorIntelligenceProfile(
                                true, null, true, 10, 0, 10, 10, 90, NOW, List.of(), List.of(stat), List.of(), List.of(), List.of()),
                        Locale.forLanguageTag("en-IN"));
        assertEquals(OWN_LINE, r.bestPosts().get(0).captionFirstLine());
        assertFalse(r.toString().contains("Zebra"));
    }

    @Test
    @DisplayName(
            "defence in depth: the service record never serialises the caption line (only the"
                    + " creator tool DTO PostReading carries it on the wire)")
    void serviceRecordNeverSerialisesIt() throws Exception {
        PostStat stat =
                new PostStat(
                        "p", null, NOW, "w", null, 1, 1.0, null,
                        new CreatorIntelligenceProfile.Evidence(com.influora.domain.enums.EvidenceType.CREATOR_POST_DATA, List.of("p"), 10),
                        OWN_LINE);
        CreatorIntelligenceProfile profile =
                new CreatorIntelligenceProfile(
                        true, null, true, 10, 0, 10, 10, 90, NOW, List.of(), List.of(stat), List.of(stat), List.of(), List.of());
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        String json = mapper.writeValueAsString(profile);
        assertTrue(json.contains("\"postId\":\"p\""), "non-vacuity: the post itself is serialised: " + json);
        assertFalse(json.contains("Zebra"), "the caption line reached JSON through the service record: " + json);
        assertFalse(json.contains("captionFirstLine"), json);
        assertEquals(OWN_LINE, stat.captionFirstLine(), "the accessor still gives the executor its value");
    }

    // ---------------------------------------------------------------------------------------------
    // fixtures
    // ---------------------------------------------------------------------------------------------

    private static PostReading find(List<PostReading> posts, String id) {
        PostReading found = posts.stream().filter(p -> p.postId().equals(id)).findFirst().orElse(null);
        assertNotNull(found, id + " must be listed as a best or weak post; got " + posts.stream().map(PostReading::postId).toList());
        return found;
    }

    private static MediaMetric row(String mediaId, String profileId, long reach, String caption) {
        Instant postedAt = NOW.minus(Duration.ofDays(5 + Math.abs(mediaId.hashCode() % 40)));
        return MediaMetric.builder()
                .id("01HCAP" + mediaId)
                .mediaId(mediaId)
                .creatorProfileId(profileId)
                .igAccountId(ACCOUNT)
                .platform("INSTAGRAM")
                .mediaType("IMAGE")
                .postedAt(postedAt)
                .time(postedAt.plus(Duration.ofDays(3)))
                .fetchedAt(postedAt.plus(Duration.ofDays(3)))
                .reach(reach)
                .impressions(reach * 2)
                .engagement(reach / 10)
                .caption(caption)
                .build();
    }

    private static List<MediaMetric> rows() {
        List<MediaMetric> rows = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            rows.add(row("own-mid-" + i, PROFILE_ID, 1000 + i, "Ordinary post " + i));
        }
        rows.add(row("own-best", PROFILE_ID, 9000, "\n" + OWN_LINE + " @brand https://example.com\nsecond line"));
        // A row the query should never have returned: another creator's post. Its numbers are
        // counted (the query is the account boundary), but its caption must never be attached.
        rows.add(row("foreign-best", OTHER_PROFILE_ID, 8000, "Other creator's words"));
        rows.add(row("own-weak-nocaption", PROFILE_ID, 100, null));
        return rows;
    }

    private static GetMyContentPatternsResult run(boolean outcomesFail) {
        MediaMetricsRepository media = mock(MediaMetricsRepository.class);
        CreatorProfileRepository profiles = mock(CreatorProfileRepository.class);
        MetaOAuthTokenRepository tokens = mock(MetaOAuthTokenRepository.class);
        CreatorRecommendationOutcomeService outcomes = mock(CreatorRecommendationOutcomeService.class);

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
        if (outcomesFail) {
            doThrow(new IllegalStateException("boom")).when(outcomes).evaluate(any(), any(), anyBoolean(), any());
        }

        CreatorIntelligenceService service =
                new CreatorIntelligenceService(media, profiles, new ConnectedInstagramAccount(tokens), tokens, outcomes);
        CreatorIntelligenceProfile computed = service.profile(USER, NOW);
        assertTrue(computed.enoughData(), "fixture must have enough settled posts");
        return GetMyContentPatternsExecutor.render(computed, Locale.forLanguageTag("en-IN"));
    }
}
