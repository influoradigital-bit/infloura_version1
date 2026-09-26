package com.influora.service.meera.tool.creator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.domain.enums.ChallengeDayType;
import com.influora.domain.enums.CreatorRecommendationSource;
import com.influora.domain.enums.EvidenceType;
import com.influora.service.CreatorAgentPreferencesService;
import com.influora.service.creatorcopilot.CreatorIntelligenceProfile;
import com.influora.service.creatorcopilot.CreatorIntelligenceProfile.BaselineStat;
import com.influora.service.creatorcopilot.CreatorIntelligenceProfile.BeatsOn;
import com.influora.service.creatorcopilot.CreatorIntelligenceProfile.Evidence;
import com.influora.service.creatorcopilot.CreatorIntelligenceProfile.FollowedStat;
import com.influora.service.creatorcopilot.CreatorIntelligenceProfile.GroupStat;
import com.influora.service.creatorcopilot.CreatorIntelligenceProfile.Metric;
import com.influora.service.creatorcopilot.CreatorIntelligenceProfile.PatternKind;
import com.influora.service.creatorcopilot.CreatorIntelligenceProfile.PostStat;
import com.influora.service.creatorcopilot.CreatorIntelligenceService;
import com.influora.web.dto.creator.CreatorAgentDtos.PreferencesResponse;
import com.influora.web.dto.meera.CreatorToolDtos.FollowedGroup;
import com.influora.web.dto.meera.CreatorToolDtos.GetMyContentPatternsResult;
import com.influora.web.dto.meera.CreatorToolDtos.PostReading;
import com.influora.web.dto.meera.CreatorToolDtos.WorkingPattern;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Meera intelligence v1 (T16) -- {@link GetMyContentPatternsExecutor} renders every number into a
 * locale string in Java, so the model never receives a raw number it could divide or rank itself.
 */
@ExtendWith(MockitoExtension.class)
class GetMyContentPatternsExecutorTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Locale EN_IN = Locale.forLanguageTag("en-IN");
    private static final String USER = "01HCREATORUSER1234567A";

    @Mock private CreatorIntelligenceService intelligenceService;
    @Mock private CreatorAgentPreferencesService preferencesService;

    private static Evidence ev(List<String> ids, Integer baselineN) {
        return new Evidence(EvidenceType.CREATOR_POST_DATA, ids, baselineN);
    }

    private static CreatorIntelligenceProfile profile() {
        Instant posted = LocalDate.of(2026, 9, 12).atTime(19, 40).atZone(IST).toInstant();
        Instant weakPosted = LocalDate.of(2026, 9, 14).atTime(8, 5).atZone(IST).toInstant();
        List<String> ids = List.of("p1", "p2", "p3", "p4", "p5", "p6", "p7", "p8", "p9", "p10", "p11", "p12");
        return new CreatorIntelligenceProfile(
                true,
                null,
                true,
                12,
                2,
                12,
                10,
                90,
                LocalDate.of(2026, 9, 24).atTime(23, 30).atZone(IST).toInstant(),
                List.of(
                        new BaselineStat(Metric.REACH, 12400.0, ev(ids, null)),
                        new BaselineStat(Metric.VIEWS, 1840.5, ev(ids, null)),
                        new BaselineStat(Metric.ENGAGEMENT_RATE, 0.0615, ev(ids.subList(0, 10), null))),
                List.of(
                        new PostStat(
                                "p1", ChallengeDayType.REEL, posted, "weekend evening",
                                "https://www.instagram.com/reel/p1/", 29760, 2.4, 0.079, ev(List.of("p1"), 12))),
                List.of(
                        new PostStat("p2", null, weakPosted, "weekday morning", null, 5580, 0.45, null, ev(List.of("p2"), 12)),
                        new PostStat("p3", ChallengeDayType.POST, weakPosted, "weekday morning", null, 12400, 1.0, 0.05, ev(List.of("p3"), 12))),
                List.of(
                        new GroupStat(
                                PatternKind.POST_TYPE, "Reels and videos", 8, 17608.0, 1.42, 0.069, 1.11,
                                List.of(BeatsOn.REACH), ev(ids.subList(0, 8), null))),
                List.of(
                        new FollowedStat(
                                CreatorRecommendationSource.PLAN_MY_WEEK, 6, 4, 17.5, ev(List.of("p4", "p5", "p6"), null)),
                        new FollowedStat(
                                CreatorRecommendationSource.CHALLENGE, 3, 1, null, ev(List.of(), null))));
    }

    @Test
    @DisplayName(
            "T16: renders locale strings (\"12,400\", \"6.2%\", \"+140%\", \"-55%\", IST date/time) and"
                    + " no raw fractional number reaches the model")
    void rendersLocaleStringsAndNoRawNumbersForModel() throws Exception {
        GetMyContentPatternsResult r = GetMyContentPatternsExecutor.render(profile(), EN_IN);

        assertEquals(com.influora.common.Rendered.date(LocalDate.of(2026, 9, 24), EN_IN), r.asOf());
        assertEquals(com.influora.common.Rendered.date(LocalDate.of(2026, 9, 12), EN_IN), r.bestPosts().get(0).postedDate());
        assertEquals("REACH", r.baseline().get(0).metric());
        assertEquals("12,400", r.baseline().get(0).median());
        assertEquals("1,841", r.baseline().get(1).median(), "counts round half-up");
        assertEquals("6.2%", r.baseline().get(2).median());

        PostReading best = r.bestPosts().get(0);
        assertEquals("REEL", best.postType());
        assertEquals("19:40", best.postedTime());
        assertEquals("weekend evening", best.window());
        assertEquals("29,760", best.reach());
        assertEquals("+140%", best.reachVsUsual());
        assertEquals("7.9%", best.engagementRate());
        assertEquals(12, best.evidence().baselineSampleSize());

        PostReading weak = r.weakPosts().get(0);
        assertEquals("OTHER", weak.postType(), "an unknown media type is OTHER, never guessed");
        assertEquals("-55%", weak.reachVsUsual());
        assertEquals("08:05", weak.postedTime());
        assertNull(weak.engagementRate(), "unknown interactions must not render as 0.0%");
        assertEquals("+0%", r.weakPosts().get(1).reachVsUsual());

        WorkingPattern group = r.whatWorks().get(0);
        assertEquals("POST_TYPE", group.kind());
        assertEquals("+42%", group.reachVsUsual());
        assertEquals("+11%", group.engagementVsUsual());
        assertEquals("6.9%", group.medianEngagementRate());
        assertEquals("17,608", group.medianReach());
        assertEquals(List.of("REACH"), group.beatsOn());
        assertEquals(8, group.evidence().sampleSize());

        // Structural half: no record component on the wire is a floating-point or long number --
        // only whole counts (int) and strings. Returning a raw double anywhere turns this red.
        List<String> numeric = new ArrayList<>();
        findNonIntNumbers(GetMyContentPatternsResult.class, new HashSet<>(), numeric);
        assertTrue(numeric.isEmpty(), "raw numbers on the wire: " + numeric);

        // And on the serialised JSON: every number node is an integral count.
        JsonNode json = new ObjectMapper().valueToTree(r);
        List<String> fractional = new ArrayList<>();
        walk(json, "$", fractional);
        assertTrue(fractional.isEmpty(), "non-integral numbers in the JSON: " + fractional);
    }

    @Test
    @DisplayName(
            "slice 2: followed_recommendations renders per source, the median as a signed whole"
                    + " percentage, null below the 3-post floor, and evidence = the posts it rests on")
    void rendersFollowedRecommendations() {
        GetMyContentPatternsResult r = GetMyContentPatternsExecutor.render(profile(), EN_IN);

        assertEquals(2, r.followedRecommendations().size());
        FollowedGroup plan = r.followedRecommendations().get(0);
        assertEquals("PLAN_MY_WEEK", plan.source());
        assertEquals(6, plan.recommended());
        assertEquals(4, plan.followed());
        assertEquals("+18%", plan.medianReachVsUsual(), "17.5 rounds half-up");
        assertEquals("CREATOR_POST_DATA", plan.evidence().type());
        assertEquals(3, plan.evidence().sampleSize());
        assertEquals(List.of("p4", "p5", "p6"), plan.evidence().postIds());

        FollowedGroup challenge = r.followedRecommendations().get(1);
        assertEquals("CHALLENGE", challenge.source());
        assertNull(challenge.medianReachVsUsual(), "below the floor there is no median");
        assertEquals(0, challenge.evidence().sampleSize());

        assertEquals("-4%", GetMyContentPatternsExecutor.signedPercent(-4.0, EN_IN));
        assertEquals("+0%", GetMyContentPatternsExecutor.signedPercent(0.0, EN_IN));
        assertEquals("-3%", GetMyContentPatternsExecutor.signedPercent(-2.5, EN_IN), "half-up away from zero");
    }

    @Test
    @DisplayName("the thin-data note names how many settled posts there are and how many are needed")
    void thinDataNote() {
        CreatorIntelligenceProfile thin =
                new CreatorIntelligenceProfile(
                        true, null, false, 4, 1, 4, 10, 90, null, List.of(), List.of(), List.of(), List.of(), List.of());
        GetMyContentPatternsResult r = GetMyContentPatternsExecutor.render(thin, EN_IN);
        assertFalse(r.enoughData());
        assertEquals("Not enough settled posts yet: 4 of the 10 needed from the last 90 days.", r.note());
        assertTrue(r.baseline().isEmpty());
        assertTrue(r.bestPosts().isEmpty());
        assertTrue(r.weakPosts().isEmpty());
        assertTrue(r.whatWorks().isEmpty());
    }

    @Test
    @DisplayName("NOT_CONNECTED carries no post data and no note")
    void notConnected() {
        CreatorIntelligenceProfile none =
                new CreatorIntelligenceProfile(
                        false, "NOT_CONNECTED", false, 0, 0, 0, 10, 90, null, List.of(), List.of(), List.of(), List.of(), List.of());
        GetMyContentPatternsResult r = GetMyContentPatternsExecutor.render(none, EN_IN);
        assertFalse(r.available());
        assertEquals("NOT_CONNECTED", r.reason());
        assertNull(r.note());
        assertNull(r.asOf());
    }

    @Test
    @DisplayName("the cap note appears when more settled posts exist than were used")
    void capNote() {
        CreatorIntelligenceProfile capped =
                new CreatorIntelligenceProfile(
                        true, null, true, 180, 0, 150, 10, 90, null, List.of(), List.of(), List.of(), List.of(), List.of());
        assertEquals(
                "Based on the 150 most recent of 180 settled posts from the last 90 days.",
                GetMyContentPatternsExecutor.render(capped, EN_IN).note());
    }

    @Test
    @DisplayName("execute: now is decided by the executor, the input map is ignored, locale comes from preferences")
    void executeUsesPreferencesLocale() {
        when(intelligenceService.profile(eq(USER), any(Instant.class))).thenReturn(profile());
        when(preferencesService.getOrCreatePreferences(USER))
                .thenReturn(
                        new PreferencesResponse(
                                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, "INR", List.of(), List.of(), 0,
                                "en-IN", "FRIENDLY", null, null, "Asia/Kolkata", List.of(), null, false, null,
                                true, "v1", false, null, false, 0, false));
        GetMyContentPatternsExecutor executor = new GetMyContentPatternsExecutor(intelligenceService, preferencesService);

        GetMyContentPatternsResult r = executor.execute(USER, Map.of("now", "2020-01-01T00:00:00Z"));

        assertEquals("12,400", r.baseline().get(0).median());
        verify(intelligenceService).profile(eq(USER), any(Instant.class));
    }

    private static void findNonIntNumbers(Type type, Set<Type> seen, List<String> out) {
        if (type instanceof ParameterizedType parameterized) {
            for (Type argument : parameterized.getActualTypeArguments()) {
                findNonIntNumbers(argument, seen, out);
            }
            return;
        }
        if (!(type instanceof Class<?> clazz) || !seen.add(clazz) || !clazz.isRecord()) {
            return;
        }
        for (RecordComponent component : clazz.getRecordComponents()) {
            Class<?> raw = component.getType();
            boolean numeric = Number.class.isAssignableFrom(raw)
                    || raw == double.class
                    || raw == float.class
                    || raw == long.class;
            if (numeric && raw != Integer.class) {
                out.add(clazz.getSimpleName() + "." + component.getName() + ":" + raw.getSimpleName());
            }
            findNonIntNumbers(component.getGenericType(), seen, out);
        }
    }

    private static void walk(JsonNode node, String path, List<String> out) {
        if (node.isNumber() && !node.isIntegralNumber()) {
            out.add(path + "=" + node);
        }
        node.fields().forEachRemaining(e -> walk(e.getValue(), path + "." + e.getKey(), out));
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                walk(node.get(i), path + "[" + i + "]", out);
            }
        }
    }

    @Test
    void onlyPlainInstagramPostLinksArePassedOn() {
        // Kabir L-2: the permalink reaches the model as trusted text and a later card puts it in
        // an href, so only a plain instagram.com post/reel/tv link survives.
        assertEquals(
                "https://www.instagram.com/reel/C0a_b-1/",
                GetMyContentPatternsExecutor.safePermalink("https://www.instagram.com/reel/C0a_b-1/"));
        assertEquals(
                "https://www.instagram.com/p/C0301",
                GetMyContentPatternsExecutor.safePermalink("https://www.instagram.com/p/C0301"));
        assertNull(GetMyContentPatternsExecutor.safePermalink("javascript:alert(1)"));
        assertNull(GetMyContentPatternsExecutor.safePermalink("http://www.instagram.com/p/C0301/"));
        assertNull(GetMyContentPatternsExecutor.safePermalink("https://evil.example/p/C0301/"));
        assertNull(GetMyContentPatternsExecutor.safePermalink("https://www.instagram.com/p/C0301/<b>x</b>"));
        assertNull(GetMyContentPatternsExecutor.safePermalink(null));
    }
}
