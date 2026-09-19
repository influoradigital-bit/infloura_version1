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
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
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
     *       mob"). <b>T-GOLIVE-0918-R4 additions:</b> Devanagari {@code कातिल} (qatil, "killer") —
     *       the exact Hindi-Urdu translation of the excluded {@code killer} above, with the
     *       identical romantic/song-lyric false-positive rate ("कातिल अदाएं", "कातिल निगाहें");
     *       bare Latin {@code phansi}/{@code fansi} — transliterate identically to both फांसी
     *       ("hanging", unsafe) and फंसना ("to get stuck", benign); bare {@code man shot at}/
     *       {@code woman shot at}/{@code opened fire} — the plain-news "&lt;victim&gt; shot at
     *       &lt;place&gt;" register is lexically identical to photography "shot at
     *       &lt;location/time&gt;" ("Woman shot at golden hour on 85mm"), and "opened fire"
     *       collides with the Indian gadget brand "Fire-Boltt" ("opened Fire-Boltt Ninja 3
     *       unboxing"); bare {@code goli maar di}/{@code maar diya gaya}/{@code को मार डाला} —
     *       collide with Hinglish idiom, cricket-commentary and hyperbolic-song usage ("Tension ko
     *       goli maar di, weekend vibes", "chhakka maar diya gaya", "इस गाने ने दिल को मार डाला");
     *       bare {@code hanged} — collides with the ordinary transitive "hang" ("Hanged fairy
     *       lights for Diwali decor" — kept only as the qualified "hanged himself"/"hanged
     *       herself"); bare {@code body found} — collides with fitness idiom ("My body found its
     *       rhythm with Pilates", "Your dream body found in 30 days") with no available qualifier
     *       that keeps the crime-discovery sense apart.
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
                        // probe: "atmahatya" = suicide.
                        "atmahatya",
                        // F-0857 repair round 1 (vikram · 2026-09-18) — "aatmahatya" is the other
                        // common Latin-script spelling (double vowel) of the same word; and "maut"
                        // (death) closes an outright gap Kabir's probe named ("maut").
                        "aatmahatya", "maut",
                        // F-0857 repair round 1 — Devanagari coverage: आत्महत्या (aatmahatya,
                        // suicide), CORRECTLY SPELLED with its matras and virama intact.
                        //
                        // T-GOLIVE-0918-R2 (vikram · 2026-09-18) — repair round 2 REWRITES this from
                        // the stripped consonant-skeleton form ("आतमहतय") 927002a/49a0415 shipped to
                        // the full, correctly-spelled word. The skeleton technique is what caused the
                        // COMMUNAL over-block a reviewer found (see isDevanagariCombiningMark's
                        // javadoc): normalizeForMatching no longer strips Devanagari vowel signs/
                        // virama/anusvara, so a term written as a stripped skeleton would now FAIL
                        // its own class-load self-check (it could never equal its own normalized
                        // form) — every Devanagari term below is therefore the real, correctly-
                        // spelled word, exactly like Latin terms always were.
                        "आत्महत्या",
                        // F-0857 repair round 1 (vikram · 2026-09-18) — an independent reviewer's
                        // probe found "Woman found hanging" bypassing; this is routine Indian-news
                        // phrasing for a death/suicide discovery, distinct from the DEATH set's
                        // existing "hanging"-free vocabulary.
                        "found hanging",
                        // T-GOLIVE-0918-R2 (vikram · 2026-09-18) — repair round 2. Devanagari/Latin
                        // vocabulary gaps an independent reviewer's probe named outright: मौत (maut,
                        // death) is the Devanagari spelling used in routine Indian-news phrasing
                        // ("हादसे में मौत" — died in an accident); खुदकुशी (khudkushi) is a common
                        // Hindi/Urdu register word for suicide, distinct from आत्महत्या above; the
                        // Latin spellings "aatmhatya"/"atmhatya" and "khudkushi" are the Hinglish
                        // counterparts (dropped-vowel "mh" cluster, not covered by the existing
                        // "aatmahatya"/"atmahatya" entries which keep the vowel). "hanged" is the
                        // routine Indian-news self-harm/death verb ("Man hanged himself in hostel");
                        // "body found" is the routine discovery-of-a-death headline pattern ("Body
                        // found in suitcase") — accepted as a phrase (both words required) for the
                        // same reason "school shooting" was accepted for a bare-excluded word: the
                        // combination is far more specific than either word alone. Source:
                        // wiki/decisions/2026-09-18-trend-headline-screening.md.
                        "मौत", "खुदकुशी", "aatmhatya", "atmhatya", "khudkushi",
                        // T-GOLIVE-0918-R4 (vikram · 2026-09-19) — repair round 4 MEDIUM fix. Bare
                        // "hanged" and "body found" are REMOVED: an independent reviewer's probe found
                        // both over-blocking ordinary creator content that has nothing to do with a
                        // death — "Hanged fairy lights for Diwali decor" (the ordinary transitive verb
                        // "hang" applied to decorations, not a person) and "My body found its rhythm
                        // with Pilates"/"Your dream body found in 30 days" (routine fitness-content
                        // idiom "body found X"). "hanged" is kept ONLY as the qualified phrases
                        // "hanged himself"/"hanged herself" below — the reflexive object is what makes
                        // the self-harm reading unambiguous ("Man hanged himself in hostel" still
                        // blocks; "Hanged fairy lights" has no "himself"/"herself" to match). "body
                        // found" has no comparable qualifier that keeps the crime-discovery sense
                        // ("Body found in suitcase") separate from the fitness idiom — both say
                        // "body found in <noun phrase>" — so it is dropped outright, same discipline
                        // as excluding bare "killer"/"shot"/"attack": the false-positive rate in this
                        // product's actual creator-content niche is too high. ACCEPTED GAP: a bare
                        // "Body found in <place>" headline with no other DEATH/CRIME vocabulary no
                        // longer blocks on its own (see
                        // firstUnsafeTopic_repairRound4AcceptedGapsStayQuotable in the test suite).
                        "hanged himself", "hanged herself",
                        // T-GOLIVE-0918-R4 — vocabulary gaps an independent reviewer's probe named
                        // outright: सुसाइड is the Devanagari transliteration of "suicide" itself,
                        // routinely used in Hindi entertainment/crime headlines ("सुसाइड नोट में बड़ा
                        // खुलासा") and distinct from the already-listed आत्महत्या/खुदकुशी (both
                        // native-register words, not the transliterated loanword); मारे गए ("were
                        // killed", passive plural) is routine Indian-news phrasing ("हादसे में 5 लोग
                        // मारे गए") not reachable from any already-listed मार/मार डाला vocabulary;
                        // निधन (nidhan, "demise/passing away", the formal register used for a
                        // public figure's death) closes an outright gap ("दिग्गज अभिनेता का निधन").
                        "सुसाइड", "मारे गए", "निधन",
                        // T-GOLIVE-0918-R2 repair round 2 (vikram · 2026-09-18) — HIGH regression fix.
                        // Devanagari has no suffix generator (GENERATED_SUFFIXES is ASCII-only), so
                        // every grammatical inflection of a Devanagari term has to be its own literal,
                        // same discipline as हत्यारा/हत्याओं above. मौतें (direct plural of मौत) and
                        // मौतों (oblique plural) are DIFFERENT token sequences from मौत's bare form —
                        // the trailing vowel sign/anusvara extends the token past मौत's own end anchor
                        // now that isDevanagariCombiningMark keeps marks instead of stripping them —
                        // so मौत alone cannot match inside either. आत्महत्याओं is the same fix applied
                        // to आत्महत्या's plural ("suicides"). An independent reviewer's probe found
                        // "हादसे में 5 लोगों की मौतें", "कोविड से मौतों का आंकड़ा" and "आत्महत्याओं"
                        // bypassing 72cabea. Source: wiki/decisions/2026-09-18-trend-headline-screening.md.
                        "मौतें", "मौतों", "आत्महत्याओं",
                        // T-GOLIVE-0918-R3 (vikram · 2026-09-18) — repair round 3 MEDIUM fix.
                        // "मौते" is an informal/colloquial plural spelling of मौत (deaths) distinct
                        // from the standard "मौतें" above — an independent reviewer's probe found
                        // "हादसे में 3 की मौते" bypassing. "आत्महत्याएं" is the direct-plural
                        // counterpart of the already-listed oblique plural "आत्महत्याओं" (same
                        // direct-vs-oblique pattern as हत्याएं/हत्याओं in the CRIME set) — the
                        // same probe found "आत्महत्याएं बढ़ीं" bypassing.
                        "मौते", "आत्महत्याएं",
                        // T-GOLIVE-0918-R2 repair round 2 — MEDIUM vocabulary gap: फांसी (phansi,
                        // hanging/execution by hanging) is routine Indian-news self-harm/death
                        // phrasing distinct from the DEATH set's existing "hanged"/"found hanging"
                        // entries (those are English; this is the Devanagari word itself, e.g. "फांसी
                        // लगाकर जान दी" — took their own life by hanging). Kept bare — unlike its
                        // Latin transliteration below, the Devanagari spelling itself is what
                        // disambiguates it from the unrelated फंसना ("to get stuck"); there is no
                        // separate Devanagari spelling for "stuck" that collides with this one.
                        "फांसी",
                        // T-GOLIVE-0918-R4 (vikram · 2026-09-19) — repair round 4 MEDIUM fix. Bare
                        // Latin "fansi"/"phansi" are REMOVED: an independent reviewer's probe found
                        // them over-blocking the everyday Hinglish word फंसना ("to get stuck"), which
                        // transliterates IDENTICALLY to फांसी ("hanging") in Latin script — "Traffic
                        // mein phansi hui thi 2 ghante" and "Exam ke chakkar mein fansi rahi" were
                        // quotable at 49a0415 and were wrongly blocked once the bare Latin forms were
                        // added in round 2/3. Unlike फांसी's Devanagari spelling (kept bare above),
                        // there is no separate Latin spelling for the two senses, so the qualified
                        // hanging-specific collocations below — which only ever occur in the hanging
                        // sense, never the "stuck" sense — replace the bare forms, same discipline as
                        // bare "छेड़छाड़"'s round-3 removal. The four cover every currently-attested
                        // spelling/helper-verb combination (round-2's "fansi laga ke jaan de di" and
                        // round-3's "phansi laga li" probes both still block through these).
                        "phansi laga li", "phansi lagakar", "phansi laga ke",
                        "fansi laga li", "fansi lagakar", "fansi laga ke",
                        // T-GOLIVE-0918-R3 (vikram · 2026-09-18) — repair round 3 MEDIUM fix:
                        // "khudkhushi" (double-h transliteration, distinct from the already-listed
                        // single-h "khudkushi"). An independent reviewer's probe found "khudkhushi kar
                        // li" bypassing. Not flagged as ambiguous the way phansi/fansi are — "khudkushi"
                        // /"khudkhushi" has no attested unrelated benign meaning — so it stays bare.
                        "khudkhushi",
                        // T-GOLIVE-0918-R2 repair round 2 — MEDIUM: "unalive"/"unalived" is
                        // algorithm-evasion slang for "kill"/"killed"/"suicide" (an independent
                        // reviewer's probe: "unalived himself"), in wide use on video platforms
                        // specifically because it dodges literal death/suicide vocabulary — exactly
                        // the class of term this filter exists to catch. Ends in a bare "e", so
                        // matchesTerm's e-drop rule already generates "unalived"/"unaliving" for free.
                        "unalive")),
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
                        // F-0857 repair round 1 (vikram · 2026-09-18) — Hinglish inflections/
                        // compounds Kabir's probe also named: "hatyakand" (murder incident/case),
                        // "balatkari" (rapist), "qatl" (killing/murder, Urdu-Hindi register common
                        // in Indian crime reporting).
                        "hatyakand", "balatkari", "qatl",
                        // F-0857 repair round 1 — Devanagari coverage: हत्या (hatya, murder/
                        // killing), बलात्कार (balatkar, rape), CORRECTLY SPELLED.
                        //
                        // T-GOLIVE-0918-R2 (vikram · 2026-09-18) — repair round 2 REWRITES these
                        // from the stripped consonant-skeleton forms ("हतय"/"बलतकर") to the full,
                        // correctly-spelled words — see isDevanagariCombiningMark's javadoc for why
                        // the skeleton technique was itself the bug (it collapsed दंगा/देगा/देंगे/
                        // दूंगा/दाग onto one shape) and DEATH's आत्महत्या entry above for the same
                        // rewrite applied there.
                        "हत्या", "बलात्कार",
                        // F-0855/2026-09-18 decision — Indian-English news vocabulary Kabir's probe
                        // named outright: an FIR (First Information Report) being lodged is how an
                        // Indian crime story is reported ahead of any arrest or verdict.
                        "fir lodged",
                        // F-0857 repair round 1 — an independent reviewer's probe found these
                        // bypassing. Not reachable via GENERATED_SUFFIXES: "kidnapper"/"scammer"
                        // double their final consonant (kidnap+er would generate "kidnaper", not
                        // "kidnapper") and "fraudster" is an irregular agent-noun formation, so all
                        // three are listed as their own literals rather than chasing consonant-
                        // doubling as a general rule.
                        "kidnapper", "kidnappers", "scammer", "scammers", "fraudster", "fraudsters",
                        // F-0857 repair round 1 — Indian crime-reporting vocabulary an independent
                        // reviewer's probe found bypassing: a chargesheet is the formal charge
                        // document Indian police file, distinct from (and reported well before) a
                        // court "indictment"/"conviction" already covered above; "gunned down" is
                        // routine Indian-news phrasing for a fatal shooting that bare "gunman"/
                        // "gunfire"/"shootout" above do not catch.
                        "chargesheet", "chargesheeted", "gunned down",
                        // T-GOLIVE-0918-R2 (vikram · 2026-09-18) — repair round 2. Vocabulary gaps
                        // an independent reviewer's probe named outright against 49a0415.
                        //
                        // "gangrape" is listed as its own literal, ONE WORD: Indian outlets
                        // routinely write it that way ("Gangrape accused held in UP"), not as two
                        // words, and it is not reachable from "rape" by any generated suffix. Its
                        // own inflection ("gangraped") IS reachable — "gangrape" ends in a bare "e",
                        // so matchesTerm's e-dropping rule already generates "gangraped" for free.
                        //
                        // "strangle" is the base form of a killing method in the same bucket as the
                        // existing "stabbed"/"stabbing" pair ("Woman strangled by husband"); it also
                        // ends in "e", so "strangled"/"strangling"/"strangles" are all generated,
                        // not listed separately.
                        //
                        // "molester"/"molesters" close an irregular agent-noun gap: the base
                        // "molest" was never listed (only "molested"/"molesting"/"molestation"
                        // are), so no suffix rule reaches "molester" ("Molester thrashed by crowd").
                        //
                        // "murderous" is an adjective form of "murder" not reachable by any listed
                        // suffix rule ("Murderous attack on journalist").
                        //
                        // "custodial torture" and "set ablaze" are Indian crime-reporting phrases
                        // named by the probe ("Custodial torture case", "Woman set ablaze by
                        // stalker") with no safe single-word equivalent to add instead.
                        //
                        // T-GOLIVE-0918-R2 repair round 2 (vikram · 2026-09-18) — MEDIUM fix: bare
                        // "shot at" (added above, this same ticket) over-blocked ordinary creator
                        // filming language — "Reel shot at Marine Drive", "Shot at golden hour on
                        // iPhone 15" and "This whole vlog was shot at home" are all camera-usage
                        // "shot at <place/time>", not gunfire, and were QUOTABLE before this term was
                        // added. An independent reviewer's probe found the over-block. Per this
                        // class's own javadoc excluding bare "shot"/"shooting" for exactly this
                        // reason, "shot at" is replaced with the narrower "shot at by" (gunfire
                        // followed by its attacker, "Man shot at by gunman outside mall") and
                        // "opened fire" (routine Indian-news phrasing for a shooting, "Gunman opened
                        // fire outside mall"). "shot dead" needs no separate entry — it already
                        // matches via the bare "dead" term above.
                        // T-GOLIVE-0918-R3 (vikram · 2026-09-18) — repair round 3 MEDIUM fix. "shot
                        // at by" alone regressed the plain-news form of the same story: "Man shot at
                        // outside mall" was BLOCKED at 72cabea (bare "shot at") and BYPASSED once
                        // round 2 narrowed it to require "by <attacker>" — an independent reviewer's
                        // probe found this. "man shot at"/"woman shot at" (victim-noun immediately
                        // before "shot at") restores that Indian-news form specifically, without
                        // reintroducing the camera-usage over-block the narrowing was fixing:
                        // "Reel shot at Marine Drive", "Shot at golden hour on iPhone 15" and "This
                        // whole vlog was shot at home" have no "man"/"woman" immediately before
                        // "shot at", so none of them gain a new match.
                        // T-GOLIVE-0918-R4 (vikram · 2026-09-19) — repair round 4 MEDIUM fix: round
                        // 3's bare "man shot at"/"woman shot at" and this same round's "opened fire"
                        // are REMOVED. An independent reviewer's probe found all three over-blocking
                        // ordinary creator content: "Woman shot at golden hour on 85mm", "Street
                        // portrait: man shot at Chandni Chowk" and "Old man shot at sunset on 35mm
                        // film" are photography-usage "shot at <location/time>", lexically identical
                        // to the crime-reporting register these terms were added for; "Just opened
                        // Fire-Boltt Ninja 3 unboxing" collides with a real, widely-used Indian
                        // smartwatch brand. Unlike the round-2 "bare shot at" narrowing (which had
                        // "shot at by" as a safe, unambiguous replacement), there is no phrase-only
                        // fix here that keeps blocking a bare-victim-noun shooting headline without
                        // also matching a bare-victim-noun photography caption — the two share
                        // identical surface grammar. ACCEPTED GAP, same discipline as excluding bare
                        // "killer"/"shot"/"attack"/"clash"/"mob"/"court" above (see this enum's own
                        // "Terms deliberately EXCLUDED" note): "Man/Woman shot at outside
                        // mall/market" with no other CRIME vocabulary no longer blocks on its own
                        // (see firstUnsafeTopic_repairRound4AcceptedGapsStayQuotable). "shot at by"
                        // (unambiguous — names the attacker) and "gunman"/"gunfire"/"shootout"/
                        // "gunned down" (bare, unaffected) still catch the genuine crime register
                        // whenever the headline names the attacker or uses gun-specific vocabulary.
                        "gangrape", "strangle", "shot at by", "molester", "molesters",
                        "murderous", "custodial torture", "set ablaze",
                        // Devanagari coverage (correctly spelled, per isDevanagariCombiningMark):
                        // गैंगरेप (gangrape) is its own literal because it is one fused token —
                        // रेप alone cannot match inside it (containsTerm requires BOTH a token-start
                        // and token-end anchor, and गैंग precedes रेप within the same token);
                        // हत्याकांड (hatyakand, murder case/incident, the Devanagari form of the
                        // existing Latin "hatyakand"); हत्यारा (hatyara, murderer); हत्याओं
                        // (hatyaon, murders — plural/oblique of हत्या, its own literal for the same
                        // reason गैंगरेप is: more letters follow हत्या within the same token, so the
                        // end anchor never lands on हत्या alone); कत्ल (qatl).
                        //
                        // T-GOLIVE-0918-R3 (vikram · 2026-09-18) — repair round 3. "क़त्ल" (with
                        // nukta) is REMOVED from this list: normalizeForMatching now folds the nukta
                        // away (see DEVANAGARI_NUKTA's javadoc), so a nukta-bearing spelling could
                        // never equal its own normalized form and would fail the class-load
                        // self-check — the remaining nukta-free "कत्ल" below now matches both
                        // spellings of input on its own, the same way it already matched "क़त्ल"
                        // before this round (both folded onto the same normalized form once
                        // DEVANAGARI_NUKTA landed).
                        //
                        // T-GOLIVE-0918-R4 (vikram · 2026-09-19) — repair round 4 MEDIUM fix: round
                        // 3's "कातिल" (qatil/katil, "murderer") is REMOVED again. An independent
                        // reviewer's probe found it over-blocking standard Bollywood-song/romantic
                        // vocabulary — "कातिल अदाएं डांस कवर" and "तेरी कातिल निगाहें रील" ("killer
                        // moves", "your killer gaze") were quotable at 49a0415 and are ordinary
                        // creator-content phrasing, the SAME false-positive class as the already
                        // excluded English "killer" (see this enum's own "Terms deliberately
                        // EXCLUDED" note above — कातिल is its literal Hindi-Urdu translation and
                        // inherits the identical accepted-false-positive reasoning, now added there).
                        // "कत्ल"/"katl"/"qatl" below are unaffected — they are the noun for the
                        // ACT of killing, not "killer" as a person/adjective, and carry no comparable
                        // romantic usage.
                        //
                        // T-GOLIVE-0918-R3 (vikram · 2026-09-18) — repair round 3 MEDIUM fix. Bare
                        // "रेप" is the ordinary Hindi loanword for "rape" but is IDENTICALLY spelled
                        // to the ordinary Hindi loanword for a gym "rep" (repetition) — an
                        // independent reviewer's probe found "आखिरी रेप तक पुश करो" and "बस एक रेप
                        // और, हार मत मानो" (both gym-workout captions) wrongly OVERBLOCKED [CRIME],
                        // a round-1 finding never closed. Same discipline as excluding bare
                        // "killer"/"shot"/"mob": the qualified phrase "रेप केस" (rape case) below
                        // keeps the one probe that actually needs bare रेप ("रेप केस") blocking,
                        // without the bare word's gym-context false positives. "गैंगरेप" is
                        // unaffected — it is its own fused token, not reachable via रेप.
                        "रेप केस",
                        "गैंगरेप", "हत्याकांड", "हत्यारा", "हत्याओं", "कत्ल",
                        // Latin/Hinglish alternate spellings of terms already covered above —
                        // "balatkaar" (balatkar), "hatyaa" (hatya), "qatal"/"katl" (qatl).
                        "balatkaar", "hatyaa", "qatal", "katl",
                        // T-GOLIVE-0918-R4 (vikram · 2026-09-19) — repair round 4 MEDIUM fix: Latin
                        // "hatyaon" (हत्याओं, murders — oblique plural) had no Latin counterpart at
                        // all; an independent reviewer's probe found "hatyaon ka silsila" bypassing
                        // (bare "hatya" cannot match inside it — matchesTerm's end anchor never lands
                        // on "hatya" while "on" continues the same token).
                        "hatyaon",
                        // T-GOLIVE-0918-R3 (vikram · 2026-09-18) — repair round 3 MEDIUM fix: Latin/
                        // Hinglish counterparts of Devanagari terms already listed above that had no
                        // Latin spelling at all — "hatyara"/"hatyare" (हत्यारा/हत्यारे, murderer,
                        // singular/plural) and "golibari" (गोलीबारी, gunfire). An independent
                        // reviewer's probe found "hatyara pakda gaya", "hatyare giraftar" and
                        // "golibari mein 2 ghayal" bypassing.
                        "hatyara", "hatyare", "golibari",
                        // T-GOLIVE-0918-R2 repair round 2 (vikram · 2026-09-18) — HIGH regression fix.
                        // बलात्कारी (balatkari, rapist) and हत्यारे/हत्यारों (murderers, direct and
                        // oblique plural of हत्यारा) are DIFFERENT token sequences from their base
                        // words बलात्कार/हत्यारा — a trailing vowel sign (ी/े/ों) extends the token
                        // past the base word's own end anchor now that isDevanagariCombiningMark
                        // keeps marks instead of stripping them, so the base term cannot match inside
                        // the inflected one. हत्याएं is the direct-plural counterpart of the already-
                        // listed हत्याओं (oblique plural), same word family, different suffix. An
                        // independent reviewer's probe found "बलात्कारी", "बलात्कारियों को सजा",
                        // "हत्यारे पकड़े गए", "हत्यारों को सजा" and "हत्याएं बढ़ीं" bypassing 72cabea —
                        // बलात्कारी was BLOCKED at 49a0415 (via the since-replaced consonant-skeleton
                        // technique) and silently regressed when the skeleton was rewritten. Devanagari
                        // has no suffix generator (GENERATED_SUFFIXES is ASCII-only), so each
                        // inflection is its own literal, same discipline as हत्यारा/हत्याओं already
                        // above.
                        "बलात्कारी", "बलात्कारियों", "हत्यारे", "हत्यारों", "हत्याएं",
                        // T-GOLIVE-0918-R2 repair round 2 — MEDIUM vocabulary gap: दुष्कर्म (dushkarm)
                        // is the standard Hindi-press euphemism for rape, distinct from the already-
                        // listed बलात्कार; छेड़छाड़ (chhedchhad) is the standard Hindi-press word for
                        // molestation/eve-teasing, distinct from the already-listed मोलेस्टेशन-class
                        // English terms; गोलीबारी (golibari, gunfire/firing) is the Devanagari
                        // counterpart of the already-bare "gunfire"/"gunman"/"shootout"; मर्डर is the
                        // Devanagari transliteration of "murder" routinely used in Hindi entertainment/
                        // crime tabloid headlines; अपहरण (apaharan, abduction/kidnapping) is the
                        // Devanagari counterpart of the already-listed "abduction"/"abducted". The
                        // Latin/Hinglish counterparts are "dushkarm" and "apharan"/"apaharan" (both
                        // spellings seen in print). An independent reviewer's probe named all of these
                        // outright: "युवती से दुष्कर्म", "dushkarm ka aaropi giraftar", "छात्रा से
                        // छेड़छाड़", "chhedchhad ka aaropi", "गोलीबारी में 2 घायल", "मर्डर केस में बड़ा
                        // खुलासा", "बच्चे का अपहरण", "bachche ka apharan". Source: wiki/decisions/
                        // 2026-09-18-trend-headline-screening.md.
                        "दुष्कर्म", "dushkarm", "गोलीबारी", "मर्डर", "अपहरण",
                        "apharan", "apaharan",
                        // T-GOLIVE-0918-R3 (vikram · 2026-09-18) — repair round 3 MEDIUM fix. Bare
                        // "छेड़छाड़"/"chhedchhad" are REMOVED (nukta-free spelling below is also
                        // gone from the bare form for the same reason): the word means both
                        // "molestation/eve-teasing" (crime) and plain "tampering" (no crime at all),
                        // and Hindi uses the IDENTICAL grammatical construction for both senses —
                        // "छात्रा से छेड़छाड़" (molestation of a student) and "प्रकृति से छेड़छाड़ मत
                        // करो" (don't tamper with nature) both use "<subject> से छेड़छाड़". An
                        // independent reviewer's probe found "स्किन के साथ छेड़छाड़ मत करो" and
                        // "प्रकृति से छेड़छाड़ मत करो" wrongly OVERBLOCKED [CRIME]; both were
                        // QUOTABLE before "छेड़छाड़" was added. There is no phrase-only fix that
                        // keeps blocking a bare crime headline AND stops blocking a bare tamper
                        // sentence — the two are lexically identical — so the qualified phrases
                        // below (which only ever occur in the crime sense) replace the bare word.
                        // ACCEPTED GAP, stated rather than hidden: a bare crime headline with no
                        // case/accused/complaint word (e.g. bare "छात्रा से छेड़छाड़" with nothing
                        // else) no longer blocks on its own. This is a product trade-off, not an
                        // engineering one — same class of call as the confusables-table deviation
                        // recorded near CONFUSABLE_FOLD — and needs the same kind of ruling; flagged
                        // for Priya rather than decided here.
                        "छेडछाड का आरोपी", "छेडछाड की शिकायत", "chhedchhad ka aaropi",
                        "chhedchhad ki shikayat",
                        // T-GOLIVE-0918-R3 repair round 3 — MEDIUM vocabulary gap: दुष्कर्मी
                        // (dushkarmi, "rapist" — the agent-noun of the already-listed दुष्कर्म) and
                        // its oblique plural दुष्कर्मियों are DIFFERENT tokens from दुष्कर्म for the
                        // same reason हत्यारे/हत्यारों are different from हत्यारा (a trailing vowel
                        // sign extends the token past दुष्कर्म's own end anchor). An independent
                        // reviewer's probe found "दुष्कर्मी गिरफ्तार" and "दुष्कर्मियों को सजा"
                        // bypassing, plus the Latin/Hinglish counterpart "dushkarmi giraftar".
                        "दुष्कर्मी", "दुष्कर्मियों", "dushkarmi",
                        // T-GOLIVE-0918-R2 repair round 2 — MEDIUM vocabulary gap: जिंदा जलाया (zinda
                        // jalaya, "burned alive") is Indian crime-reporting phrasing in the same
                        // bucket as the already-listed "set ablaze" ("महिला को जिंदा जलाया"). "maar
                        // diya"/"maar daala" (Hinglish "beaten/killed") and "goli maar" (Hinglish
                        // "shot"/gunfire) are routine Hindi-crime-reporting Latin-script phrases; an
                        // independent reviewer's probe found "maar diya gaya" (round-1 LOW defect,
                        // never closed), "maar daala gaya" and "goli maar di" all bypassing.
                        "जिंदा जलाया",
                        // T-GOLIVE-0918-R3 (vikram · 2026-09-18) — repair round 3 HIGH fix. Bare
                        // "maar diya"/"maar daala"/"goli maar" are REMOVED: an independent
                        // reviewer's probe found all three over-blocking common Hinglish creator
                        // content that has nothing to do with crime — cricket commentary ("Chhakka
                        // maar diya Kohli ne!", "Sixer maar diya last ball pe!"), a Bollywood song
                        // title ("Maar Daala song dance cover", from Devdas; "Goli maar bheje mein
                        // dance cover", from Satya), and Hinglish compliment/meme slang ("Tune toh
                        // maar daala yaar, kya look hai", "Maar diya jaaye ya chhod diya jaaye
                        // meme"). This is the same class of accepted cost as excluding bare
                        // "killer"/"shot"/"attack"/"clash"/"mob"/"court" (see this enum's own class
                        // javadoc): the bare phrase's false-positive rate in this product's actual
                        // niche is too high. The genuine crime-reporting register uses a PASSIVE
                        // construction these active/casual phrasings do not ("<victim> ko/ne maar
                        // diya/daala GAYA", "goli maar(i) DI/GAYI") — narrower phrases below keep
                        // that register blocked without touching any of the benign forms above (none
                        // of them end in "gaya"/"di"/"gayi" after the maar phrase).
                        // T-GOLIVE-0918-R4 (vikram · 2026-09-19) — repair round 4 MEDIUM fix: bare
                        // "maar diya gaya" and "goli maar di" are REMOVED. An independent reviewer's
                        // probe found both over-blocking common Hinglish creator content: "Cheat day
                        // pe diet ko goli maar di"/"Tension ko goli maar di, weekend vibes" are the
                        // idiom "goli maar di X" ("forget about X"/"to hell with X"), and "Last ball
                        // pe chhakka maar diya gaya" is routine cricket commentary ("a six was hit").
                        // "maar daala gaya"/"maar dala gaya"/"goli maari gayi" are NOT flagged the
                        // same way (no attested idiom/commentary collision for these three exact
                        // spellings) and stay bare. ACCEPTED GAP: a bare "X ko/ne goli maar di" or
                        // "X ko/ne maar diya gaya" crime headline using exactly these two spellings,
                        // with no other CRIME vocabulary, no longer blocks on its own (see
                        // firstUnsafeTopic_repairRound4AcceptedGapsStayQuotable).
                        "maar daala gaya", "maar dala gaya", "goli maari gayi",
                        // T-GOLIVE-0918-R4 — बलात्कारियो (informal spelling of the already-listed
                        // बलात्कारियों, missing the final anusvara — same "informal spelling"
                        // pattern as मौते/दंगो) and हत्याओ (informal spelling of the already-listed
                        // हत्याओं, same pattern) are DIFFERENT token sequences from their anusvara-
                        // bearing counterparts; an independent reviewer's probe found "बलात्कारियो
                        // को सजा" and "हत्याओ का सिलसिला" bypassing. अपहरणकर्ता (abductor/kidnapper,
                        // agent-noun of the already-listed अपहरण) is a DIFFERENT, longer token for
                        // the same reason हत्यारा/हत्यारे are ("अपहरणकर्ता गिरफ्तार" bypassing).
                        // हत्यारिन (hatyarin, "murderess" — feminine of the already-listed हत्यारा)
                        // is likewise a different token ("हत्यारिन पत्नी गिरफ्तार" bypassing).
                        // "balatkariyon" is the Latin/Hinglish counterpart of the already-listed
                        // बलात्कारियों ("balatkariyon ko saza" bypassing). "चाकू से हमला" (knife
                        // attack) is the same class of killing-method phrase as the already-listed
                        // "stabbed"/"stabbing"/"strangle" ("चाकू से हमला" bypassing outright). "जिंदा
                        // जला दिया" is a different verb inflection of the already-listed "जिंदा
                        // जलाया" ("महिला को ज़िंदा जला दिया" bypassing — ज़िंदा's nukta already folds
                        // onto जिंदा per DEVANAGARI_NUKTA, but "जला दिया" itself was never listed).
                        "बलात्कारियो", "हत्याओ", "अपहरणकर्ता", "हत्यारिन", "balatkariyon",
                        "चाकू से हमला", "जिंदा जला दिया",
                        // Devanagari counterparts of the same passive construction — an independent
                        // reviewer's probe named "गोली मार दी" outright. "को मार डाला" is REMOVED
                        // (T-GOLIVE-0918-R4 MEDIUM fix): an independent reviewer's probe found it
                        // over-blocking the identical hyperbolic-song-lyric register as the already-
                        // excluded active maar-diya/daala forms — "इस गाने ने दिल को मार डाला" ("this
                        // song killed my heart") was quotable at 49a0415 and shares the exact same
                        // "<object> को मार डाला" construction as the genuine crime headline "युवक को
                        // मार डाला", with no lexical way to tell them apart. ACCEPTED GAP, same
                        // discipline as the maar-diya/goli-maar exclusions above.
                        "गोली मार दी")),
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
                        "stone pelting",
                        // F-0857 repair round 1 (vikram · 2026-09-18) — "stone pelters" (the agent
                        // noun) is a distinct phrase from "stone pelting" above, not reachable by
                        // GENERATED_SUFFIXES (which appends to the whole phrase's compact form, not
                        // to its final word alone); an independent reviewer's probe found it
                        // bypassing. "danga"/"dange" (riot/riots) is the Hinglish (Latin-script)
                        // counterpart to the Devanagari skeleton below, named in the same probe
                        // family as "hatya"/"balatkar".
                        "stone pelters", "danga", "dange",
                        // F-0857 repair round 1 — Devanagari coverage: दंगे (dange, riots).
                        //
                        // T-GOLIVE-0918-R2 (vikram · 2026-09-18) — repair round 2 REWRITES this from
                        // the 2-code-point stripped skeleton ("दग") to the full, correctly-spelled
                        // word, and adds its two remaining base inflections (दंगा singular, दंगों
                        // oblique plural) as their own literals — Devanagari has no generated-suffix
                        // mechanism (GENERATED_SUFFIXES is ASCII-only), so each form is listed, same
                        // discipline as an irregular Latin form like "slain".
                        //
                        // THIS IS THE FIX FOR THE MEDIUM AN INDEPENDENT REVIEWER FOUND: the old
                        // skeleton "दग" is exactly what देगा/देगी/देंगे/दूंगा ("will give") and दाग
                        // ("spot/stain") ALSO stripped down to, because rule 2 deleted every
                        // Devanagari vowel sign and anusvara the same way it deletes a Latin
                        // combining accent. Once normalizeForMatching stops stripping Devanagari's
                        // own marks (see isDevanagariCombiningMark), दंगा/दंगे/दंगों keep their
                        // anusवार and देगा/देगी/देंगे/दूंगा/दाग keep theirs (or lack one) — verified
                        // empirically, code point by code point, that all of these five benign words
                        // are now genuinely distinct sequences from दंगा/दंगे/दंगों:
                        //   दंगा  = द ं ग ा   (द, anusvara, ग, ा)
                        //   दंगे  = द ं ग े   (द, anusvara, ग, े)
                        //   दंगों = द ं ग ो ं (द, anusvara, ग, ो, anusvara)
                        //   देगा  = द े ग ा   (द, े — NO anusvara — ग, ा)
                        //   देगी  = द े ग ी
                        //   देंगे = द े ं ग े (5 code points — the anusvara sits AFTER े, not
                        //           directly after द — distinct in both length and sequence from
                        //           दंगे's 4)
                        //   दूंगा = द ू ं ग ा (5 code points, ऊ-vowel + anusvara — distinct from
                        //           दंगा's 4)
                        //   दाग   = द ा ग     (3 code points — no anusvara, no े at all)
                        "दंगा", "दंगे", "दंगों",
                        // T-GOLIVE-0918-R2 — दंगाई (dangai, rioter/agent-noun) is a DIFFERENT,
                        // longer token from दंगा (an independent vowel letter ई follows, so दंगा
                        // never reaches this token's end) — an independent reviewer's probe named
                        // "दंगाई गिरफ्तार" outright. आतंकी (aatanki, terrorist/terrorism-related
                        // adjective) is the Devanagari counterpart to the already-bare "terrorist"/
                        // "extremist" (covers "आतंकी हमला", terrorist attack).
                        //
                        // T-GOLIVE-0918-R2 repair round 2 (vikram · 2026-09-18) — MEDIUM fix: बम (bam,
                        // bomb) and धमाका (dhamaka, blast/explosion) were ORIGINALLY listed bare here
                        // (same round, same commit) and over-blocked festival/sale content — "बम बम
                        // भोले महाशिवरात्रि स्पेशल" (the Shiva chant "Bam Bam Bhole"), "दिवाली धमाका
                        // सेल शुरू" and "धमाका ऑफर सिर्फ आज" (Diwali/sale "dhamaka" is ordinary Indian
                        // retail marketing copy) were all QUOTABLE before these bare terms were added.
                        // An independent reviewer's probe found the over-block. The Latin side of this
                        // set already avoided the same mistake — "bam dhamaka" was kept as a phrase
                        // below specifically because bare "bam" risks colliding with onomatopoeia —
                        // and that same phrase discipline now applies to the Devanagari side too: बम
                        // and धमाका are bare no longer, only the phrases "बम धमाका" and "बम ब्लास्ट"
                        // (bomb blast) are listed, matching the reviewer's suggested fix.
                        "दंगाई", "आतंकी", "बम धमाका", "बम ब्लास्ट",
                        // T-GOLIVE-0918-R4 (vikram · 2026-09-19) — बम धमाके (oblique/plural of the
                        // already-listed बम धमाका) and बम धमाकों (its oblique plural) are DIFFERENT
                        // token sequences from धमाका's bare form for the same reason हत्यारे/हत्यारों
                        // are — an independent reviewer's probe found "बम धमाके से दहला शहर", "बम
                        // धमाकों से दहला शहर" and "बम धमाके में 10 घायल" bypassing. दंगो (informal
                        // spelling of the already-listed दंगों, missing the final anusvara — same
                        // "informal spelling" pattern as मौते) is a regression the same probe found
                        // ("दंगो में 5 घायल" bypassing, BLOCKED at 49a0415). "dangon" is the Latin/
                        // Hinglish oblique-plural counterpart of the already-listed "danga"/"dange"
                        // ("dangon mein 5 ghayal" bypassing).
                        "बम धमाके", "बम धमाकों", "दंगो", "dangon",
                        // Latin/Hinglish counterparts: "dangai" (rioter), "aatankwadi" (terrorist),
                        // "bam dhamaka" (bomb blast — kept as a phrase rather than a bare "bam",
                        // since "bam" alone as a 3-letter English token risks colliding with
                        // creator-content onomatopoeia, e.g. "and BAM, transformation complete").
                        // Devanagari/English/Hinglish phrase: "hindu muslim clash" targets the
                        // CONFLICT the same way "communal clash" does — see UnsafeHeadlineTopic's
                        // own design notes on why COMMUNAL avoids bare identity terms; this is the
                        // conflict-naming phrase, not identity alone, so it does not violate that
                        // rule. "mob torch" is the base of "Mob torches bus in Nuh" — ends in no
                        // silent "e", so plain GENERATED_SUFFIXES covers torches/torched/torching.
                        // "terrorised"/"terrorized" are adjective forms of "terror" not reachable by
                        // any listed suffix rule ("Terrorised villagers flee").
                        "dangai", "aatankwadi", "bam dhamaka", "hindu muslim clash", "mob torch",
                        "terrorised", "terrorized",
                        // T-GOLIVE-0918-R2 — "delhiriots" closes the ALL-LOWERCASE/ALL-CAPS joined-
                        // hashtag gap rule 5's case-transition split cannot see ("#delhiriots",
                        // "#DELHIRIOTS" — no case transition and no digit exists inside either, so
                        // there is no boundary signal at all to split on; a general dictionary
                        // segmenter would be needed for the unbounded case, e.g. any other city
                        // name, and none is added per this round's no-new-dependency rule). This is
                        // the one specific compound Kabir's review named; it is listed as its own
                        // literal, same technique as "gangrape" in CRIME, rather than claimed fixed
                        // in general.
                        "delhiriots",
                        // T-GOLIVE-0918-R2 repair round 2 (vikram · 2026-09-18) — HIGH regression fix.
                        // दंगाइयों (dangaiyon, rioters, oblique plural of दंगाई) is a DIFFERENT token
                        // sequence from दंगाई's bare form for the same reason हत्यारे/हत्यारों are
                        // (see the CRIME set's matching note) — an independent reviewer's probe found
                        // "दंगाइयों" bypassing 72cabea.
                        "दंगाइयों",
                        // T-GOLIVE-0918-R2 repair round 2 — MEDIUM vocabulary gap: आतंकवादी
                        // (aatankwadi, "terrorist", the noun) and आतंकवाद (aatankwad, "terrorism") are
                        // DIFFERENT tokens from the already-listed आतंकी (a shorter, adjectival form)
                        // — an independent reviewer's probe found "आतंकवादी हमला" and "आतंकवाद पर बड़ा
                        // फैसला" bypassing. सांप्रदायिक (sampradayik, "communal/sectarian") is the
                        // Devanagari counterpart of the already-bare English "communal"/"sectarian"
                        // ("सांप्रदायिक हिंसा भड़की"). लिंचिंग (linching) is the Devanagari
                        // transliteration of the already-listed English "lynching" ("मॉब लिंचिंग का
                        // मामला"). पथराव (pathrav, "stone-pelting") is the Devanagari counterpart of
                        // the already-listed English phrase "stone pelting" ("पथराव के बाद तनाव"). The
                        // Latin/Hinglish counterparts are "atankwadi" (single-a spelling, distinct from
                        // the already-listed double-a "aatankwadi") and "aatanki" ("aatanki hamla",
                        // "atankwadi hamla"); "sampradayik" ("sampradayik hinsa").
                        "आतंकवादी", "आतंकवाद", "सांप्रदायिक", "लिंचिंग", "पथराव", "atankwadi", "aatanki",
                        "sampradayik",
                        // T-GOLIVE-0918-R4 (vikram · 2026-09-19) — repair round 4 vocabulary gaps an
                        // independent reviewer's probe named outright. आतंकवादियों (oblique plural of
                        // the already-listed आतंकवादी) and आतंकियों (oblique plural of the already-
                        // listed आतंकी) are DIFFERENT tokens from their singular forms, same pattern
                        // as हत्यारे/हत्यारों ("आतंकवादियों ने हमला किया", "आतंकियों का सफाया"
                        // bypassing). "aatankwadiyon" is the Latin/Hinglish counterpart
                        // ("aatankwadiyon ne hamla kiya" bypassing). "आत्मघाती हमला" (self-
                        // destructive/suicide attack — a terrorism term distinct from personal
                        // आत्महत्या in DEATH) closes an outright gap ("आत्मघाती हमला" bypassing).
                        "आतंकवादियों", "आतंकियों", "aatankwadiyon", "आत्मघाती हमला",
                        // T-GOLIVE-0918-R2 repair round 2 — MEDIUM digit/case-boundary fix: an
                        // all-Devanagari joined hashtag carries no case-transition signal for rule 5
                        // to split on (Devanagari has no upper/lower case) and, being all-letters,
                        // gives digitBoundary nothing to fire on either — the same accepted gap as
                        // "delhiriots" above, closed the same way, for the one compound an independent
                        // reviewer's probe named outright: "#दिल्लीदंगा".
                        "दिल्लीदंगा")),
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
                        "delhi hc", "apex court",
                        // F-0857 repair round 1 (vikram · 2026-09-18) — an independent reviewer's
                        // probe found other High Courts' "<City> HC" shorthand and "top court" (a
                        // common headline synonym for the Supreme Court, alongside "apex court"
                        // above) bypassing the same way "Delhi HC" did before F-0855.
                        "bombay hc", "allahabad hc", "top court",
                        // T-GOLIVE-0918-R2 (vikram · 2026-09-18) — repair round 2. Indian-news legal
                        // vocabulary an independent reviewer's probe named outright: "jailed" ("Actor
                        // jailed for 2 years"); "bail denied" as a phrase, since bare "bail" alone
                        // covers ordinary non-legal creator content risk poorly (skateboarding
                        // "bail", a common creator-vlog term for a failed trick) — the phrase is
                        // specific to the legal outcome ("Bail denied to accused"); "sentenced" and
                        // "imprisonment" ("Sentenced to life imprisonment") are not reachable from
                        // any already-listed term or suffix rule.
                        "jailed", "bail denied", "sentenced", "imprisonment"));

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
                    // T-GOLIVE-0918-R2 repair round 2 (vikram · 2026-09-18) — corrected from 'y' to
                    // 'u'. An independent reviewer's probe used U+0443 to substitute Latin 'u' in an
                    // all-Cyrillic spelling of "suicide" ("ѕуісіде"); folding it to 'y' instead
                    // produced "syicide", which matches nothing. There is no other user of this fold
                    // in the test suite, so the correction is not a behaviour-preserving no-op — it
                    // is a fix of a wrong prior mapping.
                    Map.entry((int) 'у', (int) 'u'), // U+0443
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
                    // T-GOLIVE-0918-R2 repair round 2 (vikram · 2026-09-18) — MEDIUM: U+0434 CYRILLIC
                    // SMALL LETTER DE is the ORDINARY Cyrillic "d" (not the komi-de U+0501 above) and
                    // is visually close enough to Latin 'd' in many fonts to be used the same way —
                    // an independent reviewer's probe found the all-Cyrillic homoglyph spelling
                    // "ѕуісіде" bypassing 72cabea purely because this one entry was missing from an
                    // otherwise-complete fold.
                    Map.entry((int) 'д', (int) 'd'), // U+0434
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
                    // Digit and symbol leetspeak substitutions that are UNAMBIGUOUS — i.e. every
                    // reported real-world use folds to the same Latin letter, so a single static
                    // entry is correct and '1'/'|' (see AMBIGUOUS_FOLD_VARIANTS below) are NOT here.
                    // F-0857 repair round 1 (vikram · 2026-09-18) — '4'->a, '5'->s, '$'->s and the
                    // four extra homoglyphs below were added after an independent reviewer's probe
                    // found them bypassing (r4pe, 5uicide, a$$ault, murd€r, rɑpe, ԁеатһ, murdЗr,
                    // bomƄ). Source: wiki/decisions/2026-09-18-trend-headline-screening.md
                    // ("confusable folding driven by the Unicode confusables data").
                    //
                    // T-GOLIVE-0918-R3 (vikram · 2026-09-18) — repair round 3. '0' is REMOVED from
                    // this unconditional table: an independent reviewer's probe "5ucc0mbs" needs
                    // '0'->'u' to reach "succumbs", but the existing static '0'->'o' fold gives
                    // "succombs", which matches nothing. Unlike '4'/'5'/'$' above, '0' is genuinely
                    // two-way ambiguous in real leetspeak (both "s0cial"->o and "5ucc0mbs"->u are
                    // attested), so it moves to the same ambiguous-reading technique as '1'/'|' —
                    // see ZERO_READINGS and buildConfusableFoldVariants below — rather than staying a
                    // single static entry that can only ever be right for one reading. See
                    // AMBIGUOUS_O_OR_U_CHARS and buildConfusableFoldVariants below.
                    Map.entry((int) '3', (int) 'e'),
                    Map.entry((int) '4', (int) 'a'),
                    Map.entry((int) '5', (int) 's'),
                    Map.entry((int) '$', (int) 's'),
                    Map.entry((int) '€', (int) 'e'), // € EURO SIGN, e.g. "murd€r"
                    Map.entry(0x0251, (int) 'a'), // ɑ LATIN SMALL LETTER ALPHA, e.g. "rɑpe"
                    Map.entry(0x04BB, (int) 'h'), // һ CYRILLIC SMALL LETTER SHHA, e.g. "ԁеатһ"
                    Map.entry(0x0437, (int) 'e'), // з CYRILLIC SMALL LETTER ZE (looks like '3'->e)
                    Map.entry(0x0185, (int) 'b'), // ƅ LATIN SMALL LETTER TONE SIX, e.g. "bomƄ"
                    // T-GOLIVE-0918-R2 (vikram · 2026-09-18) — an independent reviewer's probe found
                    // these two bypassing 49a0415. Per the LOCKED ruling (wiki/decisions/2026-09-18-
                    // trend-headline-screening.md, "confusable folding driven by the Unicode
                    // confusables data") this table should be generated from the Unicode confusables
                    // data (UTS #39 confusables.txt), not hand-picked one probe at a time — but no
                    // confusables-data dependency (e.g. ICU4J) is on this project's classpath (I
                    // checked `mvn -o dependency:tree` — no icu4j/com.ibm.icu artifact anywhere), and
                    // hard rule 8 for this round forbids adding one. So these are added to the
                    // existing curated table instead, same discipline as every entry above. THIS IS
                    // A STATED DEVIATION FROM THE RULING'S LETTER, NOT A FIX OF IT: the table remains
                    // finite and hand-curated, and any homoglyph outside it still evades exactly as
                    // UnsafeHeadlineTopic's own javadoc already discloses. Priya needs to record
                    // either (a) an amendment accepting the curated-table approach permanently, or
                    // (b) a follow-up ticket to add a confusables-data dependency.
                    Map.entry(0x0257, (int) 'd'), // ɗ LATIN SMALL LETTER D WITH HOOK, e.g. "ɗeath"
                    // Ꮇ (U+13B7, CHEROKEE LETTER LU — visually a Latin capital M) is UPPERCASE per
                    // Unicode's Cherokee case pairs added in Unicode 8.0; normalizeForMatching lower-
                    // cases with Character.toLowerCase BEFORE this table is consulted, and on this
                    // JVM's Unicode data U+13B7 lowercases to U+AB87 (verified empirically, not
                    // assumed) — so the key here must be the LOWERCASE form, or this entry would
                    // silently never fire.
                    Map.entry(0xAB87, (int) 'm'), // ꮇ CHEROKEE SMALL LETTER LU, e.g. "Ꮇurder"
                    // T-GOLIVE-0918-R2 repair round 2 (vikram · 2026-09-18) — MEDIUM/LOW: round-1's
                    // own LOW defect listed these as BYPASS and they were never closed. '!' visually
                    // resembles a dotless 'i' (no descender, same vertical stroke) and is unambiguous
                    // in practice — every reported real-world use ("k!lled", "su!c!de") substitutes
                    // for 'i', never 'l'. '+' and '7' both visually resemble 't' (a crossbar over a
                    // vertical stroke) and are likewise unambiguous ("dea+h toll", "dea7h toll").
                    // Source: wiki/decisions/2026-09-18-trend-headline-screening.md.
                    //
                    // T-GOLIVE-0918-R2 REPAIR ROUND 2 (vikram · 2026-09-19) — HIGH fix. '!' and '+'
                    // are REMOVED from this unconditional table. Repair round 3 tried to make them
                    // context-dependent with a same-word-flank heuristic (PUNCTUATION_LETTER_LOOKALIKES
                    // / isWordFlank, now deleted) that inspected only the single raw character on the
                    // trailing side. That heuristic cannot be right in either direction at once: "k!lled"
                    // and "murder!pune" both have a plain lowercase letter immediately after the mark,
                    // so any rule keyed on "is the next raw character lowercase" either folds both
                    // (regluing "murder"+"pune" into one token so "murder" never reaches its own token
                    // end — an independent reviewer's probe found "murder!pune shocked",
                    // "Suicide+note found", "riots*delhi on edge" and "Death+destruction in town" all
                    // bypassing this way) or folds neither. It was also case-sensitive by construction
                    // (checked Character.isLowerCase on the neighbour), which is why "K!LLED IN DELHI",
                    // "M*RDER IN DELHI", "R*PE CASE" and "SU!C!DE NOTE FOUND" bypassed even though the
                    // lowercase forms blocked. '7' keeps its unconditional fold (still no decorative/
                    // separator use attested); '!'/'+'/'*' move to the SAME technique already used for
                    // '1'/'|'/'0'/'@' above: both readings (folded to a letter, and left as the
                    // separator/punctuation it is) are tried as separate normalization passes, decided
                    // once per variant rather than guessed per character from local context — see
                    // BANG_SIGN/PLUS_SIGN/ASTERISK and buildConfusableFoldVariants below. Because
                    // neither reading depends on the character's case, this closes the all-caps gap in
                    // the same change, with no new case-sensitive logic.
                    Map.entry((int) '7', (int) 't'));

    /**
     * T-GOLIVE-0918-R2 REPAIR ROUND 2 (vikram · 2026-09-19) — HIGH fix, replaces the deleted
     * {@code PUNCTUATION_LETTER_LOOKALIKES}/{@code isWordFlank} context-flank mechanism from repair
     * rounds 3 and 4.
     *
     * <p><b>Why the flank heuristic could never work.</b> Both rounds tried to decide, from the single
     * raw character trailing {@code '!'}/{@code '+'}/{@code '*'}, whether the mark was genuine mid-word
     * leetspeak ("dea+h", "k!lled" — should fold) or a separator/emphasis mark between two different
     * words ("Murder!Pune", "Suicide+Note" — must NOT fold, or the two words glue into one token and
     * neither's end anchor ever fires). Round 4 narrowed the trailing flank to "next raw char is a
     * plain lowercase letter", which fixed the case where the second word is capitalised — but
     * "murder!pune shocked", "Suicide+note found", "riots*delhi on edge" and "Death+destruction in
     * town" all have an ORDINARY LOWERCASE word after the mark too, which is locally indistinguishable
     * from "dea+h"'s trailing "h": both are "punctuation immediately followed by a lowercase letter".
     * No rule keyed on the single trailing character can separate these two cases, because the actual
     * distinguishing fact — whether the run before the mark is already a complete word on its own — is
     * not a property of one neighbouring character. The same heuristic was also inherently
     * case-sensitive (it special-cased {@code Character.isLowerCase}), which is why "K!LLED IN DELHI",
     * "M*RDER IN DELHI", "R*PE CASE" and "SU!C!DE NOTE FOUND" bypassed even though the lowercase forms
     * blocked — the fold never even got the chance to fire before an uppercase neighbour.
     *
     * <p><b>The actual fix: stop guessing from local context and try both readings</b>, the same
     * technique {@link #AT_SIGN} and {@link #AMBIGUOUS_I_OR_L_CHARS} already use for exactly this kind
     * of "no single correct static choice" ambiguity. One {@link #CONFUSABLE_FOLD_VARIANTS} pass folds
     * {@code '!'} to {@code 'i'}/{@code '+'} to {@code 't'}/{@code '*'} to a vowel; a second pass
     * leaves the mark as the punctuation it is, which is not a letter, so it falls into the ordinary
     * token-ending branch. A headline is unsafe if ANY variant matches (see {@link
     * #firstUnsafeTopic(String)}), so:
     * <ul>
     *   <li>"dea+h toll" blocks via the FOLDING variant ("+"-&gt;"t" completes "death").
     *   <li>"murder!pune shocked"/"Suicide+note found"/"riots*delhi on edge"/"Death+destruction in
     *       town" block via the SEPARATOR variant — the mark ends the token there regardless of what
     *       follows, so "murder"/"suicide"/"riots"/"death" reach their own token end untouched.
     *   <li>"Rio+ Carnival"/"Rio+Carnival"/"#Rio+Carnival" stay quotable in BOTH variants: the
     *       separator variant never glues "rio" to "carnival" at all, and the folding variant produces
     *       one token "riotcarnival" whose token-END lands after "carnival", not after "riot" — so
     *       {@code containsTerm}'s end anchor never lands on bare "riot" either way (see {@link
     *       #matchesTerm}'s own end-anchor discipline).
     *   <li>Because neither reading is gated on the character's case, "K!LLED IN DELHI"/"M*RDER IN
     *       DELHI"/"R*PE CASE"/"SU!C!DE NOTE FOUND" now fold exactly like their lowercase forms — see
     *       {@code previousAppendedWasPunctuationFold} in {@link #normalizeForMatching} for the one
     *       extra piece this needs: an all-caps run must not itself be split by the ordinary
     *       camelCase-boundary rule (rule 5) reacting to the LOWERCASE letter a fold like {@code
     *       '!'->'i'} produces in the middle of it.
     * </ul>
     *
     * <p>This is strictly simpler than the deleted mechanism, not just more correct: {@link
     * #normalizeForMatching(String, Map)} needs no special-cased branch for these three characters at
     * all any more — they are looked up in the per-variant fold map exactly like every other character,
     * the same one line of code that already handles {@link #AT_SIGN}.
     */
    private static final int BANG_SIGN = '!';

    private static final int PLUS_SIGN = '+';

    /**
     * T-GOLIVE-0918-R3 (vikram · 2026-09-18) — repair round 3 fix for the still-open "R1U BYPASS
     * 5ucc0mbs" item. {@code '0'} is genuinely ambiguous between {@code 'o'} and {@code 'u'} (see
     * the removed {@code CONFUSABLE_FOLD} entry's javadoc) — same technique as {@link
     * #AMBIGUOUS_I_OR_L_CHARS}: both readings are tried as separate normalization passes.
     */
    private static final Set<Integer> AMBIGUOUS_O_OR_U_CHARS = Set.of((int) '0');

    /**
     * '1' and '|' are genuinely ambiguous leetspeak/confusable substitutions for BOTH 'i' and 'l'
     * (F-0857 repair round 1, vikram · 2026-09-18 — an independent reviewer's probe showed
     * "k1lled"/"su1c1de"/"d1ed"/"r1ots" [1-&gt;i needed] alongside "k|lled" [|-&gt;i needed] and
     * "ki||ed" [|-&gt;l needed] all bypassing the single-reading '1'-&gt;'l' fold that 927002a
     * shipped). The LOCKED decision text itself says "1→i/l", not "1→l" — there is no single
     * correct static fold. Rather than guess, {@link #normalizeForMatching(String)} tries BOTH
     * readings (as separate full normalization passes) and a headline is unsafe if EITHER reading
     * matches — see {@link #CONFUSABLE_FOLD_VARIANTS}.
     */
    private static final Set<Integer> AMBIGUOUS_I_OR_L_CHARS = Set.of((int) '1', (int) '|');

    /**
     * '@' is a DIFFERENT kind of ambiguity from '1'/'|': folding it to 'a' is a mid-word leetspeak
     * substitution ("r@pe" -&gt; "rape"), but '@' is ALSO an ordinary separator ("murder@midnight",
     * "@murder", "#murder@home") where folding it to a letter GLUES the two sides into one token
     * and breaks the token-start/token-end anchors {@link #matchesTerm} depends on — exactly the
     * F-0857 repair-round-1 HIGH regression 927002a introduced by adding '@'-&gt;'a' unconditionally
     * (probe: "murder@midnight" blocked on 927002a^, bypassed on 927002a). There is no single
     * correct static choice, so — same technique as the '1'/'|' ambiguity above — both readings
     * ('@' left as a separator, and '@' folded to 'a') are tried as separate normalization passes;
     * see {@link #CONFUSABLE_FOLD_VARIANTS}.
     */
    private static final int AT_SIGN = '@';

    /**
     * '*' is a THIRD kind of ambiguity (T-GOLIVE-0918-R2 repair round 2, vikram · 2026-09-18) —
     * round-1's own LOW defect listed "m*rder in Delhi", "r*pe case" and "s*icide note" as BYPASS
     * and they were never closed. Unlike '1'/'|' (each genuinely two-way ambiguous between two
     * specific letters) or the digit/symbol leetspeak substitutions above (each visually resembles
     * exactly one letter), '*' carries NO shape hint at all for which letter it redacts — it is a
     * generic censor/redaction mark, and every reported real-world use of it this way masks a
     * vowel. There is no single correct static choice, so — same technique as '1'/'|' and '@' above
     * — all five vowel readings are tried as separate normalization passes; see {@link
     * #CONFUSABLE_FOLD_VARIANTS}. Restricting the wildcard to vowels (rather than all 26 letters)
     * keeps the combinatorial cost small and matches the actual observed evasion pattern.
     *
     * <p>T-GOLIVE-0918-R2 REPAIR ROUND 2 (vikram · 2026-09-19) — HIGH fix. A vowel reading is no
     * longer forced unconditionally: {@code null} is now a sixth reading, meaning "leave '*' as
     * punctuation, do not fold it at all" (see {@link #BANG_SIGN}'s javadoc for why the old
     * context-flank alternative could not work). {@code null} is grouped into the SAME dimension as
     * {@link #BANG_SIGN}/{@link #PLUS_SIGN} rather than each getting its own independent boolean —
     * see {@link #ASTERISK_READINGS} for why.
     */
    private static final int ASTERISK = '*';

    private static final char[] VOWEL_READINGS = {'a', 'e', 'i', 'o', 'u'};

    /**
     * T-GOLIVE-0918-R2 REPAIR ROUND 2 (vikram · 2026-09-19). {@link #BANG_SIGN}, {@link #PLUS_SIGN}
     * and {@link #ASTERISK} are combined into ONE variant dimension rather than three independent
     * booleans. Every probe on record needs either ALL THREE resolved to a letter in the same pass
     * ("dea+h toll", "k!lled", "m*rder in Delhi" — each a single mark genuinely mid-word) or NONE of
     * them ("Murder!Pune", "Suicide+Note", "Rio+ Carnival" — the mark is a separator/emphasis mark
     * elsewhere in the same headline); no probe needs '!' folded while '+' stays a separator in the
     * SAME headline. Six combinations — {@code null} (fold none of the three) plus one per vowel
     * (fold all three, '*' reading as that vowel) — cover every listed probe with no loss of
     * coverage, instead of the 2×2×6 = 24 a fully independent treatment would need. If a future probe
     * demonstrates a headline that genuinely needs one of the three folded and another not in the
     * SAME pass, split this back into independent booleans then — until one does, the smaller table
     * is preferred (see {@link #CONFUSABLE_FOLD_VARIANTS}'s own javadoc on why even the larger table
     * would still not be a performance concern).
     */
    private static final Character[] ASTERISK_READINGS = {'a', 'e', 'i', 'o', 'u', null};

    /**
     * Digits that {@link #CONFUSABLE_FOLD} (plus, for '0', the per-variant {@link
     * #AMBIGUOUS_O_OR_U_CHARS} reading) folds onto a Latin letter for leetspeak matching
     * ('0'/'1'/'3'/'4'/'5'/'7' -&gt; o-or-u/i-or-l/e/a/s/t). T-GOLIVE-0918-R2 repair round 2 (vikram
     * · 2026-09-18) — MEDIUM fix: rule 5's {@code digitBoundary} in {@link #normalizeForMatching}
     * checks {@code Character.isDigit} on the character AFTER this fold has already run, so a digit
     * that folds to a letter never registers as a digit and no hashtag/digit-suffix boundary is
     * ever created — an independent reviewer's probe found "#Riots1984", "#BombayBlasts1993",
     * "#Murder4Justice", "#Riots05", "#murder1", "#rape0", "#Blast4" and "#Stampede05" all bypassing
     * 72cabea for exactly this reason (only digits 2/6/8/9, which this table never touches, created
     * a boundary). The fix is a dedicated variant, built below, where none of these digits fold —
     * they stay digits, so the boundary check sees them — while every other letter/homoglyph fold
     * still applies normally.
     */
    private static final int[] LETTER_FOLDED_DIGITS = {'0', '1', '3', '4', '5', '7'};

    /**
     * Every confusable-fold reading {@link #firstUnsafeTopic(String)} must try. {@link
     * #CONFUSABLE_FOLD} above holds every UNAMBIGUOUS substitution; this builds the 2 ('1'/'|' -&gt;
     * i, or -&gt; l) &times; 2 ('0' -&gt; o, or -&gt; u, T-GOLIVE-0918-R3) &times; 2 ('@' folded, or
     * left as a separator) &times; 6 ({@link #ASTERISK_READINGS} — '!'/'+'/'*' all folded to a
     * letter, '*' reading as one of 5 vowels, OR none of the three folded) = 48 combinations on top
     * of it (F-0857 repair round 1 shipped the first and third dimensions; T-GOLIVE-0918-R2 repair
     * round 2 added the fourth; T-GOLIVE-0918-R3 repair round 3 added the second; T-GOLIVE-0918-R2
     * repair round 2 [2026-09-19] folded '!'/'+' into the fourth dimension, replacing the
     * context-flank mechanism repair rounds 3-4 tried — see {@link #BANG_SIGN}'s javadoc), plus 5
     * more digit-literal variants (one per '*' vowel reading, '!'/'+' left unfolded) where {@link
     * #LETTER_FOLDED_DIGITS} are left unfolded so digit-boundary detection can see them — 53
     * variants total. A headline is unsafe if ANY variant's normalization matches — see {@link
     * #firstUnsafeTopic(String)}. This many passes over a short trend headline is not a performance
     * concern; this is not run per-suggestion (the 2026-09-18 decision moves it to ingest-time, one
     * evaluation per trend).
     */
    private static final List<Map<Integer, Integer>> CONFUSABLE_FOLD_VARIANTS =
            buildConfusableFoldVariants();

    private static List<Map<Integer, Integer>> buildConfusableFoldVariants() {
        List<Map<Integer, Integer>> variants = new ArrayList<>();
        for (int ilReading : new int[] {'i', 'l'}) {
            // T-GOLIVE-0918-R3 (vikram · 2026-09-18) — repair round 3: the '0'->o/u ambiguous
            // reading, same technique as ilReading above. See AMBIGUOUS_O_OR_U_CHARS's javadoc.
            for (int zeroReading : new int[] {'o', 'u'}) {
                for (boolean foldAt : new boolean[] {false, true}) {
                    // T-GOLIVE-0918-R2 REPAIR ROUND 2 (vikram · 2026-09-19) — see
                    // ASTERISK_READINGS's javadoc for why '!'/'+'/'*' share this one loop instead of
                    // three independent booleans. asteriskReading == null means "fold none of the
                    // three"; any other value means "fold all three, '*' reads as this vowel".
                    for (Character asteriskReading : ASTERISK_READINGS) {
                        Map<Integer, Integer> variant = new HashMap<>(CONFUSABLE_FOLD);
                        for (int ambiguous : AMBIGUOUS_I_OR_L_CHARS) {
                            variant.put(ambiguous, ilReading);
                        }
                        for (int zero : AMBIGUOUS_O_OR_U_CHARS) {
                            variant.put(zero, zeroReading);
                        }
                        if (foldAt) {
                            variant.put(AT_SIGN, (int) 'a');
                        }
                        if (asteriskReading != null) {
                            variant.put(BANG_SIGN, (int) 'i');
                            variant.put(PLUS_SIGN, (int) 't');
                            variant.put(ASTERISK, (int) asteriskReading);
                        }
                        variants.add(Collections.unmodifiableMap(variant));
                    }
                }
            }
        }
        for (char vowel : VOWEL_READINGS) {
            Map<Integer, Integer> digitLiteral = new HashMap<>(CONFUSABLE_FOLD);
            for (int digitKey : LETTER_FOLDED_DIGITS) {
                digitLiteral.remove(digitKey);
            }
            digitLiteral.put(ASTERISK, (int) vowel);
            variants.add(Collections.unmodifiableMap(digitLiteral));
        }
        return Collections.unmodifiableList(variants);
    }

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

    /**
     * Inclusive bounds of the core Devanagari Unicode block (T-GOLIVE-0918-R2, vikram ·
     * 2026-09-18). See {@link #isDevanagariCombiningMark} for why marks in this range are treated
     * differently from every other combining mark in {@link #normalizeForMatching}.
     */
    private static final int DEVANAGARI_BLOCK_START = 0x0900;

    private static final int DEVANAGARI_BLOCK_END = 0x097F;

    /**
     * T-GOLIVE-0918-R3 (vikram · 2026-09-18) — repair round 3 MEDIUM fix. The nukta (़, a dot placed
     * under a consonant to represent a sound Devanagari's base consonants do not cover, e.g. क़/ख़/
     * ग़/ज़/ड़/ढ़/फ़) is DROPPED during normalization rather than kept like every other Devanagari
     * mark in {@link #isDevanagariCombiningMark}. An independent reviewer's probe found the SAME word
     * bypassing depending only on whether its writer included the nukta: "ख़ुदकुशी कर ली" (nukta)
     * bypassed while "खुदकुशी" (no nukta, already listed) blocked; "महिला को ज़िंदा जलाया" (nukta on
     * ज) bypassed the already-listed "जिंदा जलाया"; "छेडछाड का आरोपी" (no nukta at all) bypassed the
     * listed "छेड़छाड़"-based terms. Both spellings are the same word in ordinary Hindi typing —
     * nukta placement is inconsistent across keyboards, fonts and writers, not a meaningful spelling
     * distinction this filter should treat as two different words. NFD (already applied above)
     * canonically decomposes the precomposed nukta letters (U+0958-095F) into base-consonant +
     * U+093C, so dropping U+093C here folds BOTH the precomposed and the decomposed spelling onto
     * the plain base consonant uniformly. Every Devanagari term in {@link UnsafeHeadlineTopic} that
     * would otherwise need a nukta is therefore written WITHOUT it (nukta-bearing spellings would
     * fail the class-load self-check below, since they could never equal their own normalized form).
     */
    private static final int DEVANAGARI_NUKTA = 0x093C;

    /**
     * T-GOLIVE-0918-R3 (vikram · 2026-09-18) — repair round 3 MEDIUM fix. Chandrabindu (ँ, marking
     * nasalization on a vowel) folds onto anusvara (ं, U+0902) — a DIFFERENT mark by Unicode's own
     * accounting (not a canonical equivalence NFD would already merge), but the same nasal sound in
     * the vast majority of everyday Hindi words, and writers use the two interchangeably in casual
     * typing. An independent reviewer's probe found "फाँसी लगाकर जान दी" (chandrabindu) bypassing the
     * already-listed anusvara spelling "फांसी", and "हत्याएँ बढ़ीं" (chandrabindu) bypassing the
     * already-listed anusvara spelling "हत्याएं" — same word, same meaning, different mark under the
     * same vowel. Folded the same direction as {@link #CONFUSABLE_FOLD} folds a homoglyph onto the
     * plain letter it is being mistaken for: onto the MORE COMMON spelling already in the term list,
     * not the other way around, so no Devanagari term needs to change. Kept to this one pair rather
     * than a general "nasalization-insensitive" scheme — it is the one confusion an independent
     * reviewer's probe named outright, same discipline as {@link #CONFUSABLE_FOLD}'s own small,
     * curated, probe-driven scope.
     */
    private static final int DEVANAGARI_CHANDRABINDU = 0x0901;

    private static final int DEVANAGARI_ANUSVARA = 0x0902;

    /**
     * Devanagari's OWN combining marks — vowel signs/matras ({@code ा ि ी ु ू े ै ो ौ}), virama
     * ({@code ्}), anusvara ({@code ं}), candrabindu, visarga and nukta — must NOT vanish the way
     * every other combining mark does under rule 2 below (T-GOLIVE-0918-R2 repair round 2, vikram
     * 2026-09-18, fixing a MEDIUM an independent reviewer found in 49a0415).
     *
     * <p><b>Why this was wrong before.</b> Rule 2 was written for Latin-script evasion: a combining
     * diacritic bolted onto "murder" is noise an attacker added, and stripping it is correct. But a
     * Devanagari vowel sign or anusvara is not noise added on top of a word — it IS the word.
     * Stripping them collapsed distinct Hindi words onto the same "consonant skeleton": दंगा
     * (danga, riot), देगा (dega, "will give"), देगी, देंगे and दूंगा (all ordinary future-tense
     * forms of "to give") and दाग (daag, "spot/stain" — a core skincare word) all reduced to the
     * same 2-3 character skeleton, so the term meant to catch दंगा also blocked the everyday
     * words. The reviewer's probe: "यह क्रीम देगी ग्लो", "दिवाली पर ऑफर देंगे ब्रांड", "चेहरे के दाग
     * हटाएं" were all wrongly rejected as COMMUNAL.
     *
     * <p><b>The fix.</b> Keep every Devanagari mark as a real, sequence-distinguishing character
     * (appended like a letter, never breaking the token — see the call site) instead of deleting
     * it. Devanagari terms in {@link UnsafeHeadlineTopic} are now written in their CORRECTLY
     * SPELLED form (matras and virama included), the same way Latin terms are spelled out in full,
     * rather than as an artificial stripped skeleton — so दंगा/देगा/देंगे/दूंगा/दाग are four
     * genuinely different code-point sequences and only the one that is actually spelled "दंगा"
     * (or its own inflections, listed as their own literals below) matches. Verified empirically
     * against this JVM's Unicode data (all of ं/े/ा/ी/ो/् above are General_Category Mn or Mc, and
     * every consonant/independent vowel is Lo) before relying on it.
     */
    private static boolean isDevanagariCombiningMark(int cp, int type) {
        return cp >= DEVANAGARI_BLOCK_START
                && cp <= DEVANAGARI_BLOCK_END
                && (type == Character.NON_SPACING_MARK
                        || type == Character.COMBINING_SPACING_MARK
                        || type == Character.ENCLOSING_MARK);
    }

    /**
     * T-GOLIVE-0918-R4 (vikram · 2026-09-19) — repair round 4 MEDIUM fix. True for any code point
     * (mark or base letter alike) inside the core Devanagari block — used by rule 5 in {@link
     * #normalizeForMatching} to treat a Latin&lt;-&gt;Devanagari script change as a token boundary,
     * the same way a case change or a letter&lt;-&gt;digit change already is. See that rule's own
     * comment for the bypass this closes.
     */
    private static boolean isDevanagariCodePoint(int cp) {
        return cp >= DEVANAGARI_BLOCK_START && cp <= DEVANAGARI_BLOCK_END;
    }

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
    private static final Set<String> GENERATED_FORM_BLOCKLIST =
            Set.of(
                    "killer",
                    "killers",
                    // F-0857 repair round 1 (vikram · 2026-09-18) — "bomber"/"blaster"/"blasting"/
                    // "arresting" are exactly the same class of accepted false positive as
                    // "killer" above: generated by the ordinary -er/-ing suffix rule from terms
                    // ("bomb", "blast", "arrest") this filter must keep, but the generated forms
                    // are ordinary creator/fashion/sports vocabulary ("Bomber jacket styling",
                    // "Nerf blaster unboxing", "Blasting music workout", "Arresting sunset
                    // shots") with no plausible unsafe reading. An independent reviewer's probe
                    // found these newly over-blocked after F-0853/F-0855's suffix generation
                    // shipped in 927002a.
                    "bomber",
                    "bombers",
                    "blaster",
                    "blasters",
                    "blasting",
                    "arresting");

    /**
     * T-GOLIVE-0918-R3 [vikram · 2026-09-19] — a digit-literal reading, used ONLY to decide
     * whether a "dead" occurrence's allow-listed compound (see {@link #DEAD_COMPOUND_ALLOWLIST})
     * truly ends at a token boundary.
     *
     * <p>Reusing whichever leetspeak-folded {@link #CONFUSABLE_FOLD_VARIANTS} entry happens to be
     * under test for the PRIMARY unsafe-term match is wrong here: {@link #CONFUSABLE_FOLD}'s
     * default '3'-&gt;'e' fold (kept so an evasion attempt like "d3ad" still matches "dead") also
     * erases rule 5's digit&lt;-&gt;letter token boundary wherever it applies elsewhere in the same
     * headline. "#DeadPool3 review" is exactly this: under a '3'-&gt;'e' variant, "Pool" and the
     * folded digit merge into one token with no boundary between "Pool" and where "3" used to be,
     * so requiring {@code DEAD_COMPOUND_ALLOWLIST}'s "deadpool" to end at a token boundary under
     * THAT SAME variant would wrongly refuse to allow-list a headline that, letter for letter, is
     * a benign hashtag. Nobody evades detection of a BENIGN word, so the allow-list has no reason
     * to try every leetspeak reading the way unsafe-term matching does — it always uses the one
     * reading where digits stay digits and the trailing-digit boundary stays visible, independent
     * of whichever variant the caller is currently testing. Built the same way as the
     * "digit-literal" variants inside {@link #buildConfusableFoldVariants} (start from {@link
     * #CONFUSABLE_FOLD}, remove every {@link #LETTER_FOLDED_DIGITS} key) — computed once here
     * rather than picked out of that list so this class does not depend on that list's internal
     * ordering. Must stay declared ABOVE the anti-vacuity {@code static} guard below, same
     * requirement as {@link #GENERATED_SUFFIXES}/{@link #GENERATED_FORM_BLOCKLIST}.
     */
    private static final Map<Integer, Integer> DEAD_ALLOWLIST_BOUNDARY_FOLD = buildDeadAllowlistBoundaryFold();

    private static Map<Integer, Integer> buildDeadAllowlistBoundaryFold() {
        Map<Integer, Integer> fold = new HashMap<>(CONFUSABLE_FOLD);
        for (int digitKey : LETTER_FOLDED_DIGITS) {
            fold.remove(digitKey);
        }
        return Collections.unmodifiableMap(fold);
    }

    /**
     * T-GOLIVE-0918-R3 [vikram · 2026-09-19] — precise allow-list for the DEATH term "dead" only,
     * per Amendment 2026-09-19 to wiki/decisions/2026-09-18-trend-headline-screening.md
     * ("Over-blocking is not accepted... dead skin, dead ends, deadlift, dead hang, dead bug and
     * Deadpool" must stay quotable, with the same severity as a bypass). Every entry is a compact
     * form that STARTS WITH "dead", so {@link #isAllowlistedDeadOccurrence} can only ever rescue an
     * actual "dead" occurrence at the position it is checked against — it cannot widen any other
     * DEATH/CRIME/COMMUNAL/LEGAL term, and it cannot rescue a "dead" occurring elsewhere in the same
     * headline (checked per-occurrence, not per-headline; see {@link #containsTerm}). "Man found
     * dead in Delhi", "3 dead in Mumbai building collapse", "Dead body recovered from lake", "death
     * toll rises" and "#DeadInDelhi" all still match "dead"/"death" at a position none of these
     * compounds cover, so they keep blocking. Same declaration-order requirement as {@link
     * #GENERATED_SUFFIXES}/{@link #GENERATED_FORM_BLOCKLIST} above — must stay declared ABOVE the
     * anti-vacuity {@code static} guard below, which reaches this via {@code containsTerm}.
     */
    private static final Set<String> DEAD_COMPOUND_ALLOWLIST =
            Set.of(
                    "deadskin", "deadends", "deadhang", "deadbug", "deadlift", "deadpool",
                    "deadcute", "deadsea", "deadline");

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
        // Emptiness (all-invisible / all-format-character text) does not depend on WHICH
        // confusable-fold variant is used — folding never turns a letter invisible or vice versa —
        // so the canonical single-variant normalization is enough to decide it.
        NormalizedText normalized = normalizeForMatching(text);
        if (normalized.compact().isEmpty()) {
            return false;
        }
        return firstUnsafeTopic(text) == null;
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
        // T-GOLIVE-0918-R3 [vikram · 2026-09-19] — computed ONCE per headline, independent of
        // which leetspeak-folded variant is under test below. See DEAD_ALLOWLIST_BOUNDARY_FOLD's
        // javadoc for why the "dead" allow-list needs its own digit-literal reading rather than
        // reusing whichever variant found the "dead" match.
        NormalizedText deadAllowlistReference = normalizeForMatching(headline, DEAD_ALLOWLIST_BOUNDARY_FOLD);
        // F-0857 repair round 1 (vikram · 2026-09-18) — try every confusable-fold reading
        // (CONFUSABLE_FOLD_VARIANTS) and block on the first one that matches. See that field's
        // javadoc for why a single static fold cannot be correct for '1'/'|'/'@'.
        for (Map<Integer, Integer> foldVariant : CONFUSABLE_FOLD_VARIANTS) {
            UnsafeHeadlineTopic hit =
                    firstUnsafeTopic(normalizeForMatching(headline, foldVariant), deadAllowlistReference);
            if (hit != null) {
                return hit;
            }
        }
        return null;
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
    private static UnsafeHeadlineTopic firstUnsafeTopic(
            NormalizedText normalized, NormalizedText deadAllowlistReference) {
        if (normalized.compact().isEmpty()) {
            return null;
        }
        for (UnsafeHeadlineTopic topic : UnsafeHeadlineTopic.values()) {
            for (String term : topic.terms()) {
                if (matchesTerm(normalized, term, deadAllowlistReference)) {
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
    private static boolean matchesTerm(
            NormalizedText normalized, String term, NormalizedText deadAllowlistReference) {
        String compact = compactTerm(term);
        if (containsTerm(normalized, compact, deadAllowlistReference)) {
            return true;
        }
        for (String suffix : GENERATED_SUFFIXES) {
            String candidate = compact + suffix;
            if (!GENERATED_FORM_BLOCKLIST.contains(candidate)
                    && containsTerm(normalized, candidate, deadAllowlistReference)) {
                return true;
            }
        }
        int n = compact.length();
        if (n >= 2 && compact.charAt(n - 1) == 'y' && "aeiou".indexOf(compact.charAt(n - 2)) < 0) {
            String iesForm = compact.substring(0, n - 1) + "ies";
            if (!GENERATED_FORM_BLOCKLIST.contains(iesForm)
                    && containsTerm(normalized, iesForm, deadAllowlistReference)) {
                return true;
            }
        }
        // F-0857 repair round 1 (vikram · 2026-09-18) — e-dropping inflections for a term ending
        // in a bare "e". GENERATED_SUFFIXES' plain "+ed"/"+ing" gives WRONG spellings for these
        // ("stampede"+"ed"="stampedeed", "rape"+"ing"="rapeing") that never match anything, which
        // is exactly what let "stampeded"/"stampeding"/"raping"/"abusing"/"policing" bypass on
        // 927002a — the code comment there even claimed this rule already existed. The correct
        // English inflections drop the trailing "e": add just "d" for the past tense ("stampede"
        // -> "stampeded", "rape" -> "raped") and drop-e-then-"ing" for the gerund ("stampede" ->
        // "stampeding", "rape" -> "raping", "police" -> "policing"). Deliberately narrow — this is
        // still a literal suffix rule, not a spelling engine (see this method's class javadoc on
        // why consonant doubling stays unhandled).
        if (n >= 2 && compact.charAt(n - 1) == 'e') {
            String dForm = compact + "d";
            if (!GENERATED_FORM_BLOCKLIST.contains(dForm)
                    && containsTerm(normalized, dForm, deadAllowlistReference)) {
                return true;
            }
            String ingDropEForm = compact.substring(0, n - 1) + "ing";
            if (!GENERATED_FORM_BLOCKLIST.contains(ingDropEForm)
                    && containsTerm(normalized, ingDropEForm, deadAllowlistReference)) {
                return true;
            }
        }
        return false;
    }

    private static String compactTerm(String term) {
        return term.indexOf(' ') < 0 ? term : term.replace(" ", "");
    }

    /**
     * Class-load self-check overload only (every term must match itself — see the {@code static}
     * guard below). Uses {@code normalized} as its own dead-allowlist reference, which is correct
     * there: a term string is hand-written plain text, never containing a digit adjacent to
     * "dead", so there is no digit-fold boundary discrepancy to correct for (see the 3-arg
     * overload's javadoc).
     */
    private static boolean containsTerm(NormalizedText normalized, String compactTerm) {
        return containsTerm(normalized, compactTerm, normalized);
    }

    private static boolean containsTerm(
            NormalizedText normalized, String compactTerm, NormalizedText deadAllowlistReference) {
        String compact = normalized.compact();
        int from = 0;
        while (true) {
            int start = compact.indexOf(compactTerm, from);
            if (start < 0) {
                return false;
            }
            int end = start + compactTerm.length() - 1;
            if (normalized.tokenStart().get(start) && normalized.tokenEnd().get(end)) {
                // T-GOLIVE-0918-R3 [vikram · 2026-09-19] — Amendment 2026-09-19: bare "dead" must
                // not block everyday fitness/skincare/brand vocabulary (dead skin, dead ends,
                // deadlift, dead hang, dead bug, Deadpool). Suppress ONLY this occurrence, ONLY
                // when the term being tested is the exact literal "dead" (never a generated form
                // like "deads"/"deaded" — compactTerm.equals guards that), and ONLY when
                // DEAD_COMPOUND_ALLOWLIST actually covers this specific position — a bare "dead"
                // with nothing matching after it still falls through to `return true` below, so
                // "Man found dead in Delhi"/"3 dead in Mumbai building collapse"/"Dead body
                // recovered from lake"/"#DeadInDelhi" are unaffected. Source: wiki/decisions/
                // 2026-09-18-trend-headline-screening.md (Amendment 2026-09-19).
                if (!(compactTerm.equals("dead")
                        && isAllowlistedDeadOccurrence(deadAllowlistReference, start))) {
                    return true;
                }
            }
            from = start + 1;
        }
    }

    /**
     * T-GOLIVE-0918-R3 [vikram · 2026-09-19] — true when the "dead" occurrence starting at {@code
     * start} is really the start of one of {@link #DEAD_COMPOUND_ALLOWLIST}'s benign compounds,
     * decided against {@code reference} (see {@link #DEAD_ALLOWLIST_BOUNDARY_FOLD}'s javadoc for
     * why this is a dedicated digit-literal reading rather than whichever variant found the "dead"
     * match). Checked with the SAME token-start/token-end anchoring {@link #containsTerm} uses for
     * every ordinary term, so a hashtag/camelCase/digit split — "#RomanDeadLift" -> Roman|Dead|Lift,
     * "#DeadLift2024" -> Dead|Lift|2024, "#DeadPool3" -> Dead|Pool|3 — rescues the compound exactly
     * as it would match it as an ordinary multi-word phrase term. Source: wiki/decisions/
     * 2026-09-18-trend-headline-screening.md (Amendment 2026-09-19).
     */
    private static boolean isAllowlistedDeadOccurrence(NormalizedText reference, int start) {
        String compact = reference.compact();
        for (String allow : DEAD_COMPOUND_ALLOWLIST) {
            int end = start + allow.length() - 1;
            if (compact.regionMatches(start, allow, 0, allow.length())
                    && reference.tokenStart().get(start)
                    && reference.tokenEnd().get(end)) {
                return true;
            }
        }
        return false;
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
    /**
     * The canonical (single-reading) normalization — used only where the ambiguous-confusable
     * question does not apply: the class-load term-form self-check (terms are hand-written plain
     * text, never leetspeak) and the all-invisible-text emptiness check in {@link
     * #isQuotableInCreatorCopy}. Actual headline matching goes through {@link
     * #firstUnsafeTopic(String)}, which tries every reading in {@link #CONFUSABLE_FOLD_VARIANTS}.
     */
    private static NormalizedText normalizeForMatching(String text) {
        return normalizeForMatching(text, CONFUSABLE_FOLD_VARIANTS.get(0));
    }

    private static NormalizedText normalizeForMatching(String text, Map<Integer, Integer> confusableFold) {
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
        // T-GOLIVE-0918-R2 (vikram, 2026-09-18) — tracks whether the character just appended was a
        // digit, independent of case, so a letter<->digit transition is its own boundary. See rule
        // 5's digitBoundary below.
        boolean previousAppendedWasDigit = false;
        // T-GOLIVE-0918-R4 (vikram · 2026-09-19) — tracks whether the character just appended was
        // Devanagari, independent of case/digit-ness, so a Latin<->Devanagari script change is its
        // own boundary. See rule 5's scriptBoundary below.
        boolean previousAppendedWasDevanagari = false;
        // T-GOLIVE-0918-R2 REPAIR ROUND 2 (vikram · 2026-09-19) — tracks whether the character just
        // appended was produced by folding BANG_SIGN/PLUS_SIGN/ASTERISK to a letter, as opposed to a
        // genuine letter typed by the source text. Such a fold is always lowercase (see
        // buildConfusableFoldVariants) but carries NONE of the "this is really where a new,
        // differently-cased word begins" signal a real lowercase letter carries — an all-caps run
        // like "K!LLED" folds '!' to lowercase 'i' purely because that IS the unambiguous letter
        // reading, not because the source text signalled a case change. Without this flag,
        // camelBoundary below would misread the fold's lowercase-ness as a real case transition and
        // split "K!LLED" into "ki"/"lled" right after it, the same way "DelhiRiots" splits into
        // "Delhi"/"Riots" — which is exactly why "K!LLED IN DELHI"/"M*RDER IN DELHI"/"R*PE CASE"/
        // "SU!C!DE NOTE FOUND" bypassed even after BANG_SIGN's fix removed the case-sensitive flank
        // check. See camelBoundary's computation below.
        boolean previousAppendedWasPunctuationFold = false;

        for (int i = 0; i < folded.length(); ) {
            int cp = folded.codePointAt(i);
            i += Character.charCount(cp);

            int type = Character.getType(cp);

            if (isDevanagariCombiningMark(cp, type)) {
                // T-GOLIVE-0918-R3 (vikram · 2026-09-18) — repair round 3 MEDIUM fix. The nukta
                // (U+093C) is dropped here, NOT appended like every other Devanagari mark below —
                // see DEVANAGARI_NUKTA's javadoc for why nukta-bearing and nukta-free spellings of
                // the same word must fold onto one form.
                if (cp == DEVANAGARI_NUKTA) {
                    continue;
                }
                // T-GOLIVE-0918-R3 — chandrabindu (ँ, U+0901) folds onto anusvara (ं, U+0902) —
                // see DEVANAGARI_CHANDRABINDU's javadoc. Every other Devanagari mark is unaffected.
                int foldedMark = cp == DEVANAGARI_CHANDRABINDU ? DEVANAGARI_ANUSVARA : cp;
                // Rule 2b (T-GOLIVE-0918-R2, vikram · 2026-09-18) — see
                // isDevanagariCombiningMark's javadoc for why these do NOT vanish like every other
                // combining mark: they are appended, like a letter, and never break the token.
                if (!inToken) {
                    tokenStart.set(compact.length());
                    if (spaced.length() > 0) {
                        spaced.append(' ');
                    }
                    inToken = true;
                }
                compact.appendCodePoint(foldedMark);
                spaced.appendCodePoint(foldedMark);
                previousAppendedWasLowerOrDigit = false;
                previousAppendedWasDigit = false;
                // T-GOLIVE-0918-R4 — a combining mark attached to a Devanagari base letter is
                // itself Devanagari; the token it extends stays Devanagari for scriptBoundary's sake.
                previousAppendedWasDevanagari = true;
                previousAppendedWasPunctuationFold = false;
                continue;
            }

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
            // T-GOLIVE-0918-R2 REPAIR ROUND 2 (vikram · 2026-09-19) — HIGH fix. BANG_SIGN/PLUS_SIGN/
            // ASTERISK now consult the per-variant fold table like every other character (no
            // case-sensitive flank decides WHICH letter they fold to — see BANG_SIGN's javadoc for
            // why the round-3/4 case-sensitive mechanism could not be made correct in both
            // directions). A minimal, case-INSENSITIVE gate is still required before even OFFERING
            // the fold, independent of that: these three are also ordinary punctuation/decoration
            // with no letter reading whenever they are not flanked by a letter-or-digit on BOTH
            // sides (space, string-start/end, or another punctuation mark) — e.g. "Rio+ Carnival"
            // (space after '+') and "**Murder**" (a second '*' has no letter/digit reading of its
            // own). Folding an unflanked mark would glue a separator into whatever token happens to
            // precede or follow it regardless of which variant is tried, exactly the round-1/round-3
            // "Rio+ Carnival looks" OVERBLOCK [COMMUNAL] (unconditional '+'->'t' turned "rio+" into
            // "riot" with nothing after the '+' to stop it). Unlike the deleted isWordFlank, this
            // gate does NOT look at the neighbour's case or digit-ness — only whether it is a letter
            // or digit AT ALL — so it does not reintroduce the case-sensitivity or the
            // lowercase-neighbour ambiguity that made the deleted mechanism unfixable; the actual
            // fold-or-not DECISION for a flanked mark is left entirely to CONFUSABLE_FOLD_VARIANTS
            // (see ASTERISK_READINGS), which is what correctly resolves "murder!pune shocked" (via
            // the variant that leaves '!' unfolded, so "murder" reaches its own token end) alongside
            // "dea+h toll" and "K!LLED IN DELHI" (via the variant that folds it).
            boolean isPunctuationLookalike = lowered == BANG_SIGN || lowered == PLUS_SIGN || lowered == ASTERISK;
            boolean flankedByLetterOrDigit =
                    inToken && i < folded.length() && Character.isLetterOrDigit(folded.codePointAt(i));
            int normalizedCp =
                    (isPunctuationLookalike && !flankedByLetterOrDigit)
                            ? lowered
                            : confusableFold.getOrDefault(lowered, lowered);
            boolean isPunctuationFold = isPunctuationLookalike && normalizedCp != lowered;

            if (Character.isLetterOrDigit(normalizedCp)) {
                boolean isDigitNow = Character.isDigit(normalizedCp);
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
                // T-GOLIVE-0918-R2 REPAIR ROUND 2 (vikram · 2026-09-19) — added
                // "&& !previousAppendedWasPunctuationFold": see that field's javadoc above. Without
                // it, an all-caps run containing a folded '!'/'+'/'*' (e.g. "K!LLED" -> "k","i","L",
                // "L",...) misread the fold's forced-lowercase 'i' as a genuine case transition and
                // split right after it, defeating BANG_SIGN's fix for all-caps headlines.
                boolean camelBoundary =
                        inToken
                                && isUpperBeforeFold
                                && previousAppendedWasLowerOrDigit
                                && !previousAppendedWasPunctuationFold;
                // T-GOLIVE-0918-R2 (vikram, 2026-09-18) — a letter<->digit transition is ALSO a
                // boundary, independent of case. Closes the digit-suffixed-hashtag gap an
                // independent reviewer found in 49a0415 (#DelhiRiots2020, #Riots2024,
                // #murder2023): the year glued onto the end of the camelCase-split word kept that
                // run from ever reaching a token END, so "riots"/"murder" never satisfied
                // containsTerm's end anchor. Source: wiki/decisions/2026-09-18-trend-headline-
                // screening.md ("hashtags split on case and joined-word boundaries").
                boolean digitBoundary = inToken && isDigitNow != previousAppendedWasDigit;
                // T-GOLIVE-0918-R2 repair round 2 (vikram · 2026-09-18) — repair round 4 fix
                // (vikram · 2026-09-19). A Latin<->Devanagari script change is ALSO a boundary,
                // independent of case/digit-ness — Devanagari has no case, so rule 5's camelBoundary
                // gives it no signal at all, and an independent reviewer's probe found
                // "#Delhiदंगे"/"#Delhiहत्याकांड" (Latin then Devanagari) and "#दंगेDelhi" (Devanagari
                // then Latin — a regression, BLOCKED at 49a0415 before this rule existed via the
                // since-replaced skeleton technique) all bypassing: with no boundary, "Delhi" and
                // "दंगे" glued into one token that neither term's own end/start anchor could ever
                // land on. See isDevanagariCodePoint's javadoc.
                boolean isDevanagariNow = isDevanagariCodePoint(normalizedCp);
                boolean scriptBoundary = inToken && isDevanagariNow != previousAppendedWasDevanagari;
                boolean boundary = camelBoundary || digitBoundary || scriptBoundary;
                if (!inToken || boundary) {
                    if (boundary) {
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
                previousAppendedWasDigit = isDigitNow;
                previousAppendedWasDevanagari = isDevanagariNow;
                previousAppendedWasPunctuationFold = isPunctuationFold;
            } else if (inToken) {
                tokenEnd.set(compact.length() - 1);
                inToken = false;
                previousAppendedWasLowerOrDigit = false;
                previousAppendedWasDigit = false;
                previousAppendedWasDevanagari = false;
                previousAppendedWasPunctuationFold = false;
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
