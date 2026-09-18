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
            } catch (BrandSafetyAiException e) {
                log.warn(
                        "TrendPullJob: rejected id={} source={} reason=classifier_error error={} —"
                                + " failing closed, not stored",
                        id,
                        raw.source(),
                        e.getMessage());
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
     * category — see class javadoc for why this is a blanket rule rather than a named subset. */
    private boolean isFlaggedByClassifier(String id, String text) {
        List<ClassifiedItem> results =
                brandSafetyAiClient.classify(
                        props.getClassifierWorkspaceId(), List.of(new ContentItem(id, text, null, null)));
        if (results == null || results.isEmpty()) {
            // Malformed/empty response the client itself didn't already reject — fail closed.
            throw new BrandSafetyAiException("empty classification result for " + id);
        }
        ClassifiedItem item = results.get(0);
        if (item.garmFlags() == null) {
            throw new BrandSafetyAiException("classification result for " + id + " carried no garm_flags");
        }
        for (GarmFlag flag : item.garmFlags()) {
            if (flag.risk() != null && !"floor".equalsIgnoreCase(flag.risk())) {
                return true;
            }
        }
        return false;
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
