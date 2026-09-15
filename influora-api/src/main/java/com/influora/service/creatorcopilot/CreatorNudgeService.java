package com.influora.service.creatorcopilot;

import com.influora.common.ApiException;
import com.influora.common.Ulids;
import com.influora.config.CreatorCopilotProperties;
import com.influora.domain.entity.CreatorNudgeLog;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.Trend;
import com.influora.domain.enums.NudgeMessageSource;
import com.influora.integration.ai.CreatorSuggestionAiClient;
import com.influora.integration.ai.CreatorSuggestionAiClient.SuggestionCopy;
import com.influora.repository.CreatorNudgeLogRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.TrendRepository;
import com.influora.service.trendspark.ThemeMatchService;
import com.influora.web.dto.creatorcopilot.CreatorCopilotDtos.SuggestionDto;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
// NOTE (F-0785): getSuggestion is deliberately NOT @Transactional — see its javadoc. The import
// stays because markDismissed/markActed still use it.

/**
 * Orchestrates the Creator AI Co-pilot's daily suggestion (be-services-plan.md §1) — a FORK of
 * {@link com.influora.service.trendspark.TrendSparkNudgeService}'s structure/discipline, not a
 * shared class: no {@code BrandProfile}/catalog/gap-check/SNAPSBY-mode concepts exist on this
 * path (spec §2.2). Order is mandatory: idempotent-read-first (the per-day cap mechanism, not a
 * separate check) -&gt; theme-tagging-pending check -&gt; pick best-scoring active trend -&gt; AI
 * phrasing (fallback template on null) -&gt; write {@code creator_nudge_log} -&gt; return DTO.
 *
 * <p><b>Content-filter scope (F-0786) — read this before assuming the class is covered.</b> {@link
 * UnsafeHeadlineTopic} is applied on the TEMPLATED FALLBACK path only ({@link #templatedFallback}).
 * It is NOT applied to:
 *
 * <ul>
 *   <li><b>The AI path.</b> {@code aiClient.requestSuggestion} is still handed the raw {@code
 *       trendText}, and copy it returns is persisted unfiltered. F-0786 was scoped to the fallback
 *       (the path that runs when influora-ai is unreachable and no AI-side safety layer is in
 *       play); whether the AI path needs the same server-side filter — the AI service's own prompt
 *       safety being an unverified upstream assumption from here — is a live residual for security
 *       review, not something this change silently decided.
 *   <li><b>{@code theme}.</b> It is persisted and returned in {@code SuggestionDto.theme} on every
 *       path. See {@link #genericFallback}'s javadoc: it is not provably closed-vocab, because
 *       {@code ThemeMatchService.parseThemeJson} does not validate against {@code knownThemes}.
 *       Pre-existing on all three paths, unchanged here, and named so it is not mistaken for
 *       covered.
 * </ul>
 */
@Service
public class CreatorNudgeService {

    private static final Logger log = LoggerFactory.getLogger(CreatorNudgeService.class);

    private final TrendRepository trendRepository;
    private final CreatorProfileRepository creatorProfileRepository;
    private final CreatorNudgeLogRepository creatorNudgeLogRepository;
    private final ThemeMatchService themeMatchService;
    private final CreatorSuggestionAiClient aiClient;
    private final CreatorCopilotProperties props;

    public CreatorNudgeService(
            TrendRepository trendRepository,
            CreatorProfileRepository creatorProfileRepository,
            CreatorNudgeLogRepository creatorNudgeLogRepository,
            ThemeMatchService themeMatchService,
            CreatorSuggestionAiClient aiClient,
            CreatorCopilotProperties props) {
        this.trendRepository = trendRepository;
        this.creatorProfileRepository = creatorProfileRepository;
        this.creatorNudgeLogRepository = creatorNudgeLogRepository;
        this.themeMatchService = themeMatchService;
        this.aiClient = aiClient;
        this.props = props;

        // F-0785 (second half) — the advertised-but-dead cap knob.
        //
        // WHICH CHANGE WAS PICKED, AND WHY. The ticket offered two options: wire a reader that
        // uses CREATOR_COPILOT_DAILY_CAP, or delete the advertised knob and document that the DB
        // unique key is the sole enforcement. DELETING THE KNOB IS THE CORRECT, SMALLER, MORE
        // HONEST CHANGE, because a cap other than 1 is not merely unimplemented — it is
        // UNIMPLEMENTABLE as the schema stands: uq_creator_nudge_day (creator_profile_id,
        // shown_day) in V20260721140000 permits exactly one row per creator per day, so a reader
        // that "supported" CREATOR_COPILOT_DAILY_CAP=3 would still have row 2 rejected by MySQL.
        // Wiring such a reader would encode a promise the database refuses to keep.
        //
        // The deletion is NOT performed here because it spans three files outside this changeset's
        // permitted scope — config/CreatorCopilotProperties.java (field + getter/setter),
        // resources/application.yml:536 (max-suggestions-per-creator-per-day), and
        // test/config/CreatorCopilotConfigWiringTest.java:67 (which asserts the getter's default).
        // Ticket F-0785 was scoped to this file only, and a concurrent session is committing
        // elsewhere in this tree.
        //
        // What IS done here, as the stopgap: a real reader that makes the mismatch LOUD instead of
        // silent. This does not make the knob work — nothing can, without a migration — it only
        // ensures that an operator who sets CREATOR_COPILOT_DAILY_CAP=3 is told at startup that the
        // value cannot take effect, rather than believing a cap of 3 is in force. Do not read this
        // warning as "the cap is now wired": the DB unique key remains the sole enforcement point.
        String capAdvisory = dailyCapAdvisory(props.getMaxSuggestionsPerCreatorPerDay());
        if (capAdvisory != null) {
            log.warn("CreatorNudgeService: {}", capAdvisory);
        }
    }

    /**
     * F-0785 — the only truthful reader for {@code
     * influora.creator-copilot.max-suggestions-per-creator-per-day}.
     *
     * @return {@code null} when the configured cap is actually enforceable (i.e. exactly 1, which
     *     is what {@code uq_creator_nudge_day} permits), otherwise the advisory text explaining why
     *     the configured value cannot take effect.
     */
    static String dailyCapAdvisory(int configuredCap) {
        if (configuredCap == 1) {
            return null;
        }
        return "influora.creator-copilot.max-suggestions-per-creator-per-day is configured as "
                + configuredCap
                + ", but the DB unique key uq_creator_nudge_day (creator_profile_id, shown_day) from"
                + " V20260721140000 permits exactly ONE suggestion row per creator per day and is the"
                + " SOLE enforcement point. The configured value cannot take effect — suggestion 2"
                + " onwards is rejected by the database regardless of this setting. Set it to 1, or"
                + " ship a migration that relaxes uq_creator_nudge_day.";
    }

    /** 3-way status the FE contract needs (API-CONTRACT.md §1.1/§2) — richer than Trend-Spark's
     * binary present/absent, since "nothing to show" here has two distinct causes the FE renders
     * differently (zero-themes-yet vs. zero-matching-trend-today). */
    public record SuggestionResult(String status, SuggestionDto suggestion) {
        public static SuggestionResult pendingTagging() {
            return new SuggestionResult("pending_tagging", null);
        }

        public static SuggestionResult noSuggestionToday() {
            return new SuggestionResult("no_suggestion_today", null);
        }

        public static SuggestionResult ready(SuggestionDto dto) {
            return new SuggestionResult("ready", dto);
        }
    }

    /**
     * F-0785 — <b>this method is deliberately NOT {@code @Transactional}. Do not "restore" it.</b>
     *
     * <p>It used to be, and that annotation is precisely what made the {@code catch
     * (DataIntegrityViolationException)} below dead code. {@link CreatorNudgeLog}'s {@code @Id} is
     * caller-assigned, the entity has no {@code @Version} and does not implement {@code
     * Persistable}, so Spring Data's {@code isNew()} is false and {@code SimpleJpaRepository.save()}
     * goes through {@code em.merge()} — which schedules the INSERT without flushing it. Inside an
     * outer transaction the INSERT therefore executed at COMMIT, in the {@code @Transactional}
     * proxy, AFTER the try block had already exited: the {@code uq_creator_nudge_day} violation
     * escaped as an uncaught 500 and the recovery below never ran. Two simultaneous first-of-day
     * requests for one creator produced one clean row and one HTTP 500.
     *
     * <p>With no outer transaction, each repository call runs inside {@code
     * SimpleJpaRepository}'s own {@code @Transactional} boundary, so the {@code saveAndFlush} below
     * flushes and fails INSIDE the call — the catch is live — and the recovery read that follows
     * runs in a brand-new transaction and persistence context, so it is not executing against a
     * rollback-only context. {@code spring.jpa.open-in-view} is {@code false}
     * (application.yml:45), so there is no request-bound EntityManager for the failed flush to
     * poison either.
     *
     * <p><b>Why dropping the annotation is safe here</b> (checked, not assumed): this method
     * performs exactly ONE write — the {@code creator_nudge_log} insert — so there is no
     * multi-statement atomicity to lose. Every entity field it reads ({@code
     * CreatorProfile.themeTagsJson}/{@code displayName}, {@code Trend.themesJson}/{@code
     * trendText}/{@code id}) is a scalar column; nothing lazy is traversed after the owning
     * repository call returns. It also removes a genuine anti-pattern: the AI phrasing call to
     * influora-ai was previously made while holding a pooled DB connection open.
     *
     * <p><b>If you add a second write to this method, you must NOT simply re-add
     * {@code @Transactional}</b> — that reintroduces this exact bug. Move the write into a
     * collaborator with its own {@code REQUIRES_NEW} boundary (note that a self-invoked
     * {@code @Transactional} method on this class is silently inert), or take a row lock.
     */
    public SuggestionResult getSuggestion(String creatorProfileId) {
        // Defensive: the caller (CreatorContextService.requireCreatorProfile) already 404s before
        // this method is ever invoked, but this method never assumes that contract holds.
        CreatorProfile profile =
                creatorProfileRepository
                        .findById(creatorProfileId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "CREATOR_PROFILE_NOT_FOUND",
                                                "Creator profile not found",
                                                HttpStatus.NOT_FOUND));

        // Idempotent-read-first — THIS is the per-creator/day cap mechanism (be-services-plan §1
        // step 2), not a separate check. A same-day repeat call returns the identical row with no
        // re-scoring and no AI spend (AC-4).
        Optional<CreatorNudgeLog> todays =
                creatorNudgeLogRepository.findByCreatorProfileIdAndShownAtAfter(
                        creatorProfileId, startOfUtcDay());
        if (todays.isPresent()) {
            return SuggestionResult.ready(toDto(todays.get()));
        }

        String creatorThemeTags = profile.getThemeTagsJson();
        if (creatorThemeTags == null || creatorThemeTags.isBlank()) {
            // The nightly CreatorThemeTaggingJob hasn't produced a rollup for this creator yet —
            // distinct from "no matching trend today" so the FE can render different copy.
            return SuggestionResult.pendingTagging();
        }

        List<Trend> activeTrends = trendRepository.findActive(Instant.now());
        Trend bestTrend = null;
        int bestScore = -1;
        for (Trend trend : activeTrends) {
            int score = themeMatchService.score(trend, creatorThemeTags);
            if (score > bestScore) {
                bestScore = score;
                bestTrend = trend;
            }
        }

        if (bestTrend == null || bestScore < props.getScoreThreshold()) {
            // No log row written — nothing to cap, since there's nothing to show.
            return SuggestionResult.noSuggestionToday();
        }

        // Server-sourced, closed-vocab theme — the same value themeMatchService.score matched
        // against. Never comes from the AI call or the fallback template.
        String theme = bestMatchedTheme(bestTrend, creatorThemeTags);

        SuggestionCopy copy = callAiSafely(creatorProfileId, theme, bestTrend.getTrendText());
        NudgeMessageSource messageSource;
        String headline;
        String contentIdea;
        if (copy != null) {
            headline = copy.headline();
            contentIdea = copy.contentIdea();
            messageSource = NudgeMessageSource.AI;
        } else {
            SuggestionCopy fallback = templatedFallback(profile, bestTrend, theme);
            headline = fallback.headline();
            contentIdea = fallback.contentIdea();
            messageSource = NudgeMessageSource.FALLBACK;
        }

        CreatorNudgeLog nudgeLog =
                CreatorNudgeLog.builder()
                        .id(Ulids.newUlid())
                        .creatorProfileId(creatorProfileId)
                        .trendId(bestTrend.getId())
                        .matchScore(bestScore)
                        .theme(theme)
                        .headline(headline)
                        .contentIdea(contentIdea)
                        .messageSource(messageSource)
                        .promptVersion(props.getPromptVersion())
                        .build();

        try {
            // saveAndFlush, NOT save (F-0785). save() on this caller-assigned-@Id entity goes
            // through merge(), which only SCHEDULES the INSERT; the unique-key violation would then
            // surface wherever the enclosing transaction happens to commit, which is what used to
            // put it outside this try block. saveAndFlush forces the INSERT to hit MySQL inside
            // this call, so uq_creator_nudge_day fails HERE or not at all.
            nudgeLog = creatorNudgeLogRepository.saveAndFlush(nudgeLog);
        } catch (DataIntegrityViolationException e) {
            return recoverFromLostDailyCapRace(creatorProfileId, e);
        }

        return SuggestionResult.ready(toDto(nudgeLog));
    }

    /**
     * Lost the per-day-cap race (V20260721140000's {@code uq_creator_nudge_day} unique key) to a
     * concurrent first-of-day request — the winner's row now exists, so return that rather than
     * propagating a 500 (be-services-plan §1 step 8 / §5).
     *
     * <p>This runs OUTSIDE any transaction of ours (see {@link #getSuggestion}'s javadoc), so the
     * re-read below opens its own fresh transaction and persistence context. It is not reading
     * through the rolled-back context that just failed.
     *
     * <p>Two ways this deliberately refuses to paper over a real defect, both of which a reviewer
     * should check are still here:
     *
     * <ul>
     *   <li><b>No winner row found -&gt; rethrow.</b> {@code creator_nudge_log} also carries FKs to
     *       {@code creator_profiles} and {@code trends}; an FK violation arrives as the same {@code
     *       DataIntegrityViolationException} type. If no row for today exists, the violation was
     *       NOT the cap race, and dressing a genuine integrity bug up as a successful suggestion
     *       would hide it forever. The original exception propagates.
     *   <li><b>The re-read itself failing -&gt; rethrow the original,</b> with the read failure
     *       attached as suppressed. Never let a recovery path replace a diagnostic exception with a
     *       less informative one.
     * </ul>
     */
    private SuggestionResult recoverFromLostDailyCapRace(
            String creatorProfileId, DataIntegrityViolationException race) {
        CreatorNudgeLog winner;
        try {
            winner =
                    creatorNudgeLogRepository
                            .findByCreatorProfileIdAndShownAtAfter(creatorProfileId, startOfUtcDay())
                            .orElse(null);
        } catch (RuntimeException readFailed) {
            race.addSuppressed(readFailed);
            throw race;
        }

        if (winner == null) {
            // Not the cap race — a real integrity violation (FK, length, NOT NULL). Surface it.
            log.error(
                    "CreatorNudgeService: integrity violation writing creator_nudge_log for creator={}"
                            + " and no row exists for today — this is NOT the per-day-cap race",
                    creatorProfileId);
            throw race;
        }

        log.info(
                "CreatorNudgeService: lost the per-day-cap race for creator={}, returning the"
                        + " concurrently-written row suggestion={}",
                creatorProfileId,
                winner.getId());
        return SuggestionResult.ready(toDto(winner));
    }

    @Transactional
    public void markDismissed(String creatorProfileId, String suggestionId) {
        CreatorNudgeLog nudgeLog = requireOwnedSuggestion(creatorProfileId, suggestionId);
        nudgeLog.markDismissed();
        creatorNudgeLogRepository.save(nudgeLog);
    }

    @Transactional
    public void markActed(String creatorProfileId, String suggestionId) {
        CreatorNudgeLog nudgeLog = requireOwnedSuggestion(creatorProfileId, suggestionId);
        nudgeLog.markActed();
        creatorNudgeLogRepository.save(nudgeLog);
    }

    /** Resolves the row THEN checks creator ownership — never trusts the id path param alone
     * (Guardrail 2 discipline, same as {@code TrendSparkNudgeService.requireOwnedNudge}). "Not
     * found" and "not yours" return the identical 404 (no IDOR oracle, API-CONTRACT.md §1.2). */
    private CreatorNudgeLog requireOwnedSuggestion(String creatorProfileId, String suggestionId) {
        return creatorNudgeLogRepository
                .findByIdAndCreatorProfileId(suggestionId, creatorProfileId)
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "SUGGESTION_NOT_FOUND", "Suggestion not found", HttpStatus.NOT_FOUND));
    }

    private SuggestionCopy callAiSafely(String creatorProfileId, String theme, String trendText) {
        try {
            return aiClient.requestSuggestion(creatorProfileId, theme, trendText);
        } catch (Exception e) {
            // Belt-and-braces: CreatorSuggestionAiClient already never throws, but the caller must
            // never see an exception surface from the phrasing step either way.
            log.warn(
                    "CreatorNudgeService: AI suggestion call errored for creator={}: {}",
                    creatorProfileId,
                    e.getMessage());
            return null;
        }
    }

    /** Fallback templated copy — placeholder text mirroring {@code
     * TrendSparkNudgeService.templatedFallback}'s discipline (plain, safe, never breaks tone
     * rules); final copy is Ash/Tejas's call per the spec's still-open zero-state ruling (§6/§8),
     * unaffected by this stub. Returns the same two-value tuple shape the AI client returns, so
     * this class never branches into "AI gives 2 fields, template gives 1".
     *
     * <p><b>F-0786:</b> {@code trend.getTrendText()} is a RAW THIRD-PARTY NEWS HEADLINE. This
     * method used to format it straight into creator-facing copy with no filter at all, on the one
     * path that runs precisely when influora-ai is unreachable — i.e. when no AI-side safety layer
     * is in play and nobody is watching. It now refuses to quote a headline that matches any {@link
     * UnsafeHeadlineTopic} and degrades to {@link #genericFallback} instead. */
    private SuggestionCopy templatedFallback(CreatorProfile profile, Trend trend, String theme) {
        String name = displayName(profile);
        String trendText = trend.getTrendText();

        if (!isQuotableInCreatorCopy(trendText)) {
            UnsafeHeadlineTopic topic = firstUnsafeTopic(trendText);
            // The headline text itself is deliberately NOT logged. It is the exact string this
            // method just decided is unfit for creator-facing copy; the trend id is enough to look
            // it up in `trends` during an incident, and keeping it out of the log stream means the
            // rejection cannot reappear verbatim wherever logs are shipped or surfaced.
            log.warn(
                    "CreatorNudgeService: suppressed the trend headline from fallback copy for"
                            + " creator={} trend={} (category={}) — emitting generic copy instead",
                    profile.getId(),
                    trend.getId(),
                    topic == null ? "MISSING_OR_BLANK_HEADLINE" : topic.name());
            return genericFallback(name);
        }

        String headline = String.format("%s, your %s content is trending right now", name, theme);
        String contentIdea =
                String.format(
                        "There's a trend around \"%s\" that fits your %s niche — a quick post while"
                                + " it's hot could land well.",
                        trendText, theme);
        return new SuggestionCopy(headline, contentIdea);
    }

    /**
     * Degraded fallback copy for a headline we refuse to quote (F-0786). Silent-but-functional: the
     * co-pilot still returns a suggestion rather than throwing or returning nothing.
     *
     * <p><b>Contains no trend-derived text whatsoever — not the headline, and not {@code theme}
     * either.</b> The theme is excluded on purpose, and the reason is not obvious: this class's own
     * javadoc calls {@code theme} a "closed-vocab" value, but {@code
     * ThemeMatchService.parseThemeJson} does NOT validate its output against {@code knownThemes} —
     * it returns whatever strings happen to sit in {@code trends.themes_json}. So {@code theme} is
     * only as closed-vocab as the ingestion pipeline that wrote that column, which is the very
     * third-party source this filter exists to distrust. The creator's own display name is the only
     * interpolated value here, and that is first-party data.
     */
    private static SuggestionCopy genericFallback(String name) {
        return new SuggestionCopy(
                String.format("%s, there's a trend in your space worth a look", name),
                "Something in your niche is picking up right now — worth a quick look before you"
                        + " plan today's post.");
    }

    // -------------------------------------------------------------------------------------------
    // F-0786 — content filter for the fallback path
    // -------------------------------------------------------------------------------------------

    /**
     * Topic categories that disqualify a third-party trend headline from being quoted back to a
     * creator as content inspiration (F-0786).
     *
     * <p><b>Design notes for review.</b>
     *
     * <ul>
     *   <li><b>Why these four, and why small sets.</b> The ticket's four categories (death, crime,
     *       communal/religious conflict, legal) are the ones where quoting a headline in our brand
     *       voice — "a quick post while it's hot could land well" — is actively harmful rather than
     *       merely off. Each set is deliberately small and high-signal. An exhaustive blocklist
     *       would become its own maintenance problem and would rot into a false sense of coverage.
     *   <li><b>The error asymmetry that justifies the term choices.</b> A false positive costs one
     *       creator one day of generic copy. A false negative puts a death, a rape case or a
     *       communal riot into a creator's content suggestion. These are not comparable, so
     *       ambiguous high-signal terms are INCLUDED. Known accepted false positives: "killing"
     *       (as in "killing it"), "sue" (the given name), "plea".
     *   <li><b>Terms deliberately EXCLUDED, and why</b> — each of these was considered and rejected
     *       because its false-positive rate in this product's actual niche is high enough to make
     *       the filter useless rather than cautious: {@code killer} ("killer workout"), {@code
     *       shooting}/{@code shot} ("shooting a reel", "the shot"), {@code attack} ("heart attack",
     *       "attack the day"), {@code clash} (routine sports headline verb), {@code court}
     *       ("tennis court" — the LEGAL set uses "high court"/"supreme court"/"courtroom"
     *       instead), and bare {@code mob} ("flash mob" — COMMUNAL uses "lynch mob").
     *   <li><b>COMMUNAL targets CONFLICT, not IDENTITY.</b> There are no religion or caste
     *       identifiers here ({@code hindu}, {@code muslim}, {@code temple}, {@code mosque},
     *       {@code church}, {@code dalit}). This is an India-first product whose legitimate trend
     *       feed is full of festival content — the existing test fixture headline is literally
     *       "Diwali morning workout challenge". Filtering on religious identity would suppress the
     *       product's best content while doing nothing extra for communal-violence coverage, which
     *       the conflict vocabulary below already catches.
     *   <li><b>Known gap, stated rather than papered over:</b> natural disaster, accident and
     *       public-health tragedy are NOT covered — "12 injured in building collapse" passes this
     *       filter. That is outside F-0786's four categories and belongs to a follow-up ticket; it
     *       is recorded here so nobody reads this enum as complete coverage of "bad news".
     * </ul>
     */
    enum UnsafeHeadlineTopic {
        DEATH(
                Set.of(
                        "death", "deaths", "died", "dies", "die", "dead", "deadly", "killed", "kills",
                        "kill", "killing", "fatal", "fatally", "fatality", "fatalities", "suicide",
                        "funeral", "obituary", "mourns", "mourning", "condolences", "passed away",
                        "tragedy", "tragic")),
        CRIME(
                Set.of(
                        "crime", "criminal", "murder", "murders", "murdered", "rape", "raped", "rapist",
                        "assault", "assaulted", "arrest", "arrested", "police", "theft", "robbery",
                        "robbed", "kidnap", "kidnapped", "fraud", "scam", "trafficking", "abuse",
                        "stabbed", "stabbing", "gunman", "gunfire", "shootout", "molested",
                        "harassment")),
        COMMUNAL(
                Set.of(
                        "communal", "sectarian", "riot", "riots", "rioting", "unrest", "curfew",
                        "lynch mob", "lynched", "lynching", "blasphemy", "blasphemous", "jihad",
                        "extremist", "extremism", "terror", "terrorist", "terrorism", "genocide",
                        "hate speech", "hate crime", "ethnic violence", "religious violence",
                        "caste violence")),
        LEGAL(
                Set.of(
                        "lawsuit", "lawsuits", "sue", "sues", "sued", "suing", "litigation",
                        "defamation", "verdict", "indicted", "indictment", "subpoena", "convicted",
                        "conviction", "acquitted", "prosecuted", "prosecution", "tribunal",
                        "injunction", "plea", "high court", "supreme court", "courtroom",
                        "legal notice", "class action"));

        private final Set<String> terms;

        UnsafeHeadlineTopic(Set<String> terms) {
            this.terms = terms;
        }

        Set<String> terms() {
            return terms;
        }
    }

    static {
        // Anti-vacuity guard. Every term must already be in matching-normal form (lowercase,
        // single-spaced, letters/digits only), because matching is done by substring-containment
        // against a normalized headline — a term written as "Hate Speech" or "hate  speech" would
        // compile, ship, and NEVER match anything, i.e. the filter would silently lose a category
        // with no test able to notice unless it happened to cover that exact term. Fail at class
        // load instead. Same discipline as CreatorNudgeServiceTest's ULID-length static guard.
        for (UnsafeHeadlineTopic topic : UnsafeHeadlineTopic.values()) {
            if (topic.terms().isEmpty()) {
                throw new IllegalStateException("unsafe-topic category has no terms: " + topic);
            }
            for (String term : topic.terms()) {
                if (!term.equals(normalizeForMatching(term).trim())) {
                    throw new IllegalStateException(
                            "unsafe-topic term is not in matching-normal form and could never match: '"
                                    + term
                                    + "' (" + topic + ")");
                }
            }
        }
    }

    /**
     * The single decision point for "may this third-party headline appear in creator copy?".
     * <b>Fails closed:</b> a null or blank headline is not quotable.
     */
    private static boolean isQuotableInCreatorCopy(String headline) {
        return headline != null && !headline.isBlank() && firstUnsafeTopic(headline) == null;
    }

    /**
     * First category that disqualifies {@code headline}, or {@code null} if none matched.
     *
     * <p><b>{@code null} here does NOT mean "safe".</b> A null or blank headline also returns
     * {@code null} (nothing to match against). Callers must go through {@link
     * #isQuotableInCreatorCopy}, which fails closed on blank; this method is separate only so the
     * suppression log line can name the category.
     *
     * <p>Matching is whole-token containment, not raw {@code String.contains}: both the headline
     * and every term are normalized to a space-delimited, space-padded, lowercase,
     * letters-and-digits-only form, and a term matches as {@code " term "}. That gives word-boundary
     * semantics for single words and for multi-word phrases alike, without regex (so no
     * escaping hazard, no catastrophic-backtracking surface on attacker-influenced input, and no
     * per-call Pattern compilation). It is why {@code "sue"} does not match {@code "issue"} and
     * {@code "die"} does not match {@code "audience"}, while {@code "murder,"} / {@code "Murder"} /
     * {@code "murder's"} all still match {@code murder}.
     */
    static UnsafeHeadlineTopic firstUnsafeTopic(String headline) {
        if (headline == null || headline.isBlank()) {
            return null;
        }
        String normalized = normalizeForMatching(headline);
        // values() order (DEATH, CRIME, COMMUNAL, LEGAL) is the reported-category order; which one
        // wins is a logging detail only — any match at all suppresses the headline.
        for (UnsafeHeadlineTopic topic : UnsafeHeadlineTopic.values()) {
            for (String term : topic.terms()) {
                if (normalized.contains(" " + term + " ")) {
                    return topic;
                }
            }
        }
        return null;
    }

    /**
     * Lowercases and reduces {@code text} to space-separated alphanumeric tokens, padded with a
     * leading and trailing space so every token is delimited on both sides.
     *
     * <p>{@code Character.toLowerCase(char)} is used rather than {@code String.toLowerCase()} on
     * purpose: the no-arg form applies the JVM's DEFAULT LOCALE, which under a Turkish locale maps
     * 'I' to the dotless 'ı' and would stop {@code "INDICTED"} from ever matching {@code
     * "indicted"} — a filter bypass that depends on nothing but the server's locale setting.
     * {@code Character.toLowerCase} is locale-independent.
     *
     * <p>{@code Character.isLetterOrDigit} is Unicode-aware, so Devanagari and other non-ASCII
     * scripts survive normalization as tokens rather than being shredded into separators.
     */
    private static String normalizeForMatching(String text) {
        StringBuilder out = new StringBuilder(text.length() + 2);
        out.append(' ');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                out.append(Character.toLowerCase(c));
            } else if (out.charAt(out.length() - 1) != ' ') {
                out.append(' ');
            }
        }
        if (out.charAt(out.length() - 1) != ' ') {
            out.append(' ');
        }
        return out.toString();
    }

    private String displayName(CreatorProfile profile) {
        String name = profile.getDisplayName();
        return (name == null || name.isBlank()) ? "You" : name;
    }

    /** Overlap-intersection pick between the trend's themes and the creator's theme_tags — the
     * exact vocabulary member {@code themeMatchService.score} counted as matching. Deterministic
     * (sorted, not first-in-iteration-order) so repeated calls against the same inputs agree. When
     * {@code bestScore >= scoreThreshold} (always true by the time this is called, since score IS
     * overlap count), the intersection is guaranteed non-empty — the final {@code orElse} branch is
     * defensive only. */
    private String bestMatchedTheme(Trend trend, String creatorThemeTagsJson) {
        Set<String> trendThemes = themeMatchService.parseThemeJson(trend.getThemesJson());
        Set<String> creatorThemes = themeMatchService.parseThemeJson(creatorThemeTagsJson);
        return trendThemes.stream()
                .filter(creatorThemes::contains)
                .sorted()
                .findFirst()
                .orElseGet(() -> trendThemes.stream().sorted().findFirst().orElse(""));
    }

    private SuggestionDto toDto(CreatorNudgeLog row) {
        return new SuggestionDto(
                row.getId(), row.getTheme(), row.getHeadline(), row.getContentIdea(), expiresAt(row.getShownAt()));
    }

    /** End of the UTC day containing {@code shownAt} — NOT a stored column, computed on every
     * read (API-CONTRACT.md §2, "display-only for Tier-1"). */
    private String expiresAt(Instant shownAt) {
        return shownAt
                .atZone(ZoneOffset.UTC)
                .toLocalDate()
                .plusDays(1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toString();
    }

    private static Instant startOfUtcDay() {
        return Instant.now().atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
