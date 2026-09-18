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
import java.text.Normalizer;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
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
 * <p><b>Content-filter scope (F-0786, corrected by F-0825) — read this before moving the gate
 * again.</b> {@link UnsafeHeadlineTopic} is applied to the COPY ABOUT TO BE PERSISTED, in {@link
 * #getSuggestion} immediately before the {@code creator_nudge_log} write, over {@code headline},
 * {@code contentIdea} AND {@code theme}, <b>on every path regardless of which produced them</b>.
 *
 * <p>F-0786 originally mounted it on {@link #templatedFallback}'s INPUT, and Kabir's review proved
 * that was the wrong path. influora-ai has its OWN internal fallback ({@code
 * app/prompt/creator_suggestion.py} {@code fallback_message}) that fires on spend-gate trip,
 * provider error or model-output-validation failure; it returns HTTP 200 / {@code success: true}
 * with the raw {@code trend_text} interpolated into the copy and {@code message_source:
 * "FALLBACK"}. Java's transport-failure fallback never runs for those responses, so every one of
 * them sailed past the F-0786 filter — and, because this class used to derive {@code
 * NudgeMessageSource} from "did the call succeed" rather than reading {@code message_source}, was
 * stamped {@code AI} in the audit trail as well. Both halves are fixed: {@link #messageSourceOf}
 * reads the wire value, and the gate now sits on the output rather than on one path's input.
 *
 * <p>Gating the OUTPUT (not the trend headline going in) is what makes the coverage claim
 * checkable: there is exactly one place copy can reach the database from, and it is gated. It also
 * closes the two smaller findings that rode along with the old placement — copy the MODEL invented
 * (rather than quoted) is now filtered too, and {@code theme}, which is persisted and rendered in
 * {@code SuggestionDto.theme} on every path and is NOT provably closed-vocab ({@code
 * ThemeMatchService.parseThemeJson} does not validate against {@code knownThemes}), is checked
 * instead of merely disclaimed.
 *
 * <p>{@link #templatedFallback} ALSO keeps its own input check. That is deliberate duplication, not
 * a leftover: it is the only place that can tell a suppressed headline apart from a {@code
 * null}/blank one before {@code String.format} renders the literal text {@code "null"} into the
 * copy, and it names the matched category in the suppression log. The output gate is the one that
 * must never be removed.
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

        // -------------------------------------------------------------------------------------
        // F-0825 — THE content gate, theme arm. F-0838: it runs BEFORE the AI call.
        // -------------------------------------------------------------------------------------
        // The theme is fully determined by this point and its verdict cannot depend on anything the
        // AI returns, so checking it after callAiSafely only meant paying influora-ai (spend-gate
        // budget, provider tokens, latency) for copy that was then thrown away unconditionally —
        // and sending an unsafe theme string to the model as a prompt input on top. Do not move
        // this below the AI call again.
        //
        // THEME IS HANDLED SEPARATELY AND MORE STRICTLY, and the reason is not obvious: degrading
        // the COPY cannot launder the theme, because `theme` is persisted in its own column and
        // rendered on its own in SuggestionDto.theme. Emitting generic copy alongside a theme of
        // "communal riot" would be a gate that reports success while shipping the very string it
        // objected to. There is also no safe substitute value to swap in — the theme is the whole
        // subject of the suggestion — so the only honest degrade is to show nothing today. A
        // creator loses one day of copy; the alternative is rendering a poisoned themes_json value
        // in the product. (Blank/empty theme lands here too: bestMatchedTheme's `orElse("")` is
        // documented as defensive-only and unreachable at this point, so if it DOES fire, staying
        // silent is the right answer rather than persisting a themeless row.)
        if (!isQuotableInCreatorCopy(theme)) {
            log.warn(
                    "CreatorNudgeService: refusing to serve a suggestion for creator={} trend={} —"
                            + " the server-derived theme is not fit for creator copy (category={});"
                            + " no creator_nudge_log row written",
                    creatorProfileId,
                    bestTrend.getId(),
                    categoryNameFor(theme));
            return SuggestionResult.noSuggestionToday();
        }

        // -------------------------------------------------------------------------------------
        // F-0854 — THE content gate, trend-headline arm, ahead of the AI call. (vikram, 2026-09-17,
        // ticket F-0854 — a murder headline previously reached influora-ai and its paraphrase was
        // persisted status=ready, message_source=AI, because only `theme` was checked above this
        // point and callAiSafely (below) sends bestTrend.getTrendText() — a RAW THIRD-PARTY
        // headline — to influora-ai as a prompt input regardless of its content.)
        // -------------------------------------------------------------------------------------
        // The theme check above says nothing about the headline: they are independent
        // server-derived values, and a safe theme must not wave an unsafe headline through to the
        // paid AI call. Unlike the theme arm, there IS a safe substitute here — templatedFallback
        // already knows how to degrade an unsafe headline to generic copy (F-0786) — so the
        // response to an unsafe headline is to withhold it from the AI call and go straight to
        // that same degrade, exactly as if influora-ai were unreachable. That keeps `copy` and the
        // eventual message_source/row outcome identical to the existing offline-fallback path,
        // while guaranteeing the raw headline is never sent to the model and never labelled AI.
        // F-0786/F-0825's gate on the RESULTING copy (below, after callAiSafely) stays in place —
        // it is what catches a SAFE headline that the model paraphrases into something unsafe,
        // which this arm cannot see. Never log the headline text itself, only its category.
        boolean headlineSafeToSendToAi = isQuotableInCreatorCopy(bestTrend.getTrendText());
        if (!headlineSafeToSendToAi) {
            log.warn(
                    "CreatorNudgeService: withholding the trend headline from the AI call for"
                            + " creator={} trend={} (category={}) — degrading straight to fallback"
                            + " copy instead of paying for a model call",
                    creatorProfileId,
                    bestTrend.getId(),
                    categoryNameFor(bestTrend.getTrendText()));
        }
        SuggestionCopy copy =
                headlineSafeToSendToAi
                        ? callAiSafely(creatorProfileId, theme, bestTrend.getTrendText())
                        : null;
        NudgeMessageSource messageSource;
        String headline;
        String contentIdea;
        if (copy != null) {
            headline = copy.headline();
            contentIdea = copy.contentIdea();
            // F-0825: read the label the AI service actually sent. A 200 is NOT proof a model wrote
            // this — influora-ai's own fallback returns 200/success:true/message_source=FALLBACK.
            messageSource = messageSourceOf(copy.messageSource(), creatorProfileId);
        } else {
            SuggestionCopy fallback = templatedFallback(profile, bestTrend, theme);
            headline = fallback.headline();
            contentIdea = fallback.contentIdea();
            messageSource = NudgeMessageSource.FALLBACK;
        }

        // -------------------------------------------------------------------------------------
        // F-0825 — THE content gate, copy arm. One place, on the copy about to be persisted.
        // -------------------------------------------------------------------------------------
        if (!isQuotableInCreatorCopy(headline) || !isQuotableInCreatorCopy(contentIdea)) {
            // The copy itself is deliberately NOT logged — it is the exact text just judged unfit
            // for creator-facing copy, and keeping it out of the log stream means the rejection
            // cannot reappear verbatim wherever logs are shipped. ids are enough to reconstruct it.
            // F-0833: only the field(s) that actually FAILED are named. Naming both, as this used
            // to, stamped MISSING_OR_BLANK on a field that had simply matched nothing — pointing an
            // incident responder at a blank-copy bug that did not exist.
            log.warn(
                    "CreatorNudgeService: suppressed {} copy for creator={} trend={} (unquotable:"
                            + " {}) — persisting generic copy instead",
                    messageSource,
                    creatorProfileId,
                    bestTrend.getId(),
                    unquotableFieldsFor(headline, contentIdea));
            SuggestionCopy degraded = safeGenericFallback(displayName(profile));
            headline = degraded.headline();
            contentIdea = degraded.contentIdea();
            // The stored copy is now OURS, not the model's. message_source describes the copy in
            // the row, so labelling a suppressed row AI would be the same class of audit-trail lie
            // F-0825 is about.
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

    /**
     * F-0825 — turns influora-ai's wire {@code message_source} into the enum persisted in {@code
     * creator_nudge_log.message_source}.
     *
     * <p><b>Fails closed to {@code FALLBACK}, never to {@code AI}.</b> The failure this method
     * exists to prevent is a row claiming a model vetted copy that no model ever saw; the opposite
     * error (under-claiming AI during a version skew) costs nothing but an understated stat. The
     * skew is logged rather than swallowed so a python route that stops sending the field is loud.
     */
    private NudgeMessageSource messageSourceOf(String wireValue, String creatorProfileId) {
        String value = wireValue == null ? "" : wireValue.trim();
        if (NudgeMessageSource.AI.name().equalsIgnoreCase(value)) {
            return NudgeMessageSource.AI;
        }
        if (NudgeMessageSource.FALLBACK.name().equalsIgnoreCase(value)) {
            return NudgeMessageSource.FALLBACK;
        }
        log.warn(
                "CreatorSuggestion: influora-ai returned 200 with an absent/unrecognised"
                        + " message_source ('{}') for creator={} — recording FALLBACK, because a 200 is"
                        + " not evidence a model produced this copy",
                value,
                creatorProfileId);
        return NudgeMessageSource.FALLBACK;
    }

    /** Category name for a suppression log line, or a marker when nothing matched (i.e. the text
     * was rejected by the fail-closed null/blank/all-invisible arm rather than by a term).
     *
     * <p><b>Only meaningful for text already known to be UNQUOTABLE</b> (F-0833): for quotable text
     * {@code firstUnsafeTopic} is also {@code null}, so this would wrongly report
     * MISSING_OR_BLANK. Callers with a mix of passing and failing fields use {@link
     * #unquotableFieldsFor}. */
    private static String categoryNameFor(String text) {
        UnsafeHeadlineTopic topic = firstUnsafeTopic(text);
        return topic == null ? "MISSING_OR_BLANK" : topic.name();
    }

    /** F-0833 — {@code "headline=CRIME"}, {@code "contentIdea=MISSING_OR_BLANK"}, or both joined by
     * {@code ", "}: names ONLY the fields that failed the gate, each with what it failed on. */
    static String unquotableFieldsFor(String headline, String contentIdea) {
        StringBuilder out = new StringBuilder();
        if (!isQuotableInCreatorCopy(headline)) {
            out.append("headline=").append(categoryNameFor(headline));
        }
        if (!isQuotableInCreatorCopy(contentIdea)) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append("contentIdea=").append(categoryNameFor(contentIdea));
        }
        return out.toString();
    }

    /**
     * {@link #genericFallback} plus a proof that the degraded copy is itself quotable.
     *
     * <p>The interpolated display name is first-party data, but "first-party" is not "safe" — a
     * creator chooses their own display name, so degrading to generic copy could otherwise persist
     * copy that the gate would reject on a second pass. There is no third degrade below this: the
     * name-free variant is built from compile-time constants that the class-load guard proves are
     * quotable.
     */
    private static SuggestionCopy safeGenericFallback(String name) {
        SuggestionCopy named = genericFallback(name);
        if (isQuotableInCreatorCopy(named.headline()) && isQuotableInCreatorCopy(named.contentIdea())) {
            return named;
        }
        return genericFallback(NO_NAME);
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
        return new SuggestionCopy(headline, contentIdea, NudgeMessageSource.FALLBACK.name());
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
                        + " plan today's post.",
                NudgeMessageSource.FALLBACK.name());
    }

    /** The name used when the creator has none — and the last-resort name when their own display
     * name is not fit for creator copy ({@link #safeGenericFallback}). */
    private static final String NO_NAME = "You";

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
     *       (as in "killing it"), "sue" (the given name), "plea", and — added by F-0826 —
     *       "grooming" ("men's grooming", "pet grooming"), "blast" ("blast from the past") and
     *       "explosion" ("an explosion of colour"). Each of those three was weighed against what
     *       it catches (child-sexual-abuse coverage, bombings) and the trade was taken knowingly;
     *       they are listed HERE, as accepted costs, rather than left for a reviewer to discover.
     *   <li><b>Terms deliberately EXCLUDED, and why</b> — each of these was considered and rejected
     *       because its false-positive rate in this product's actual niche is high enough to make
     *       the filter useless rather than cautious: {@code killer} ("killer workout"), bare
     *       {@code shooting}/{@code shot} ("shooting a reel", "the shot"), bare {@code attack}
     *       ("heart attack", "attack the day"), bare {@code clash} (routine sports headline verb),
     *       {@code court} ("tennis court" — the LEGAL set uses "high court"/"supreme
     *       court"/"courtroom" instead), and bare {@code mob} ("flash mob" — COMMUNAL uses "lynch
     *       mob").
     *   <li><b>F-0826: the phrase technique now applies to those exclusions too.</b> Rescuing a
     *       high-false-positive word by qualifying it ("lynch mob", "high court") was invented for
     *       {@code mob}/{@code court} but never applied to {@code shooting}/{@code attack}/{@code
     *       clash}, which were simply dropped — so "school shooting" and "acid attack" passed. The
     *       phrases {@code school shooting}, {@code mass shooting}, {@code acid attack}, {@code
     *       terror attack} and {@code communal clash} are now listed. <b>The bare words stay
     *       excluded</b>; that judgement was sound and re-adding them would break {@link
     *       com.influora.service.creatorcopilot.CreatorNudgeService} 's benign-headline tests on
     *       purpose.
     *   <li><b>F-0826: enumerated gaps closed, not a new kind of list.</b> The additions are (a)
     *       inflections of terms already present whose base form was listed but whose plural or
     *       verb form was not ({@code murderer}, {@code rapes}, {@code rapists}, {@code killings},
     *       {@code stabs}, {@code molestation}, {@code arrests}, {@code abducted}), and (b) five
     *       categories that were absent outright: mass killing ({@code massacre}, {@code
     *       homicide}, {@code manslaughter}, {@code beheaded}), explosives ({@code bomb}, {@code
     *       blast}, {@code explosion}, {@code ied} — which belonged here all along, since COMMUNAL
     *       already carried {@code terrorism}), hostage-taking, self-harm beyond bare {@code
     *       suicide}, and child safety ({@code grooming}, {@code child exploitation}, {@code
     *       csam}). The sets stay small, curated and high-signal; this is not the start of an
     *       unbounded blocklist, and the next gap should be closed the same way — named, tested,
     *       and argued — rather than by bulk-importing a vocabulary.
     *   <li><b>Inflection coverage is NOT systematic, and must not be assumed.</b> These sets are
     *       literal strings; there is no stemmer. {@code blasting} is not covered by {@code
     *       blast}, {@code massacring} is not covered by {@code massacre}. The closure added here
     *       is the one F-0826 enumerated plus the obvious siblings of those exact words, no more.
     *       The one systematic exception (F-0832): every MULTI-WORD phrase also matches with a
     *       regular plural on its final word — see {@code matchesTerm}. Single words stay literal.
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
                        "kill", "killing", "killings", "fatal", "fatally", "fatality", "fatalities",
                        "suicide", "suicides", "suicidal", "self harm", "funeral", "obituary", "mourns",
                        "mourning", "condolences", "passed away", "tragedy", "tragic",
                        // F-0826 — mass-killing vocabulary, absent outright before.
                        "massacre", "massacres", "massacred", "homicide", "homicides", "manslaughter",
                        "beheaded", "beheading",
                        // F-0855/2026-09-18 decision — Indian-English news vocabulary Kabir's probe
                        // named outright. "succumb" and "stampede" are base forms; the
                        // generated-inflection rule in matchesTerm produces "succumbs" and
                        // "stampedes"/"stampeded"/"stampeding" from them. "slain" is the irregular
                        // past participle of "slay" and cannot be generated by any suffix rule, so
                        // it is listed as its own literal.
                        "succumb", "stampede", "slain",
                        // F-0856/2026-09-18 decision — Hinglish (Latin-script) term set, Kabir's
                        // probe: "atmahatya" = suicide. Devanagari-script coverage is a KNOWN GAP,
                        // stated rather than silently assumed complete: normalizeForMatching already
                        // strips Devanagari vowel signs (matras) and the virama as combining marks
                        // (rule 2), which is required for the Latin-script evasions this filter
                        // targets, but a literal Devanagari term would have to be written in that
                        // already-stripped consonant-skeleton form to pass the class-load
                        // self-check below — getting that transliteration wrong fails class load for
                        // the WHOLE enum. That risk is out of this changeset's safe scope; it belongs
                        // to its own ticket, not a guess made here.
                        "atmahatya")),
        CRIME(
                Set.of(
                        "crime", "criminal", "murder", "murders", "murdered", "rape", "raped", "rapist",
                        "assault", "assaulted", "arrest", "arrested", "police", "theft", "robbery",
                        "robbed", "kidnap", "kidnapped", "fraud", "scam", "trafficking", "abuse",
                        "stabbed", "stabbing", "gunman", "gunfire", "shootout", "molested",
                        "harassment",
                        // F-0826 — inflections whose base form was already listed.
                        "murderer", "murderers", "rapes", "rapists", "stabs", "molestation",
                        "molesting", "arrests", "abducted", "abduction", "abducts", "kidnaps",
                        "kidnapping",
                        // F-0826 — hostage-taking and child safety, absent outright before.
                        "hostage", "hostages", "grooming", "child exploitation", "csam",
                        // F-0826 — the phrase technique applied to the bare words that stay excluded.
                        "acid attack", "school shooting", "mass shooting",
                        // F-0856/2026-09-18 decision — Hindi/Hinglish (Latin-script) term set,
                        // Kabir's probe: "hatya" (killing/murder), "balatkar" (rape).
                        "hatya", "balatkar",
                        // F-0855/2026-09-18 decision — Indian-English news vocabulary Kabir's probe
                        // named outright: an FIR (First Information Report) being lodged is how an
                        // Indian crime story is reported ahead of any arrest or verdict.
                        "fir lodged")),
        COMMUNAL(
                Set.of(
                        "communal", "sectarian", "riot", "riots", "rioting", "unrest", "curfew",
                        "lynch mob", "lynched", "lynching", "blasphemy", "blasphemous", "jihad",
                        "extremist", "extremism", "terror", "terrorist", "terrorism", "genocide",
                        "hate speech", "hate crime", "ethnic violence", "religious violence",
                        "caste violence",
                        // F-0826 — explosives. These belong beside "terrorism", which was already
                        // here; leaving them out while listing terrorism was the inconsistency.
                        "bomb", "bombs", "bombing", "bombings", "blast", "blasts", "explosion",
                        "explosions", "ied",
                        // F-0826 — phrase forms of two words that stay excluded as bare terms.
                        // "terror attack"/"communal clash" are strictly redundant (their first word
                        // is already a term); they are listed so the intent survives a future edit
                        // that narrows "terror" or "communal".
                        "terror attack", "communal clash",
                        // F-0855/2026-09-18 decision — Indian-English news vocabulary Kabir's probe
                        // named outright: stone-pelting is Indian-news shorthand for crowd violence
                        // during a protest or riot, not a sport or craft term in this product's
                        // niche.
                        "stone pelting")),
        LEGAL(
                Set.of(
                        "lawsuit", "lawsuits", "sue", "sues", "sued", "suing", "litigation",
                        "defamation", "verdict", "indicted", "indictment", "subpoena", "convicted",
                        "conviction", "acquitted", "prosecuted", "prosecution", "tribunal",
                        "injunction", "plea", "high court", "supreme court", "courtroom",
                        "legal notice", "class action",
                        // F-0855/2026-09-18 decision — Indian-English news vocabulary Kabir's probe
                        // named outright: "Delhi HC" and "apex court" are how Indian outlets refer
                        // to the Delhi High Court and the Supreme Court respectively; neither is
                        // covered by the existing "high court"/"supreme court" phrases.
                        "delhi hc", "apex court"));

        private final Set<String> terms;

        UnsafeHeadlineTopic(Set<String> terms) {
            this.terms = terms;
        }

        Set<String> terms() {
            return terms;
        }
    }

    /**
     * Cyrillic and Greek characters that are visually indistinguishable from a Latin letter, mapped
     * onto that letter (F-0827). Keys are LOWERCASE code points only — {@link
     * #normalizeForMatching} lowercases before folding, so the uppercase homoglyphs (Cyrillic А, Е,
     * О, Р, С, Х, М, Т, К, Н, В; Greek Α, Β, Ε, Η, Ι, Κ, Μ, Ν, Ο, Ρ, Τ, Υ, Χ) are covered by their
     * lowercase forms being listed here.
     *
     * <p>Kept SMALL on purpose. This is the set that actually appears in homoglyph substitution of
     * English words, not a general transliteration table: mapping all of Cyrillic to Latin would
     * mangle legitimate Cyrillic headlines into accidental English words. Note that Cyrillic в and
     * Greek ν are mapped by SHAPE (to {@code b} and {@code v}), which is what an attacker exploits,
     * not by their phonetic value.
     */
    private static final Map<Integer, Integer> CONFUSABLE_FOLD =
            Map.ofEntries(
                    // Cyrillic
                    Map.entry((int) 'а', (int) 'a'), // U+0430
                    Map.entry((int) 'е', (int) 'e'), // U+0435
                    Map.entry((int) 'о', (int) 'o'), // U+043E
                    Map.entry((int) 'р', (int) 'p'), // U+0440
                    Map.entry((int) 'с', (int) 'c'), // U+0441
                    Map.entry((int) 'у', (int) 'y'), // U+0443
                    Map.entry((int) 'х', (int) 'x'), // U+0445
                    Map.entry((int) 'і', (int) 'i'), // U+0456
                    Map.entry((int) 'ј', (int) 'j'), // U+0458
                    Map.entry((int) 'ѕ', (int) 's'), // U+0455
                    Map.entry((int) 'м', (int) 'm'), // U+043C
                    Map.entry((int) 'т', (int) 't'), // U+0442
                    Map.entry((int) 'к', (int) 'k'), // U+043A
                    Map.entry((int) 'н', (int) 'h'), // U+043D
                    Map.entry((int) 'в', (int) 'b'), // U+0432
                    Map.entry((int) 'ԁ', (int) 'd'), // U+0501
                    // Greek
                    Map.entry((int) 'α', (int) 'a'), // U+03B1
                    Map.entry((int) 'β', (int) 'b'), // U+03B2
                    Map.entry((int) 'ε', (int) 'e'), // U+03B5
                    Map.entry((int) 'η', (int) 'h'), // U+03B7
                    Map.entry((int) 'ι', (int) 'i'), // U+03B9
                    Map.entry((int) 'κ', (int) 'k'), // U+03BA
                    Map.entry((int) 'μ', (int) 'm'), // U+03BC
                    Map.entry((int) 'ν', (int) 'v'), // U+03BD
                    Map.entry((int) 'ο', (int) 'o'), // U+03BF
                    Map.entry((int) 'ρ', (int) 'p'), // U+03C1
                    Map.entry((int) 'τ', (int) 't'), // U+03C4
                    Map.entry((int) 'υ', (int) 'u'), // U+03C5
                    Map.entry((int) 'χ', (int) 'x'), // U+03C7
                    Map.entry((int) 'ς', (int) 's'), // U+03C2
                    // Armenian (F-0856/2026-09-18 decision, Kabir's probe: "Armenian ... letters").
                    // A small curated subset, same discipline as the Cyrillic/Greek sets above: only
                    // the handful that are visually indistinguishable from a Latin letter, not a
                    // transliteration table.
                    Map.entry((int) 'ա', (int) 'w'), // U+0561
                    Map.entry((int) 'հ', (int) 'h'), // U+0570
                    Map.entry((int) 'օ', (int) 'o'), // U+0585
                    Map.entry((int) 'ս', (int) 'u'), // U+057D
                    // Small capitals (IPA Extensions / Phonetic Extensions / Latin Extended-D) —
                    // F-0856: "ᴍᴜʀᴅᴇʀ"-style styling is ordinary creator/YouTube-title decoration,
                    // the same channel F-0827's fullwidth/math-bold folding already targets.
                    Map.entry(0x1D00, (int) 'a'), // ᴀ
                    Map.entry(0x1D03, (int) 'b'), // ᴃ
                    Map.entry(0x1D04, (int) 'c'), // ᴄ
                    Map.entry(0x1D05, (int) 'd'), // ᴅ
                    Map.entry(0x1D07, (int) 'e'), // ᴇ
                    Map.entry(0x0262, (int) 'g'), // ɢ
                    Map.entry(0x029C, (int) 'h'), // ʜ
                    Map.entry(0x026A, (int) 'i'), // ɪ
                    Map.entry(0x1D0A, (int) 'j'), // ᴊ
                    Map.entry(0x1D0B, (int) 'k'), // ᴋ
                    Map.entry(0x1D0C, (int) 'l'), // ᴌ
                    Map.entry(0x1D0D, (int) 'm'), // ᴍ
                    Map.entry(0x0274, (int) 'n'), // ɴ
                    Map.entry(0x1D0F, (int) 'o'), // ᴏ
                    Map.entry(0x1D18, (int) 'p'), // ᴘ
                    Map.entry(0xA7AF, (int) 'q'), // ꞯ
                    Map.entry(0x0280, (int) 'r'), // ʀ
                    Map.entry(0xA731, (int) 's'), // ꜱ
                    Map.entry(0x1D1B, (int) 't'), // ᴛ
                    Map.entry(0x1D1C, (int) 'u'), // ᴜ
                    Map.entry(0x1D20, (int) 'v'), // ᴠ
                    Map.entry(0x1D21, (int) 'w'), // ᴡ
                    Map.entry(0x028F, (int) 'y'), // ʏ
                    Map.entry(0x1D22, (int) 'z'), // ᴢ
                    // Dotless i (F-0856, Kabir's probe: "dotless i"). U+0131 is already lowercase per
                    // Unicode data (its uppercase form is plain 'I'), so Character.toLowerCase leaves
                    // it unchanged — the fold has to happen here, not via case-mapping.
                    Map.entry(0x0131, (int) 'i'),
                    // Digit and symbol leetspeak substitutions (F-0856, decision 2026-09-18, Kabir's
                    // own named samples "murd3r" and "r@pe"). '1' is deliberately folded to 'l' (not
                    // 'i'): the two are genuinely ambiguous in real leetspeak use, and 'l' is the
                    // asymmetry that actually appears in the recorded samples; a case needing the
                    // other reading has not been observed and should be added as its own entry rather
                    // than guessed at here.
                    Map.entry((int) '0', (int) 'o'),
                    Map.entry((int) '1', (int) 'l'),
                    Map.entry((int) '3', (int) 'e'),
                    Map.entry((int) '@', (int) 'a'));

    /**
     * Letters (General_Category=Lo — {@link Character#isLetterOrDigit} says {@code true}) that
     * render as blank space: the Hangul filler jamo, used in real spam/evasion the way a
     * zero-width joiner is (F-0856, Kabir's probe: "Hangul filler U+3164"). Unlike a genuine letter
     * these must NOT survive into {@link NormalizedText#compact()} at all and must NOT end the
     * current token — see rule 2 in {@link #normalizeForMatching}. Kept as its own tiny set rather
     * than special-cased by code point so the "why" is documented once, here.
     */
    private static final Set<Integer> INVISIBLE_LETTER_LIKE =
            Set.of(
                    0x115F, // HANGUL CHOSEONG FILLER
                    0x1160, // HANGUL JUNGSEONG FILLER
                    0x3164, // HANGUL FILLER
                    0xFFA0); // HALFWIDTH HANGUL FILLER

    /** The suffixes {@link #matchesTerm} generates from every term's compact form (F-0853/F-0855,
     * decision 2026-09-18). Order does not matter; each is tried independently.
     *
     * <p><b>Must stay declared ABOVE the anti-vacuity {@code static} guard below.</b> That guard
     * calls {@code isQuotableInCreatorCopy}/{@code firstUnsafeTopic} on real terms as part of class
     * initialization, which reaches {@link #matchesTerm}; static fields initialize in textual
     * order, so if this were declared after the guard block it would still be {@code null} when
     * the guard runs — the anti-vacuity check would NullPointerException the whole class out of
     * existence rather than validate anything. (Caught by this exact failure while building the
     * 2026-09-18 decision's filter — recorded here so it is not reintroduced.) */
    private static final String[] GENERATED_SUFFIXES = {"s", "es", "ed", "ing", "er", "ers"};

    /** Generated forms that must never count as a match — see {@link #matchesTerm}'s javadoc. Kept
     * immediately next to the generator, not folded silently into a term set, so the exclusion
     * stays visible at the exact point that could reintroduce it. Same declaration-order
     * requirement as {@link #GENERATED_SUFFIXES} above. */
    private static final Set<String> GENERATED_FORM_BLOCKLIST = Set.of("killer", "killers");

    static {
        // Anti-vacuity guard. Every term must already be in matching-normal form (lowercase,
        // single-spaced, letters/digits only), because matching is done against a normalized
        // headline — a term written as "Hate Speech" or "hate  speech" would compile, ship, and
        // NEVER match anything, i.e. the filter would silently lose a category with no test able to
        // notice unless it happened to cover that exact term. Fail at class load instead. Same
        // discipline as CreatorNudgeServiceTest's ULID-length static guard.
        for (UnsafeHeadlineTopic topic : UnsafeHeadlineTopic.values()) {
            if (topic.terms().isEmpty()) {
                throw new IllegalStateException("unsafe-topic category has no terms: " + topic);
            }
            for (String term : topic.terms()) {
                NormalizedText normalizedTerm = normalizeForMatching(term);
                if (!term.equals(normalizedTerm.spaced())) {
                    throw new IllegalStateException(
                            "unsafe-topic term is not in matching-normal form and could never match: '"
                                    + term
                                    + "' (" + topic + ")");
                }
                // F-0826/F-0827: stronger than the form check above — every term must actually
                // match ITSELF through the real matcher. A term whose form is legal but which the
                // boundary logic can never match (e.g. a leading/trailing digit rule change) would
                // otherwise ship as a silently dead entry.
                if (!containsTerm(normalizedTerm, compactTerm(term))) {
                    throw new IllegalStateException(
                            "unsafe-topic term does not match itself through the matcher — it is dead: '"
                                    + term
                                    + "' (" + topic + ")");
                }
            }
        }
        // F-0825: the last-resort degrade must itself pass the gate, or suppression would persist
        // copy the gate objects to. genericFallback's non-name text is a compile-time constant, so
        // this is checkable once, here, rather than hoped for.
        SuggestionCopy lastResort = genericFallback(NO_NAME);
        if (!isQuotableInCreatorCopy(lastResort.headline())
                || !isQuotableInCreatorCopy(lastResort.contentIdea())) {
            throw new IllegalStateException(
                    "genericFallback copy is itself unquotable — the suppression path has no safe"
                            + " degrade left");
        }
    }

    /**
     * The single decision point for "may this text appear in creator copy?" — applied in {@link
     * #getSuggestion} to {@code headline}, {@code contentIdea} and {@code theme} alike (F-0825).
     *
     * <p><b>Fails closed</b> on {@code null}, on blank, and — the LOW-6 case — on a string that is
     * non-blank to {@code String.isBlank()} but carries no matchable character at all, such as a
     * headline made entirely of zero-width joiners. {@code isBlank()} answers false for that string
     * (a format character is not whitespace), which used to make an all-invisible headline
     * "quotable" and produce copy containing an empty-looking quotation. The emptiness test is
     * therefore made on the NORMALIZED form, after format characters and combining marks have been
     * removed, not on the raw input.
     *
     * <p>Package-private, not private, for the same reason {@link #firstUnsafeTopic} is: the
     * evasion suites in {@code CreatorNudgeServiceTest} reproduce Kabir's method by calling the
     * decision function directly with each bypass string. Driving 40-odd Unicode and inflection
     * cases through {@code getSuggestion} and its six mocks instead would bury what is being
     * asserted, and a behavioural-only suite cannot distinguish "the gate rejected it" from "some
     * unrelated branch happened to produce generic copy".
     */
    static boolean isQuotableInCreatorCopy(String text) {
        if (text == null) {
            return false;
        }
        NormalizedText normalized = normalizeForMatching(text);
        if (normalized.compact().isEmpty()) {
            return false;
        }
        return firstUnsafeTopic(normalized) == null;
    }

    /**
     * First category that disqualifies {@code headline}, or {@code null} if none matched.
     *
     * <p><b>{@code null} here does NOT mean "safe".</b> A null, blank or all-invisible headline
     * also returns {@code null} (nothing to match against). Callers must go through {@link
     * #isQuotableInCreatorCopy}, which fails closed on all three; this method is separate only so
     * the suppression log line can name the category.
     */
    static UnsafeHeadlineTopic firstUnsafeTopic(String headline) {
        if (headline == null) {
            return null;
        }
        return firstUnsafeTopic(normalizeForMatching(headline));
    }

    /**
     * Matching is TOKEN-BOUNDARY-ANCHORED CONTAINMENT over the separator-free form of the text —
     * not raw {@code String.contains}, and no longer the {@code " term "} padded-token search
     * F-0786 shipped. A term matches when its separator-free form occurs in the headline's
     * separator-free form AND that occurrence <b>begins at the first character of some token and
     * ends at the last character of some token</b>.
     *
     * <p><b>Why the change (F-0827).</b> Padded-token search treats every separator as a hard
     * break, which is exactly what made {@code "m.u.r.d.e.r"} — and, before normalization was
     * fixed, {@code "mur<ZWSP>der"} — invisible to it: the term spans tokens, so it can never equal
     * one. Anchoring on token START and token END instead keeps every word-boundary property the
     * old scheme had while ignoring separators INSIDE a match:
     *
     * <ul>
     *   <li>{@code "sue"} still does not match {@code "issue"} or {@code "pursue"} — the occurrence
     *       does not begin at a token start.
     *   <li>{@code "kill"} still does not match {@code "killer"}, and {@code "bomb"} does not match
     *       {@code "Bombay"} or {@code "photobomb"} — the occurrence does not end at a token end.
     *   <li>{@code "die"} still does not match {@code "audience"}; {@code "murder,"} / {@code
     *       "Murder"} / {@code "murder's"} still match {@code murder}.
     *   <li>Multi-word terms need no special case: {@code "hate speech"} is simply the compact
     *       {@code "hatespeech"} beginning at one token's start and ending at another's end, which
     *       also makes {@code "self-harm"} and {@code "self harm"} the same single term.
     * </ul>
     *
     * <p>Still no regex — no escaping hazard, no catastrophic-backtracking surface on
     * attacker-influenced input, no per-call {@code Pattern} compilation.
     *
     * <p><b>Accepted cost, stated rather than discovered later:</b> because separators inside a
     * match are ignored, a genuine two-word phrase that concatenates into a term now matches —
     * {@code "host age"} trips {@code hostage}. That is the same asymmetry the term list is built
     * on, and it is why the anchors are required at BOTH ends rather than dropped entirely (which
     * would have made {@code "issue"} trip {@code sue}).
     *
     * <p>{@code values()} order (DEATH, CRIME, COMMUNAL, LEGAL) is the reported-category order;
     * which one wins is a logging detail only — any match at all suppresses the text.
     */
    private static UnsafeHeadlineTopic firstUnsafeTopic(NormalizedText normalized) {
        if (normalized.compact().isEmpty()) {
            return null;
        }
        for (UnsafeHeadlineTopic topic : UnsafeHeadlineTopic.values()) {
            for (String term : topic.terms()) {
                if (matchesTerm(normalized, term)) {
                    return topic;
                }
            }
        }
        return null;
    }

    /**
     * F-0832/F-0837/F-0853/F-0855 (decision 2026-09-18) — a term matches in its listed form, or
     * with a generated inflection appended to its compact form: {@code s}, {@code es}, a
     * consonant-{@code y}→{@code ies} swap, {@code ed}, {@code ing}, {@code er} or {@code ers}. This
     * applies to single words and multi-word phrases alike.
     *
     * <p><b>History: this used to be multi-word-only, and plurals-only.</b> The token-END anchor in
     * {@link #containsTerm} is what keeps {@code kill} out of {@code killer}; applied to a phrase it
     * also kept {@code school shooting} out of {@code school shootings}, so every phrase was blocked
     * in the singular and passed in the plural ("mass shootings", "acid attacks", "hate crimes").
     * F-0837 fixed that with a hand-picked list of plurals that missed some; F-0853/F-0855 then
     * found the same failure mode one level up — {@code self harm} listed but not
     * "self-harming" (F-0853), {@code terrorist}/{@code riot} listed but not "terrorists"/
     * "rioters" (F-0855) — because single words had NO suffix rule at all, only whatever inflected
     * forms happened to be typed into {@link UnsafeHeadlineTopic} by hand. Generating from a small
     * fixed suffix set, for every term, is what stops the next inflection from being a new ticket.
     *
     * <p><b>{@link #GENERATED_FORM_BLOCKLIST} is the one manual guard this needs.</b> Generation is
     * mechanical and does not know which resulting strings are this class's own documented
     * accepted-false-positive exclusions. {@code kill} + {@code er}/{@code ers} regenerates exactly
     * {@code killer}/{@code killers} — the false positive "Killer ab workout routine goes viral" is
     * pinned safe elsewhere in this file — by the same rule that correctly turns {@code riot} into
     * {@code rioter}/{@code rioters}. There is no way to tell those two cases apart from the
     * suffix rule alone; the blocklist is the difference.
     *
     * <p><b>Why {@code es} unconditionally, and no consonant doubling.</b> Accepting {@code es}
     * regardless of the term's last letter costs nothing: a non-word like "high courtes" still has
     * to begin and end on token boundaries to match anything. Consonant doubling ({@code
     * scam}→{@code scamming}) and {@code e}-dropping before {@code ing} ({@code police}→{@code
     * policing}) are NOT applied — this stays a small, auditable set of literal suffix strings, not
     * a spelling engine, so some correctly-spelled inflections are still missed. That is an accepted
     * gap, not a silent one: a form that needs it must be listed explicitly, same as any irregular
     * form ({@code slain}, not generated from {@code slay}).
     */
    private static boolean matchesTerm(NormalizedText normalized, String term) {
        String compact = compactTerm(term);
        if (containsTerm(normalized, compact)) {
            return true;
        }
        for (String suffix : GENERATED_SUFFIXES) {
            String candidate = compact + suffix;
            if (!GENERATED_FORM_BLOCKLIST.contains(candidate) && containsTerm(normalized, candidate)) {
                return true;
            }
        }
        int n = compact.length();
        if (n >= 2 && compact.charAt(n - 1) == 'y' && "aeiou".indexOf(compact.charAt(n - 2)) < 0) {
            String iesForm = compact.substring(0, n - 1) + "ies";
            if (!GENERATED_FORM_BLOCKLIST.contains(iesForm) && containsTerm(normalized, iesForm)) {
                return true;
            }
        }
        return false;
    }

    private static String compactTerm(String term) {
        return term.indexOf(' ') < 0 ? term : term.replace(" ", "");
    }

    private static boolean containsTerm(NormalizedText normalized, String compactTerm) {
        String compact = normalized.compact();
        int from = 0;
        while (true) {
            int start = compact.indexOf(compactTerm, from);
            if (start < 0) {
                return false;
            }
            int end = start + compactTerm.length() - 1;
            if (normalized.tokenStart().get(start) && normalized.tokenEnd().get(end)) {
                return true;
            }
            from = start + 1;
        }
    }

    /**
     * The separator-free, lowercase, fold-normalized form of a text, plus the token-start and
     * token-end positions within it. Indices are positions in {@link #compact()}.
     *
     * <p>{@link #spaced()} is the same tokens joined by single spaces — used ONLY by the class-load
     * term-form guard, never by matching.
     */
    private record NormalizedText(String compact, String spaced, BitSet tokenStart, BitSet tokenEnd) {}

    /**
     * Reduces {@code text} to matchable tokens. <b>This method is the filter's entire defence
     * against evasion; read the four rules before editing it.</b>
     *
     * <p><b>1. NFKC first (F-0827).</b> {@code Normalizer.Form.NFKC} folds compatibility variants
     * onto their plain equivalents, which is what kills the whole family of "styled" bypasses:
     * fullwidth {@code ｍｕｒｄｅｒ}, math-bold {@code 𝐫𝐚𝐩𝐞}, circled and superscript letters. This is
     * not a hypothetical channel — YouTube video titles are one of the three trend sources, they
     * are user-authored, and they already carry fullwidth and stylized Unicode as ordinary creator
     * styling. A second pass in {@code Form.NFD} follows so that accents which NFKC would have
     * COMPOSED (e.g. {@code e} + U+0301 becoming {@code é}) are decomposed back into a base letter
     * plus a combining mark, which rule 2 then strips. NFKC alone does not handle {@code
     * "mu<combining diaeresis>rder"}: it composes it to {@code "mürder"} and {@code ü} is a
     * perfectly good letter that is not {@code u}.
     *
     * <p><b>2. Format characters and combining marks VANISH; they do not separate.</b> {@code
     * Character.isLetterOrDigit} is false for zero-width and combining characters, so the previous
     * implementation treated them as separators — which is worse than useless here: {@code
     * "mur<ZWSP>der"} became the two tokens {@code mur} and {@code der}, neither of which matches
     * {@code murder}, so inserting an invisible character DEFEATED the filter rather than being
     * ignored by it. Characters of type {@code FORMAT} (U+200B ZWSP, U+200C ZWNJ, U+200D ZWJ,
     * U+00AD soft hyphen, U+FEFF BOM, U+2060 word joiner, U+202E RTL override and the rest) and of
     * the three combining-mark types are skipped WITHOUT breaking the current token.
     *
     * <p><b>3. A small, targeted confusables fold.</b> {@link #CONFUSABLE_FOLD} maps the handful of
     * Cyrillic and Greek characters that are visually identical to Latin letters onto those Latin
     * letters, so {@code "mur<Cyrillic de>..."}-style homoglyph substitution fails. It is
     * deliberately NOT a full Unicode confusables table: those are large, carry their own
     * false-positive behaviour, and would be a dependency to keep current. The cost of this choice
     * is real and is stated in {@link UnsafeHeadlineTopic}'s residuals — a homoglyph outside this
     * table still evades.
     *
     * <p><b>4. {@code Character.toLowerCase(int)}, never {@code String.toLowerCase()}.</b> The
     * no-arg form applies the JVM's DEFAULT LOCALE, which under a Turkish locale maps 'I' to the
     * dotless 'ı' and would stop {@code "INDICTED"} from ever matching {@code "indicted"} — a
     * filter bypass that depends on nothing but the server's locale setting. The lowercase happens
     * BEFORE the fold so that the table only needs lowercase keys (Cyrillic А U+0410 lowercases to
     * а U+0430, which the table then maps to Latin {@code a}).
     *
     * <p>Iteration is by code point, not by {@code char}, so supplementary-plane input is never
     * split across surrogates.
     */
    private static NormalizedText normalizeForMatching(String text) {
        String folded =
                Normalizer.normalize(
                        Normalizer.normalize(text, Normalizer.Form.NFKC), Normalizer.Form.NFD);

        StringBuilder compact = new StringBuilder(folded.length());
        StringBuilder spaced = new StringBuilder(folded.length() + 1);
        BitSet tokenStart = new BitSet();
        BitSet tokenEnd = new BitSet();
        boolean inToken = false;
        // F-0855 (decision 2026-09-18) — tracks whether the character just appended was lowercase
        // or a digit, so a following uppercase letter can be recognised as a camelCase/hashtag
        // boundary. See rule 5 below.
        boolean previousAppendedWasLowerOrDigit = false;

        for (int i = 0; i < folded.length(); ) {
            int cp = folded.codePointAt(i);
            i += Character.charCount(cp);

            int type = Character.getType(cp);
            if (type == Character.FORMAT
                    || type == Character.NON_SPACING_MARK
                    || type == Character.COMBINING_SPACING_MARK
                    || type == Character.ENCLOSING_MARK
                    || INVISIBLE_LETTER_LIKE.contains(cp)) {
                // Rule 2 (extended by F-0856): vanish, do NOT end the token. The Hangul fillers are
                // letters to Character.isLetterOrDigit but render as blank space — see
                // INVISIBLE_LETTER_LIKE's javadoc for why they must be treated like a zero-width
                // joiner rather than either a token member or a separator.
                continue;
            }

            // F-0855 — captured BEFORE case-folding, since it is the source text's own casing (not
            // the confusable-folded result) that signals a camelCase/hashtag boundary.
            boolean isUpperBeforeFold = Character.isUpperCase(cp);
            int lowered = Character.toLowerCase(cp);
            int normalizedCp = CONFUSABLE_FOLD.getOrDefault(lowered, lowered);

            if (Character.isLetterOrDigit(normalizedCp)) {
                // Rule 5 (F-0855) — hashtag / camelCase / joined-word splitting. A lowercase-or-digit
                // character immediately followed by an uppercase one is treated as BOTH a token end
                // (for the run just finished) and a token start (for the one beginning here), even
                // though the source text has no separator between them. This is what lets
                // "#DelhiRiots" match the term "riots" and "#GangRape" match "rape": '#' already
                // starts a fresh token at "DelhiRiots", and without this rule that whole run is ONE
                // token — a term anchored on token boundaries (see matchesTerm/containsTerm) could
                // then only ever match the full "delhiriots", never "riots" alone. All-caps runs
                // ("GANGRAPE") and all-lowercase runs ("delhiriots") carry no case-transition signal
                // and are NOT split by this rule; that is an accepted, stated gap, not an oversight.
                boolean camelBoundary = inToken && isUpperBeforeFold && previousAppendedWasLowerOrDigit;
                if (!inToken || camelBoundary) {
                    if (camelBoundary) {
                        tokenEnd.set(compact.length() - 1);
                    }
                    tokenStart.set(compact.length());
                    if (spaced.length() > 0) {
                        spaced.append(' ');
                    }
                    inToken = true;
                }
                compact.appendCodePoint(normalizedCp);
                spaced.appendCodePoint(normalizedCp);
                previousAppendedWasLowerOrDigit = !isUpperBeforeFold;
            } else if (inToken) {
                tokenEnd.set(compact.length() - 1);
                inToken = false;
                previousAppendedWasLowerOrDigit = false;
            }
        }
        if (inToken) {
            tokenEnd.set(compact.length() - 1);
        }
        return new NormalizedText(compact.toString(), spaced.toString(), tokenStart, tokenEnd);
    }



    private String displayName(CreatorProfile profile) {
        String name = profile.getDisplayName();
        return (name == null || name.isBlank()) ? NO_NAME : name;
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
