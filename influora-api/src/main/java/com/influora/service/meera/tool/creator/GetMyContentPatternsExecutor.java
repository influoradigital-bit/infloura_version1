package com.influora.service.meera.tool.creator;

import com.influora.common.Rendered;
import com.influora.service.CreatorAgentPreferencesService;
import com.influora.service.creatorcopilot.CreatorIntelligenceProfile;
import com.influora.service.creatorcopilot.CreatorIntelligenceProfile.BaselineStat;
import com.influora.service.creatorcopilot.CreatorIntelligenceProfile.FollowedStat;
import com.influora.service.creatorcopilot.CreatorIntelligenceProfile.GroupStat;
import com.influora.service.creatorcopilot.CreatorIntelligenceProfile.Metric;
import com.influora.service.creatorcopilot.CreatorIntelligenceProfile.PostStat;
import com.influora.service.creatorcopilot.CreatorIntelligenceService;
import com.influora.web.dto.creator.CreatorAgentDtos.PreferencesResponse;
import com.influora.web.dto.meera.CreatorToolDtos.BaselineMetric;
import com.influora.web.dto.meera.CreatorToolDtos.Evidence;
import com.influora.web.dto.meera.CreatorToolDtos.FollowedGroup;
import com.influora.web.dto.meera.CreatorToolDtos.GetMyContentPatternsResult;
import com.influora.web.dto.meera.CreatorToolDtos.PostReading;
import com.influora.web.dto.meera.CreatorToolDtos.WorkingPattern;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Meera intelligence v1 (spec &sect;4.3) -- {@code get_my_content_patterns}: what has worked for
 * THIS creator, from her own settled posts only. {@link CreatorIntelligenceService} does the
 * arithmetic; this class only renders it, the same split as {@code GetPlanMyWeekExecutor}.
 *
 * <p><b>Every number leaves as a string</b>, rendered here in the creator's locale ("12,400",
 * "6.2%", "+140%"), so the model quotes it and never computes a percentage, average or ranking of
 * its own. Dates and times are IST, the creator's calendar.
 *
 * <p>{@code now} is decided HERE ({@link Instant#now()}), never accepted from {@code input}.
 */
@Service
public class GetMyContentPatternsExecutor {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /**
     * The only permalink shape passed on (Kabir L-2, 2026-09-25). The value comes from Meta today,
     * but it reaches the model as trusted text and a later card puts it in an {@code href}, so
     * anything else (a {@code javascript:} URL, an overlong or odd string) is sent as null.
     */
    private static final java.util.regex.Pattern INSTAGRAM_PERMALINK =
            java.util.regex.Pattern.compile("^https://www\\.instagram\\.com/(p|reel|tv)/[A-Za-z0-9_-]{1,64}/?$");

    /** {@code permalink} if it is a plain Instagram post link, otherwise null. */
    static String safePermalink(String permalink) {
        return permalink != null && INSTAGRAM_PERMALINK.matcher(permalink).matches() ? permalink : null;
    }

    private final CreatorIntelligenceService intelligenceService;
    private final CreatorAgentPreferencesService preferencesService;

    public GetMyContentPatternsExecutor(
            CreatorIntelligenceService intelligenceService, CreatorAgentPreferencesService preferencesService) {
        this.intelligenceService = intelligenceService;
        this.preferencesService = preferencesService;
    }

    /**
     * @param input unused -- {@code get_my_content_patterns} takes no arguments; the parameter is
     *     kept so every creator read executor shares one dispatch signature at the controller
     */
    public GetMyContentPatternsResult execute(String creatorUserId, Map<String, Object> input) {
        return executeAt(creatorUserId, Instant.now());
    }

    /** {@link #execute} at a fixed instant -- the seam the wire-shape test uses for a stable fixture. */
    GetMyContentPatternsResult executeAt(String creatorUserId, Instant now) {
        CreatorIntelligenceProfile profile = intelligenceService.profile(creatorUserId, now);
        PreferencesResponse prefs = preferencesService.getOrCreatePreferences(creatorUserId);
        return render(profile, localeFor(prefs));
    }

    /** Renders the numeric profile onto the wire. Pure: no clock, no repository. */
    public static GetMyContentPatternsResult render(CreatorIntelligenceProfile profile, Locale locale) {
        return new GetMyContentPatternsResult(
                profile.available(),
                profile.reason(),
                profile.enoughData(),
                profile.settledPosts(),
                profile.unsettledPosts(),
                profile.minPostsNeeded(),
                profile.lookbackDays(),
                profile.asOf() == null ? null : Rendered.date(LocalDate.ofInstant(profile.asOf(), IST), locale),
                profile.baseline().stream().map(b -> toBaselineMetric(b, locale)).toList(),
                profile.bestPosts().stream().map(p -> toPostReading(p, locale)).toList(),
                profile.weakPosts().stream().map(p -> toPostReading(p, locale)).toList(),
                profile.whatWorks().stream().map(g -> toWorkingPattern(g, locale)).toList(),
                profile.followedRecommendations().stream().map(f -> toFollowedGroup(f, locale)).toList(),
                noteFor(profile));
    }

    private static String noteFor(CreatorIntelligenceProfile profile) {
        if (!profile.available()) {
            return null;
        }
        if (!profile.enoughData()) {
            return "Not enough settled posts yet: "
                    + profile.settledPosts()
                    + " of the "
                    + profile.minPostsNeeded()
                    + " needed from the last "
                    + profile.lookbackDays()
                    + " days.";
        }
        if (profile.postsUsed() < profile.settledPosts()) {
            return "Based on the "
                    + profile.postsUsed()
                    + " most recent of "
                    + profile.settledPosts()
                    + " settled posts from the last "
                    + profile.lookbackDays()
                    + " days.";
        }
        return null;
    }

    private static BaselineMetric toBaselineMetric(BaselineStat stat, Locale locale) {
        String median =
                stat.metric() == Metric.ENGAGEMENT_RATE ? percent(stat.median(), locale) : count(stat.median(), locale);
        return new BaselineMetric(stat.metric().name(), median, toEvidence(stat.evidence()));
    }

    private static PostReading toPostReading(PostStat post, Locale locale) {
        return new PostReading(
                post.postId(),
                post.type() == null ? "OTHER" : post.type().name(),
                Rendered.date(LocalDate.ofInstant(post.postedAt(), IST), locale),
                TIME.format(post.postedAt().atZone(IST)),
                post.window(),
                safePermalink(post.permalink()),
                count(post.reach(), locale),
                vsUsual(post.reachRatio(), locale),
                post.engagementRate() == null ? null : percent(post.engagementRate(), locale),
                toEvidence(post.evidence()),
                // Her own caption's first line, already cleaned and bounded by CreatorOwnCaption
                // (ADR 2026-09-26-creator-own-caption-to-meera). Passed through, never logged.
                post.captionFirstLine());
    }

    private static WorkingPattern toWorkingPattern(GroupStat group, Locale locale) {
        return new WorkingPattern(
                group.kind().name(),
                group.label(),
                group.posts(),
                count(group.medianReach(), locale),
                vsUsual(group.reachLift(), locale),
                group.medianEngagementRate() == null ? null : percent(group.medianEngagementRate(), locale),
                group.engagementLift() == null ? null : vsUsual(group.engagementLift(), locale),
                group.beatsOn().stream().map(Enum::name).toList(),
                toEvidence(group.evidence()));
    }

    private static FollowedGroup toFollowedGroup(FollowedStat stat, Locale locale) {
        return new FollowedGroup(
                stat.source().name(),
                stat.recommended(),
                stat.followed(),
                stat.medianReachVsUsualPct() == null ? null : signedPercent(stat.medianReachVsUsualPct(), locale),
                toEvidence(stat.evidence()));
    }

    private static Evidence toEvidence(CreatorIntelligenceProfile.Evidence evidence) {
        return new Evidence(
                evidence.type().name(),
                evidence.sampleSize(),
                List.copyOf(evidence.postIds()),
                evidence.baselineSampleSize());
    }

    /** A count, rounded half-up to a whole number and grouped: {@code 12400.5 -> "12,401"}. */
    static String count(double value, Locale locale) {
        return Rendered.money(BigDecimal.valueOf(value).setScale(0, RoundingMode.HALF_UP), locale);
    }

    /** A rate as a percentage with one decimal, half-up: {@code 0.0615 -> "6.2%"}. */
    static String percent(double rate, Locale locale) {
        NumberFormat format = NumberFormat.getNumberInstance(locale != null ? locale : Rendered.DEFAULT_LOCALE);
        format.setMinimumFractionDigits(1);
        format.setMaximumFractionDigits(1);
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format.format(BigDecimal.valueOf(rate).multiply(HUNDRED)) + "%";
    }

    /**
     * A ratio against the creator's usual as a signed whole percentage, half-up: {@code 2.4 ->
     * "+140%"}, {@code 0.45 -> "-55%"}, {@code 1.0 -> "+0%"}.
     */
    static String vsUsual(double ratio, Locale locale) {
        BigDecimal pct = BigDecimal.valueOf(ratio).subtract(BigDecimal.ONE).multiply(HUNDRED).setScale(0, RoundingMode.HALF_UP);
        String sign = pct.signum() < 0 ? "-" : "+";
        return sign + Rendered.money(pct.abs(), locale) + "%";
    }

    /**
     * A percentage that is already a percentage (not a ratio), signed and whole, half-up: {@code
     * 17.5 -> "+18%"}, {@code -4.0 -> "-4%"}, {@code 0 -> "+0%"}.
     */
    static String signedPercent(double pct, Locale locale) {
        BigDecimal whole = BigDecimal.valueOf(pct).setScale(0, RoundingMode.HALF_UP);
        String sign = whole.signum() < 0 ? "-" : "+";
        return sign + Rendered.money(whole.abs(), locale) + "%";
    }

    private static Locale localeFor(PreferencesResponse prefs) {
        String tag = prefs == null ? null : prefs.creatorLanguage();
        return tag == null || tag.isBlank() ? Rendered.DEFAULT_LOCALE : Locale.forLanguageTag(tag);
    }
}
