package com.influora.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.common.Ulids;
import com.influora.config.TrendIngestProperties;
import com.influora.domain.entity.Trend;
import com.influora.domain.enums.TrendCampaignType;
import com.influora.domain.enums.TrendThemeSource;
import com.influora.integration.ai.BrandSafetyAiClient;
import com.influora.integration.ai.BrandSafetyAiException;
import com.influora.integration.ai.dto.BrandSafetyDtos.ClassifiedItem;
import com.influora.integration.ai.dto.BrandSafetyDtos.ContentItem;
import com.influora.integration.ai.dto.BrandSafetyDtos.GarmFlag;
import com.influora.service.creatorcopilot.TrendHeadlineScreener;
import com.influora.service.trendspark.ThemeMatchService;
import com.influora.service.trendspark.ingest.TrendIngestWriter;
import com.influora.service.trendspark.ingest.TrendSourceClient;
import com.influora.service.trendspark.ingest.TrendSourceClient.RawTrend;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * T-GOLIVE-0918 [vikram · 2026-09-18] — L10 trend-pull job. Pulls headlines from every configured
 * {@link TrendSourceClient}, screens each through the deterministic word filter AND the GARM
 * classifier (fail closed on either), tags surviving headlines against the closed theme taxonomy,
 * and stores only what passes with a soft expiry. Source of the rules:
 * wiki/decisions/2026-09-18-trend-headline-screening.md; source of the job shape:
 * .proof-os/tasks/T-COPILOT-ON-0910/job-design.md step 13.
 *
 * <p><b>Descoped from the full job-design (flagged for the reviewer, not silently dropped):</b>
 * <ul>
 *   <li>No AI theme-recovery pass (job-design step 11) — a headline the deterministic keyword
 *       tagger cannot match to any taxonomy theme is DROPPED, not sent to a recovery model. Safe
 *       (never stores a wrongly-tagged row) but yields fewer trends than the full design.
 *   <li>No {@code CampaignRulebookService}/campaign-rulebook.json read (job-design steps 8-10) —
 *       every stored row is tagged {@link TrendCampaignType#EDUCATIONAL} with that type's 30-day
 *       peak window. This is a real behavior gap versus the n8n workflow's HYPE/SEASONAL/PRIDE
 *       branching, left for a follow-up rather than guessed here.
 *   <li>GARM reject rule: the ruling names death/injury/crime/terrorism/violence/hate-speech/
 *       self-harm/legal-proceedings, but influora-ai's actual fixed GARM taxonomy (10 categories,
 *       {@code app/tools/schemas.py::GARM_CATEGORIES}) has no separate self-harm or legal-
 *       proceedings category. This job therefore rejects on ANY category flagged above the
 *       {@code "floor"} risk level, not a named subset — the conservative reading of "fail
 *       closed" for a headline that will be shown to creators.
 * </ul>
 *
 * <p><b>Escalated, not decided here:</b> {@link BrandSafetyAiClient#classify} bills a specific
 * workspace's AI credits per call; there is no platform/system workspace in this codebase. Until
 * {@link TrendIngestProperties#hasClassifierWorkspaceId()} is configured with a real decision from
 * Swapnil/Priya on who pays, this job fails closed and stores nothing at all — see
 * {@link TrendIngestProperties#getClassifierWorkspaceId()} javadoc.
 */
@Component
public class TrendPullJob {

    private static final Logger log = LoggerFactory.getLogger(TrendPullJob.class);

    // T-GOLIVE-0918 repair round 1 [vikram · 2026-09-18] — MEDIUM #2: the classifier result was
    // trusted structurally (a null risk was silently skipped, not rejected; an empty garm_flags
    // list fell through the for-loop as "nothing flagged"). A GarmFlag with risk=null was proven
    // to get stored (reviewer probe: nullRisk stored=1), and so was an empty garm_flags list
    // (emptyFlags stored=1). Source of the 10-category/4-risk-level contract:
    // influora-ai/app/tools/schemas.py::GARM_CATEGORIES / GARM_RISK_LEVELS — influora-ai's own
    // _validate_model_result enforces this shape server-side today, but a DTO/contract drift (a
    // renamed field under @JsonIgnoreProperties(ignoreUnknown=true)) would null every risk and
    // this job would treat every headline as safe. See #isFlaggedByClassifier.
    private static final int EXPECTED_GARM_CATEGORY_COUNT = 10;
    private static final Set<String> VALID_GARM_RISK_LEVELS = Set.of("floor", "low", "medium", "high");

    // T-GOLIVE-0918 repair round 2 [vikram · 2026-09-18] — LOW fix: repair round 1 checked only
    // the COUNT of garm_flags (10), not which categories they named. Reviewer probe p04 sent 10
    // flags all for "spam_or_harmful_content" at "floor" — none of the other 9 categories were
    // present at all — and the headline was stored=1, because a count-only check can't tell 10
    // duplicates of one category from one-of-each. influora-ai's own _validate_model_result
    // enforces the real shape server-side today (so this is defence-in-depth against future
    // contract drift, not a currently-reachable prod path), closed here by checking the exact
    // category SET, not just its size. Source of the 10 fixed names:
    // influora-ai/app/tools/schemas.py::GARM_CATEGORIES.
    private static final Set<String> EXPECTED_GARM_CATEGORIES =
            Set.of(
                    "adult_explicit_sexual_content",
                    "arms_ammunition",
                    "crime_harmful_acts_to_individuals",
                    "death_injury_military_conflict",
                    "hate_speech_acts_of_aggression",
                    "illegal_drugs_tobacco_alcohol",
                    "obscenity_profanity",
                    "spam_or_harmful_content",
                    "terrorism",
                    "debated_sensitive_social_issues");

    /** job-design.md step 12 verified typicals (HYPE 3, SEASONAL 21, PRIDE 1, EDUCATIONAL 30).
     * Only EDUCATIONAL is reachable today — see class javadoc "descoped" note. */
    private static final Map<TrendCampaignType, Integer> PEAK_WINDOW_DAYS =
            Map.of(
                    TrendCampaignType.HYPE, 3,
                    TrendCampaignType.SEASONAL, 21,
                    TrendCampaignType.PRIDE, 1,
                    TrendCampaignType.EDUCATIONAL, 30);

    private final List<TrendSourceClient> sourceClients;
    private final TrendHeadlineScreenerPort screener;
    private final BrandSafetyAiClient brandSafetyAiClient;
    private final ThemeMatchService themeMatchService;
    private final TrendIngestWriter writer;
    private final TrendIngestProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TrendPullJob(
            List<TrendSourceClient> sourceClients,
            BrandSafetyAiClient brandSafetyAiClient,
            ThemeMatchService themeMatchService,
            TrendIngestWriter writer,
            TrendIngestProperties props) {
        this(
                sourceClients,
                TrendHeadlineScreener::isSafeForCreatorCopy,
                TrendHeadlineScreener::rejectionCategory,
                brandSafetyAiClient,
                themeMatchService,
                writer,
                props);
    }

    /** Test-only seam so a unit test can substitute the word filter without a static-mock
     * framework — production always goes through the two-arg constructor above, which wires the
     * real {@code TrendHeadlineScreener}. */
    TrendPullJob(
            List<TrendSourceClient> sourceClients,
            java.util.function.Predicate<String> isSafe,
            java.util.function.Function<String, String> rejectionCategory,
            BrandSafetyAiClient brandSafetyAiClient,
            ThemeMatchService themeMatchService,
            TrendIngestWriter writer,
            TrendIngestProperties props) {
        this.sourceClients = sourceClients;
        this.screener = new TrendHeadlineScreenerPort(isSafe, rejectionCategory);
        this.brandSafetyAiClient = brandSafetyAiClient;
        this.themeMatchService = themeMatchService;
        this.writer = writer;
        this.props = props;
    }

    @Scheduled(cron = "${influora.trend-ingest.pull-cron:0 0 5 * * *}", zone = "UTC")
    @SchedulerLock(name = "TrendPullJob", lockAtMostFor = "PT20M", lockAtLeastFor = "PT1M")
    public void pullTrends() {
        if (!props.isEnabled()) {
            log.info("TrendPullJob: disabled (influora.trend-ingest.enabled=false), skipping run");
            return;
        }

        int sourcesOk = 0;
        int sourcesSkippedNoKey = 0;
        List<RawTrend> fetched = new ArrayList<>();
        for (TrendSourceClient client : sourceClients) {
            if (!client.isConfigured()) {
                sourcesSkippedNoKey++;
                log.info(
                        "TrendPullJob: source {} skipped — no API key configured (F-0781)",
                        client.sourceId());
                continue;
            }
            List<RawTrend> rows = safeFetch(client);
            if (rows.isEmpty()) {
                log.warn("TrendPullJob: source {} returned no rows this run", client.sourceId());
            } else {
                sourcesOk++;
            }
            fetched.addAll(rows);
        }

        if (fetched.isEmpty()) {
            log.warn(
                    "TrendPullJob: completed run — no rows fetched from any source (sourcesOk={},"
                            + " sourcesSkippedNoKey={}); nothing written",
                    sourcesOk,
                    sourcesSkippedNoKey);
            return;
        }

        LocalDate detectedDate = LocalDate.now(ZoneOffset.UTC);
        List<RawTrend> deduped = dedup(fetched, detectedDate);
        int droppedByCap = 0;
        if (deduped.size() > props.getMaxRowsPerRun()) {
            droppedByCap = deduped.size() - props.getMaxRowsPerRun();
            deduped = deduped.subList(0, props.getMaxRowsPerRun());
        }

        int wordFilterRejected = 0;
        int classifierRejected = 0;
        int classifierFailedOrUnconfigured = 0;
        int themesEmptyDropped = 0;
        Instant now = Instant.now();
        List<Trend> toWrite = new ArrayList<>();

        for (RawTrend raw : deduped) {
            String id = Ulids.newUlid();

            if (!screener.isSafe().test(raw.text())) {
                String category = screener.rejectionCategory().apply(raw.text());
                log.info(
                        "TrendPullJob: rejected id={} source={} reason=word_filter category={}",
                        id,
                        raw.source(),
                        category);
                wordFilterRejected++;
                continue;
            }

            if (!props.hasClassifierWorkspaceId()) {
                log.warn(
                        "TrendPullJob: rejected id={} source={} reason=classifier_unconfigured — no"
                                + " influora.trend-ingest.classifier-workspace-id set, failing closed",
                        id,
                        raw.source());
                classifierFailedOrUnconfigured++;
                continue;
            }

            boolean flagged;
            try {
                flagged = isFlaggedByClassifier(id, raw.text());
            } catch (RuntimeException e) {
                // T-GOLIVE-0918 repair round 2 [vikram · 2026-09-18] — LOW fix: this used to catch
                // only BrandSafetyAiException. BrandSafetyAiClient#classify calls
                // BrandSafetyServiceTokenService#mint(workspaceId) BEFORE its own try/catch, so a
                // JWT-signing/key-load failure there throws a plain RuntimeException (e.g.
                // IllegalStateException), which fell through this catch, out of the for-loop, and
                // out of pullTrends entirely — reviewer probe p01: "THREW IllegalStateException ...
                // stored=0", discarding every headline already approved earlier in the SAME run,
                // not just the one that hit the bad call. Widening to RuntimeException keeps the
                // exact same fail-closed behavior per headline (still never stored, still counted
                // and logged) but stops one classifier-path failure from aborting the whole run —
                // matching every other per-item resilience discipline in this class (see
                // #safeFetch) instead of contradicting it.
                log.warn(
                        "TrendPullJob: rejected id={} source={} reason=classifier_error error={} ({}) —"
                                + " failing closed, not stored",
                        id,
                        raw.source(),
                        e.getMessage(),
                        e.getClass().getSimpleName());
                classifierFailedOrUnconfigured++;
                continue;
            }
            if (flagged) {
                log.info(
                        "TrendPullJob: rejected id={} source={} reason=garm_classifier",
                        id,
                        raw.source());
                classifierRejected++;
                continue;
            }

            Set<String> themes = themeMatchService.themesForText(raw.text());
            if (themes.isEmpty()) {
                log.info(
                        "TrendPullJob: rejected id={} source={} reason=no_known_theme (F-0823)",
                        id,
                        raw.source());
                themesEmptyDropped++;
                continue;
            }

            TrendCampaignType campaignType = TrendCampaignType.EDUCATIONAL; // see class javadoc
            int peakWindowDays = PEAK_WINDOW_DAYS.get(campaignType);
            Instant expiresAt = now.plus(peakWindowDays, ChronoUnit.DAYS);

            toWrite.add(
                    Trend.create(
                            id,
                            truncate(raw.text(), 500),
                            writeJsonArray(List.of(raw.source().toLowerCase(Locale.ROOT))),
                            props.getRegion(),
                            detectedDate,
                            peakWindowDays,
                            expiresAt,
                            writeJsonArray(List.copyOf(themes)),
                            campaignType,
                            TrendThemeSource.KEYWORD,
                            now,
                            now));
        }

        int written = writer.writeAll(toWrite);

        log.info(
                "TrendPullJob: completed run — sourcesOk={} sourcesSkippedNoKey={} fetched={}"
                        + " deduped={} droppedByCap={} wordFilterRejected={} classifierRejected={}"
                        + " classifierFailedOrUnconfigured={} themesEmptyDropped={} written={}",
                sourcesOk,
                sourcesSkippedNoKey,
                fetched.size(),
                deduped.size(),
                droppedByCap,
                wordFilterRejected,
                classifierRejected,
                classifierFailedOrUnconfigured,
                themesEmptyDropped,
                written);
    }

    /** {@link TrendSourceClient#fetch()} is documented never to throw, but this job must not go
     * down with a misbehaving source regardless — defensive catch, matching every other job's
     * per-item resilience discipline in this package. */
    private List<RawTrend> safeFetch(TrendSourceClient client) {
        try {
            List<RawTrend> rows = client.fetch();
            return rows == null ? List.of() : rows;
        } catch (Exception e) {
            log.warn("TrendPullJob: source {} threw despite its never-throws contract: {}",
                    client.sourceId(), e.getMessage());
            return List.of();
        }
    }

    /** True if the classifier flags {@code text} above the {@code "floor"} risk level in ANY GARM
     * category — see class javadoc for why this is a blanket rule rather than a named subset.
     *
     * <p>T-GOLIVE-0918 repair round 1 [vikram · 2026-09-18] — MEDIUM #2 fix: a malformed result is
     * now rejected with the SAME fail-closed treatment as a transport error (thrown
     * {@link BrandSafetyAiException}, caught by the caller and counted as
     * {@code classifierFailedOrUnconfigured}), rather than silently read as "safe". Rejects when:
     * (a) {@code content_id} doesn't match the id this job sent — a mismatched/reordered result
     * must never be attributed to the wrong headline; (b) {@code garm_flags} is missing or its
     * size isn't exactly {@link #EXPECTED_GARM_CATEGORY_COUNT} — catches both a dropped category
     * and the empty-list case that used to pass the for-loop vacuously; (c) any flag's
     * {@code risk} is null or outside the 4 known levels — catches a renamed/blanked field under
     * lenient Jackson binding instead of treating a null risk as "not risky". */
    private boolean isFlaggedByClassifier(String id, String text) {
        List<ClassifiedItem> results =
                brandSafetyAiClient.classify(
                        props.getClassifierWorkspaceId(), List.of(new ContentItem(id, text, null, null)));
        if (results == null || results.isEmpty()) {
            // Malformed/empty response the client itself didn't already reject — fail closed.
            throw new BrandSafetyAiException("empty classification result for " + id);
        }
        ClassifiedItem item = results.get(0);
        if (!id.equals(item.contentId())) {
            throw new BrandSafetyAiException(
                    "classification result content_id mismatch for "
                            + id
                            + ": got '"
                            + item.contentId()
                            + "'");
        }
        List<GarmFlag> flags = item.garmFlags();
        if (flags == null || flags.size() != EXPECTED_GARM_CATEGORY_COUNT) {
            throw new BrandSafetyAiException(
                    "classification result for "
                            + id
                            + " carried "
                            + (flags == null ? "no" : flags.size())
                            + " garm_flags, expected "
                            + EXPECTED_GARM_CATEGORY_COUNT);
        }
        // T-GOLIVE-0918 repair round 2 [vikram · 2026-09-18] — LOW fix: a count check alone lets
        // 10 flags all name the SAME category through (reviewer probe p04). Verify the categories
        // are exactly the known 10, not just that there are 10 of them.
        Set<String> seenCategories = new java.util.HashSet<>();
        boolean flagged = false;
        for (GarmFlag flag : flags) {
            String category = flag.category();
            if (category == null || !EXPECTED_GARM_CATEGORIES.contains(category)) {
                throw new BrandSafetyAiException(
                        "classification result for " + id + " carried an unknown category: " + category);
            }
            if (!seenCategories.add(category)) {
                throw new BrandSafetyAiException(
                        "classification result for " + id + " carried a duplicate category: " + category);
            }
            String risk = flag.risk() == null ? null : flag.risk().toLowerCase(Locale.ROOT);
            if (risk == null || !VALID_GARM_RISK_LEVELS.contains(risk)) {
                throw new BrandSafetyAiException(
                        "classification result for " + id + " carried an invalid risk level: " + flag.risk());
            }
            if (!"floor".equals(risk)) {
                flagged = true;
            }
        }
        if (!seenCategories.equals(EXPECTED_GARM_CATEGORIES)) {
            throw new BrandSafetyAiException(
                    "classification result for " + id + " did not cover all 10 known GARM categories");
        }
        return flagged;
    }

    /** Within-run dedup only (cross-run natural-key uniqueness is a known, documented gap — see
     * job-design.md "Risks"). Key mirrors the n8n workflow's HashSet key exactly:
     * {@code region|detectedDate|lower(trim(text))}. */
    private List<RawTrend> dedup(List<RawTrend> rows, LocalDate detectedDate) {
        Map<String, RawTrend> seen = new LinkedHashMap<>();
        for (RawTrend row : rows) {
            if (row.text() == null) {
                continue;
            }
            String key =
                    props.getRegion() + "|" + detectedDate + "|" + row.text().trim().toLowerCase(Locale.ROOT);
            seen.putIfAbsent(key, row);
        }
        return new ArrayList<>(seen.values());
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private String writeJsonArray(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception e) {
            // Unreachable for a List<String> under normal Jackson operation; fail loudly rather
            // than silently store a malformed JSON column.
            throw new IllegalStateException("failed to serialize JSON array column", e);
        }
    }

    /** Bundles the two {@code TrendHeadlineScreener} entry points behind one field so the
     * test-only constructor above can substitute both without a static-mock framework. */
    private record TrendHeadlineScreenerPort(
            java.util.function.Predicate<String> isSafe,
            java.util.function.Function<String, String> rejectionCategory) {}
}
