package com.influora.service.meera.tool.creator;

import com.influora.common.Rendered;
import com.influora.domain.entity.CreatorMetric;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.MediaMetric;
import com.influora.repository.CreatorMetricsRepository;
import com.influora.repository.MediaMetricsRepository;
import com.influora.service.CreatorAgentPreferencesService;
import com.influora.service.creatorcopilot.ConnectedInstagramAccount;
import com.influora.service.analytics.AnalyticsService;
import com.influora.service.scoring.CreatorTiers;
import com.influora.service.scoring.QualityScoreService.QualityScoreResult;
import com.influora.service.scoring.QualityScoreService;
import com.influora.web.dto.analytics.AnalyticsDtos.CreatorAccountInsightsResponse;
import com.influora.web.dto.creator.CreatorAgentDtos.PreferencesResponse;
import com.influora.web.dto.meera.CreatorToolDtos.AccountLast28Days;
import com.influora.web.dto.meera.CreatorToolDtos.GetMyMetricsResult;
import com.influora.web.dto.meera.CreatorToolDtos.MetricsResult;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;3.1/&sect;3.6) — {@code get_my_metrics}: the creator's
 * latest verified numbers, pre-rendered.
 *
 * <p><b>The point of this tool is the {@code connected} flag.</b> A creator who has never connected
 * Instagram has no {@link CreatorMetric} row, and the honest answer is "no verified numbers", not a
 * fabricated zero. That mistake has already been made once on this surface and fixed
 * ({@code MeeraContextService#buildMetricsSummary}'s gate-review note), so this executor repeats the
 * discipline: with no metric row every measured string is null and only {@code tier} — derived from
 * the creator's own self-reported follower count — survives, labelled {@code SELF_REPORTED}.
 */
@Service
public class GetMyMetricsExecutor {

    /**
     * Matches {@code ScoreCalculationJob}'s window exactly. {@link QualityScoreService#calculate}
     * scores consistency and posting frequency over whatever list it is given, so a different depth
     * here would produce a different quality score for the same creator depending on which caller
     * asked — the number the creator sees in Meera must be the number stored on her score row.
     */
    private static final int RECENT_MEDIA_LIMIT = 30;

    /** SPEC.md &sect;3.6 — the label for a follower count the creator typed in herself. */
    private static final String SELF_REPORTED = "SELF_REPORTED";

    private final CreatorAgentPreferencesService preferencesService;
    private final CreatorMetricsRepository creatorMetricsRepository;
    private final MediaMetricsRepository mediaMetricsRepository;
    private final QualityScoreService qualityScoreService;
    private final ConnectedInstagramAccount connectedAccount;
    private final AnalyticsService analyticsService;

    public GetMyMetricsExecutor(
            CreatorAgentPreferencesService preferencesService,
            CreatorMetricsRepository creatorMetricsRepository,
            MediaMetricsRepository mediaMetricsRepository,
            QualityScoreService qualityScoreService,
            ConnectedInstagramAccount connectedAccount,
            AnalyticsService analyticsService) {
        this.preferencesService = preferencesService;
        this.creatorMetricsRepository = creatorMetricsRepository;
        this.mediaMetricsRepository = mediaMetricsRepository;
        this.qualityScoreService = qualityScoreService;
        this.connectedAccount = connectedAccount;
        this.analyticsService = analyticsService;
    }

    /**
     * @param input unused — {@code get_my_metrics} takes no arguments; the parameter is kept so
     *     every creator read executor shares one dispatch signature at the controller
     */
    @Transactional(readOnly = true)
    public GetMyMetricsResult execute(String creatorUserId, Map<String, Object> input) {
        CreatorProfile profile = preferencesService.requireCreatorProfile(creatorUserId);
        PreferencesResponse prefs = preferencesService.getOrCreatePreferences(creatorUserId);
        Locale locale = localeFor(prefs);

        // Same query MeeraContextService uses for the creator context block, so the tool and Block B
        // can never quote two different "latest" snapshots in one conversation.
        // Narrowed to the account the creator is connected to today. A profile that has connected
        // more than one Instagram account holds rows from all of them (seen live: three), and
        // "latest row for this profile" quietly answered with whichever account was polled last.
        String igAccountId = connectedAccount.currentAccountId(profile.getId()).orElse(null);
        Optional<CreatorMetric> latestMetric =
                (igAccountId == null
                                ? creatorMetricsRepository.findByCreatorProfileIdOrderByTimeDesc(
                                        profile.getId(), PageRequest.of(0, 1))
                                : creatorMetricsRepository.findForAccountOrderByTimeDesc(
                                        profile.getId(), igAccountId, PageRequest.of(0, 1)))
                        .stream()
                        .findFirst();

        // Tier is available with or without a metric row: an explicit admin override wins, else it
        // is derived from the profile's follower count. CreatorTiers.derive can return "MEGA", which
        // is NOT a CreatorTier constant — this stays a String all the way to the wire and is never
        // passed to CreatorTier.valueOf.
        String tier =
                profile.getTierOverride() != null
                        ? profile.getTierOverride().name()
                        : CreatorTiers.derive(profile.getTotalFollowers());

        if (latestMetric.isEmpty()) {
            // No verified snapshot. data_source is SELF_REPORTED only when she actually reported
            // something; with no metric row and no self-reported total there is no source at all,
            // and claiming one would be the fabrication this branch exists to prevent.
            String dataSource = profile.getTotalFollowers() > 0 ? SELF_REPORTED : null;
            return new GetMyMetricsResult(
                    new MetricsResult(false, null, null, null, null, null, dataSource, tier, null),
                    accountLast28Days(profile.getId(), locale));
        }

        CreatorMetric metric = latestMetric.get();
        List<MediaMetric> recentMedia =
                igAccountId == null
                        ? mediaMetricsRepository.findByCreatorProfileIdOrderByTimeDesc(
                                profile.getId(), PageRequest.of(0, RECENT_MEDIA_LIMIT))
                        : mediaMetricsRepository.findForAccountOrderByTimeDesc(
                                profile.getId(), igAccountId, PageRequest.of(0, RECENT_MEDIA_LIMIT));
        QualityScoreResult quality = qualityScoreService.calculate(latestMetric, recentMedia);

        return new GetMyMetricsResult(
                new MetricsResult(
                        true,
                        Rendered.money(BigDecimal.valueOf(metric.getFollowers()), locale),
                        // reach_30d is left null: no column holds a 30-day reach TOTAL. The only
                        // reach figure stored is the per-post average below, and multiplying it by a
                        // post count to fill this field would invent a number the creator could then
                        // quote to a brand. MeeraContextService's context block labels that same
                        // average "reach (30 days)"; that label is wrong, and this tool declines to
                        // repeat it rather than propagate it.
                        null,
                        percent(metric.getAvgEngagementRate(), locale),
                        // Rendered.money is the shared grouping-integer formatter (SPEC.md 3.6) —
                        // used here for a count, not a currency, so "12,340" matches how every other
                        // number in a creator payload is grouped.
                        metric.getAvgReachPerPost() == null
                                ? null
                                : Rendered.money(BigDecimal.valueOf(metric.getAvgReachPerPost()), locale),
                        Rendered.date(metric.getTime(), locale),
                        metric.getDataSource(),
                        tier,
                        // absent() when there was nothing to score — null, never a fabricated 0 or a
                        // neutral 50 (F-0260's ruling, see QualityScoreResult#absent).
                        Rendered.money(quality.overall(), locale)),
                accountLast28Days(profile.getId(), locale));
    }

    /**
     * Account insights (2026-09-24): the same newest snapshot the Analytics page and Meera's
     * context line read, formatted the way every number in this payload is. This is the real
     * 28-day total reach_30d above has to stay null for (no per-post average is ever passed off
     * as a period total).
     */
    private AccountLast28Days accountLast28Days(String creatorProfileId, Locale locale) {
        CreatorAccountInsightsResponse insights = analyticsService.getCreatorAccountInsightsForProfile(creatorProfileId);
        if (insights == null || !insights.hasData()) {
            return AccountLast28Days.notAvailable();
        }
        return new AccountLast28Days(
                true,
                Rendered.date(insights.periodStart(), locale) + " to " + Rendered.date(insights.periodEnd(), locale),
                count(insights.reach(), locale),
                count(insights.views(), locale),
                count(insights.totalInteractions(), locale),
                count(insights.accountsEngaged(), locale),
                count(insights.profileLinksTaps(), locale));
    }

    private static String count(Long value, Locale locale) {
        return value == null ? null : Rendered.money(BigDecimal.valueOf(value), locale);
    }

    /**
     * {@code "3.2%"}. Deliberately does NOT fall back to
     * {@code CreatorProfile#getEngagementRate()} the way the context block does: this branch has
     * already declared {@code connected = true}, and mixing a self-reported rate into a payload
     * labelled as verified is precisely the kind of quiet substitution that lets Meera state an
     * unverified number as a Meta-verified fact.
     */
    private static String percent(BigDecimal rate, Locale locale) {
        if (rate == null) {
            return null;
        }
        NumberFormat format = NumberFormat.getNumberInstance(locale);
        format.setMaximumFractionDigits(1);
        return format.format(rate) + "%";
    }

    private static Locale localeFor(PreferencesResponse prefs) {
        String tag = prefs == null ? null : prefs.creatorLanguage();
        return tag == null || tag.isBlank() ? Rendered.DEFAULT_LOCALE : Locale.forLanguageTag(tag);
    }
}
