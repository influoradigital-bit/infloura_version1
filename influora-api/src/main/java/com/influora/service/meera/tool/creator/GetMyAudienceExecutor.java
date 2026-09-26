package com.influora.service.meera.tool.creator;

import com.influora.common.Rendered;
import com.influora.domain.entity.CreatorProfile;
import com.influora.repository.MetaOAuthTokenRepository;
import com.influora.service.CreatorAgentPreferencesService;
import com.influora.service.analytics.AnalyticsService;
import com.influora.service.meera.CreatorAudienceShares;
import com.influora.web.dto.analytics.AnalyticsDtos.CreatorSelfDemographicsResponse;
import com.influora.web.dto.creator.CreatorAgentDtos.PreferencesResponse;
import com.influora.web.dto.meera.CreatorToolDtos.AudienceAgeShare;
import com.influora.web.dto.meera.CreatorToolDtos.AudienceCountryShare;
import com.influora.web.dto.meera.CreatorToolDtos.AudienceGenderShare;
import com.influora.web.dto.meera.CreatorToolDtos.AudienceSection;
import com.influora.web.dto.meera.CreatorToolDtos.GetMyAudienceResult;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code get_my_audience} (Swapnil 2026-09-26): the creator's OWN audience, read-only -- who
 * follows her (age bands, gender split, top cities, top countries, as-of date) and who engaged
 * with her content this month (same fields), each either available or with the exact reason it is
 * not.
 *
 * <p><b>Scoping.</b> The only input is the JWT-verified creator user id the controller passes
 * ({@code CreatorMeeraToolController#handleRead}); the profile is resolved from it, never from the
 * request body, so no other creator's audience is reachable. The read is the creator-self one
 * behind her own Analytics page ({@link AnalyticsService#getCreatorDemographicsForProfile}); the
 * brand-facing demographics route is never involved and never carries the engaged audience.
 *
 * <p><b>Same rules as the "Your audience" context line</b> ({@code MeeraContextService}), in the
 * same words ({@link CreatorAudienceShares}): a stored snapshot is never surfaced once the creator
 * has no live Meta connection (LOW-2), a failed read degrades to a reason instead of failing the
 * turn (LOW-1), and nothing is ever guessed: no snapshot means not available, never zeros.
 */
@Service
public class GetMyAudienceExecutor {

    private static final Logger log = LoggerFactory.getLogger(GetMyAudienceExecutor.class);

    private final CreatorAgentPreferencesService preferencesService;
    private final AnalyticsService analyticsService;
    private final MetaOAuthTokenRepository metaOAuthTokenRepository;

    public GetMyAudienceExecutor(
            CreatorAgentPreferencesService preferencesService,
            AnalyticsService analyticsService,
            MetaOAuthTokenRepository metaOAuthTokenRepository) {
        this.preferencesService = preferencesService;
        this.analyticsService = analyticsService;
        this.metaOAuthTokenRepository = metaOAuthTokenRepository;
    }

    /**
     * @param input unused -- {@code get_my_audience} takes no arguments; the parameter is kept so
     *     every creator read executor shares one dispatch signature at the controller
     */
    @Transactional(readOnly = true)
    public GetMyAudienceResult execute(String creatorUserId, Map<String, Object> input) {
        CreatorProfile profile = preferencesService.requireCreatorProfile(creatorUserId);
        if (!hasLiveMetaConnection(profile.getId())) {
            return both(CreatorAudienceShares.NOT_CONNECTED);
        }
        PreferencesResponse prefs = preferencesService.getOrCreatePreferences(creatorUserId);
        Locale locale = localeFor(prefs);

        CreatorSelfDemographicsResponse demographics;
        try {
            demographics = analyticsService.getCreatorDemographicsForProfile(profile.getId());
        } catch (RuntimeException e) {
            // Profile id only, never any breakdown data.
            log.warn("GetMyAudienceExecutor: audience read failed for creator profile {}", profile.getId(), e);
            return both(CreatorAudienceShares.READ_FAILED);
        }
        if (demographics == null || !demographics.hasData()) {
            return both(CreatorAudienceShares.FOLLOWERS_NOT_YET);
        }

        AudienceSection followers =
                section(
                        demographics.ageGenderBreakdown(),
                        demographics.cityBreakdown(),
                        demographics.countryBreakdown(),
                        demographics.fetchedAt(),
                        locale,
                        CreatorAudienceShares.FOLLOWERS_NOT_YET);
        AudienceSection engaged =
                demographics.hasEngaged()
                        ? section(
                                demographics.engagedAgeGenderBreakdown(),
                                demographics.engagedCityBreakdown(),
                                demographics.engagedCountryBreakdown(),
                                demographics.engagedFetchedAt(),
                                locale,
                                CreatorAudienceShares.engagedReason(demographics.engagedStatus()))
                        : AudienceSection.notAvailable(CreatorAudienceShares.engagedReason(demographics.engagedStatus()));
        return new GetMyAudienceResult(followers, engaged);
    }

    /** Package-private for tests: one audience from its three raw breakdowns. */
    static AudienceSection section(
            Map<String, ?> ageGender,
            Map<String, ?> cities,
            Map<String, ?> countries,
            Instant asOf,
            Locale locale,
            String reasonWhenEmpty) {
        List<AudienceAgeShare> age =
                CreatorAudienceShares.ageBands(ageGender).stream()
                        .map(s -> new AudienceAgeShare(s.key(), s.pct()))
                        .toList();
        List<AudienceGenderShare> gender =
                CreatorAudienceShares.genders(ageGender).stream()
                        .map(s -> new AudienceGenderShare(s.key(), s.pct()))
                        .toList();
        List<String> topCities = CreatorAudienceShares.topCities(cities, CreatorAudienceShares.TOP_CITIES);
        List<AudienceCountryShare> topCountries =
                CreatorAudienceShares.topCountries(countries, CreatorAudienceShares.TOP_COUNTRIES).stream()
                        .map(s -> new AudienceCountryShare(s.key(), s.pct()))
                        .toList();
        if (age.isEmpty() && gender.isEmpty() && topCities.isEmpty() && topCountries.isEmpty()) {
            return AudienceSection.notAvailable(reasonWhenEmpty);
        }
        return new AudienceSection(true, null, Rendered.date(asOf, locale), age, gender, topCities, topCountries);
    }

    private static GetMyAudienceResult both(String reason) {
        return new GetMyAudienceResult(AudienceSection.notAvailable(reason), AudienceSection.notAvailable(reason));
    }

    /**
     * The same "live creator-owned Meta connection" test {@code MeeraContextService} and {@code
     * MetricsPollingJob#onCreatorConnected} use: a non-revoked, creator-owned (workspace_id IS NULL)
     * token that is either not expiring or not yet expired.
     */
    private boolean hasLiveMetaConnection(String creatorProfileId) {
        return metaOAuthTokenRepository
                .findByCreatorProfileIdAndWorkspaceIdIsNullAndRevokedFalse(creatorProfileId)
                .filter(t -> t.getExpiresAt() == null || t.getExpiresAt().isAfter(Instant.now()))
                .isPresent();
    }

    private static Locale localeFor(PreferencesResponse prefs) {
        String tag = prefs == null ? null : prefs.creatorLanguage();
        return tag == null || tag.isBlank() ? Rendered.DEFAULT_LOCALE : Locale.forLanguageTag(tag);
    }
}
