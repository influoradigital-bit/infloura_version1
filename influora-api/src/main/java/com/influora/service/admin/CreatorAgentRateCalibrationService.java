package com.influora.service.admin;

import com.influora.common.JsonLists;
import com.influora.domain.enums.OfferEvent;
import com.influora.repository.AuditLogEntryRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.CollaborationRepository.RateBandCandidateRow;
import com.influora.repository.DealOfferHistoryRepository;
import com.influora.service.rates.RateQuoteService;
import com.influora.service.rates.RateTierProperties;
import com.influora.service.scoring.CreatorTiers;
import com.influora.web.dto.admin.AdminCreatorAgentDtos.RateCalibrationResponse;
import com.influora.web.dto.admin.AdminCreatorAgentDtos.RateCalibrationTier;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;14.1.g, B0-35) — the instrument behind {@code GET
 * /admin/creator-agent/rate-calibration}.
 *
 * <p><b>Why this report exists.</b> Phase B0's pricing is a formula, not data. There are zero
 * completed deals carrying an {@code agreed_rate} in any seed migration, so at launch every quote
 * Meera issues comes from {@code RateEstimationService.TIER_BASE_RATES} — constants written for
 * the brand-side estimate card and never checked against a close. If they are high, Meera tells
 * every nano creator to open high and brands walk. If they are low, Meera anchors the cohort under
 * market and the tier-band branch later "confirms" it from Meera's own deals. This report is the
 * only thing that can tell the difference, and SPEC.md &sect;14.5.c metric 4 — the gate on starting
 * Phase B1 — reads from it.
 *
 * <p><b>Read it as three columns that should agree.</b> {@code benchmark_*} is what the formula
 * says, {@code realised_*} is what creators in that tier actually closed at, {@code quoted_*} is
 * what Meera has been saying. Divergence between the first two is the recalibration signal
 * (&sect;14.1.h); divergence between the second and third is the anchoring signal.
 *
 * <p><b>What it will show on day one: every tier below the floor.</b> With no completed priced
 * deals, every {@code realised_median} is null, every {@code realised_n} is 0, and the report says
 * so explicitly rather than rendering zeros that look like measurements. That is the correct and
 * expected output, not a bug — and it is why B0-36 (setting the yml overrides) cannot run until
 * real deals accumulate.
 *
 * <p><b>The k-anonymity gate.</b> {@code RateBandCandidateRow}'s javadoc carries Kabir's mandatory
 * Phase-2 rule: no row of that cross-tenant projection may be serialized. Nothing here escapes
 * aggregation — {@code workspaceId} is consumed only as a distinct COUNT, {@code creatorId} is
 * never read at all, {@code collaborationId} is used only as the {@code in :ids} argument of a
 * distinct-id query and then discarded, and a tier whose sample is below
 * {@link RateQuoteService#BAND_MIN_DEALS} emits no median and no share.
 *
 * <p>Occasional-use admin read computed in memory over already-small tables, the same tradeoff
 * {@link CreatorAgentBaselineService} makes and for the same reason.
 */
@Service
public class CreatorAgentRateCalibrationService {

    /**
     * Size order — NANO smallest to MEGA largest. NOT {@code CreatorTier}: {@code "MEGA"} is not a
     * value of that enum ({@link CreatorTiers} says so in its own javadoc), so nothing here may
     * call {@code CreatorTier.valueOf} on these strings. This is also emphatically not the admin
     * console's {@code TIER_ORDER}, which holds loyalty tiers (BRONZE..PLATINUM) and shares not one
     * value with this list.
     */
    static final List<String> RATE_TIER_ORDER =
            List.of(CreatorTiers.NANO, CreatorTiers.MICRO, CreatorTiers.MID, CreatorTiers.MACRO, CreatorTiers.MEGA);

    private static final String SOURCE_OVERRIDE = "yml override";
    private static final String SOURCE_COMPILED = "compiled default";

    private final CollaborationRepository collaborationRepository;
    private final DealOfferHistoryRepository dealOfferHistoryRepository;
    private final AuditLogEntryRepository auditLogEntryRepository;
    private final RateTierProperties rateTierProperties;

    public CreatorAgentRateCalibrationService(
            CollaborationRepository collaborationRepository,
            DealOfferHistoryRepository dealOfferHistoryRepository,
            AuditLogEntryRepository auditLogEntryRepository,
            RateTierProperties rateTierProperties) {
        this.collaborationRepository = collaborationRepository;
        this.dealOfferHistoryRepository = dealOfferHistoryRepository;
        this.auditLogEntryRepository = auditLogEntryRepository;
        this.rateTierProperties = rateTierProperties;
    }

    @Transactional(readOnly = true)
    public RateCalibrationResponse getRateCalibration() {
        Instant cutoff = Instant.now().minus(RateQuoteService.BAND_WINDOW_DAYS, ChronoUnit.DAYS);

        Map<String, List<RateBandCandidateRow>> realisedByTier = realisedByTier(cutoff);
        Map<String, List<BigDecimal>> quotedByTier = quotedTotalsByTier(cutoff);
        Set<String> anchoredCollaborations = anchoredCollaborations(realisedByTier);

        List<RateCalibrationTier> tiers = new ArrayList<>();
        for (String tier : RATE_TIER_ORDER) {
            tiers.add(
                    row(
                            tier,
                            realisedByTier.getOrDefault(tier, List.of()),
                            quotedByTier.getOrDefault(tier, List.of()),
                            anchoredCollaborations));
        }

        return new RateCalibrationResponse(
                List.copyOf(tiers),
                RateQuoteService.BAND_MIN_DEALS,
                RateQuoteService.BAND_WINDOW_DAYS,
                Instant.now());
    }

    /**
     * One tier's line. Every figure derived from the realised sample is suppressed together below
     * the floor; the two plain counts survive so the reader can see how far below it the tier is.
     */
    private RateCalibrationTier row(
            String tier,
            List<RateBandCandidateRow> realised,
            List<BigDecimal> quoted,
            Set<String> anchoredCollaborations) {

        long[] band = rateTierProperties.resolve(tier);
        BigDecimal benchmarkMin = band == null ? null : BigDecimal.valueOf(band[0]);
        BigDecimal benchmarkMax = band == null ? null : BigDecimal.valueOf(band[1]);
        String benchmarkSource =
                rateTierProperties.override(tier).isPresent() ? SOURCE_OVERRIDE : SOURCE_COMPILED;

        int realisedN = realised.size();
        int distinctWorkspaces =
                (int)
                        realised.stream()
                                .map(RateBandCandidateRow::getWorkspaceId)
                                .filter(Objects::nonNull)
                                .distinct()
                                .count();

        // The k-anonymity floor. Below it the sample produces no median and no share - not a zero,
        // not a partial figure. Removing this branch is what RateCalibrationFloorTest falsifies.
        BigDecimal realisedMedian = null;
        Double meeraAnchoredShare = null;
        if (realisedN >= RateQuoteService.BAND_MIN_DEALS) {
            realisedMedian =
                    RateQuoteService.roundRupees(
                            RateQuoteService.median(
                                    realised.stream()
                                            .map(RateBandCandidateRow::getAgreedRate)
                                            .sorted()
                                            .toList()));
            Set<String> bandIds =
                    realised.stream()
                            .map(RateBandCandidateRow::getCollaborationId)
                            .filter(Objects::nonNull)
                            .collect(java.util.stream.Collectors.toCollection(HashSet::new));
            if (!bandIds.isEmpty()) {
                long anchored = bandIds.stream().filter(anchoredCollaborations::contains).count();
                // Distinct on BOTH sides. deal_offer_history's unique key is
                // (collaboration_id, sequence_no), not (collaboration_id, event), so a row count
                // over MEERA_COUNTER can exceed the band size and push this above 1.0.
                meeraAnchoredShare = anchored / (double) bandIds.size();
            }
        }

        return new RateCalibrationTier(
                tier,
                benchmarkMin,
                benchmarkMax,
                RateQuoteService.benchmarkUnitFor(benchmarkMin, benchmarkMax),
                benchmarkSource,
                realisedMedian,
                realisedN,
                distinctWorkspaces,
                meeraAnchoredShare,
                quoted.isEmpty() ? null : RateQuoteService.roundRupees(RateQuoteService.median(sorted(quoted))),
                quoted.size());
    }

    /**
     * The same candidate pool, the same 90-day window and the same tier derivation
     * {@code RateQuoteService.tierBandUnit} uses — deliberately, because a report that measured a
     * different population than the one the quote path prices from would be describing pricing
     * that does not happen.
     *
     * <p>{@code updatedAt} is nullable in the projection's contract and is LAST-MODIFIED rather
     * than completed-at ({@code collaborations.updated_at} is {@code ON UPDATE
     * CURRENT_TIMESTAMP}); an unreadable timestamp drops the row rather than silently widening the
     * 90-day claim, exactly as the quote path does.
     */
    private Map<String, List<RateBandCandidateRow>> realisedByTier(Instant cutoff) {
        Map<String, List<RateBandCandidateRow>> byTier = new HashMap<>();
        for (RateBandCandidateRow row : collaborationRepository.findAllRateBandCandidates()) {
            if (row == null || row.getAgreedRate() == null || row.getAgreedRate().signum() <= 0) {
                continue;
            }
            if (row.getUpdatedAt() == null || !row.getUpdatedAt().isAfter(cutoff)) {
                continue;
            }
            Long followers = row.getTotalFollowers();
            byTier.computeIfAbsent(CreatorTiers.derive(followers == null ? 0L : followers), k -> new ArrayList<>())
                    .add(row);
        }
        return byTier;
    }

    /**
     * DISTINCT collaborations holding at least one {@code MEERA_COUNTER}, resolved in ONE query
     * across every tier's band rather than once per tier.
     *
     * <p>Only ids that are already in a band are ever passed in, and the returned ids are used
     * only for a set-membership test that produces a fraction. No id reaches the response.
     */
    private Set<String> anchoredCollaborations(Map<String, List<RateBandCandidateRow>> realisedByTier) {
        Set<String> allIds = new HashSet<>();
        for (List<RateBandCandidateRow> rows : realisedByTier.values()) {
            for (RateBandCandidateRow row : rows) {
                if (row.getCollaborationId() != null) {
                    allIds.add(row.getCollaborationId());
                }
            }
        }
        if (allIds.isEmpty()) {
            return Set.of();
        }
        List<String> anchored =
                dealOfferHistoryRepository.findDistinctCollaborationIdsByEvent(allIds, OfferEvent.MEERA_COUNTER);
        return anchored == null ? Set.of() : new HashSet<>(anchored);
    }

    /**
     * {@code RATE_QUOTE_ISSUED} totals in the window, bucketed by the {@code tier} the quote was
     * issued against.
     *
     * <p>Reads {@code detail_json} and nothing else — see
     * {@code AuditLogEntryRepository.findDetailJsonByEventTypeSince}, which selects that single
     * column precisely so the acting creator's user id never leaves the database. A row whose JSON
     * is unparseable, or carries no tier or no numeric total, is skipped rather than guessed at.
     */
    private Map<String, List<BigDecimal>> quotedTotalsByTier(Instant cutoff) {
        Map<String, List<BigDecimal>> byTier = new LinkedHashMap<>();
        List<String> details =
                auditLogEntryRepository.findDetailJsonByEventTypeSince(
                        RateQuoteService.EVENT_RATE_QUOTE_ISSUED, cutoff);
        for (String json : details) {
            Map<?, ?> detail = JsonLists.objectFromJson(json, Map.class);
            if (detail == null) {
                continue;
            }
            Object tier = detail.get("tier");
            Object total = detail.get("total");
            if (!(tier instanceof String tierName) || !(total instanceof Number amount)) {
                continue;
            }
            if (!RATE_TIER_ORDER.contains(tierName)) {
                // "UNKNOWN" (no metric row) and any future tier string are counted nowhere rather
                // than folded into a tier they do not belong to.
                continue;
            }
            byTier.computeIfAbsent(tierName, k -> new ArrayList<>())
                    .add(new BigDecimal(amount.toString()));
        }
        return byTier;
    }

    private static List<BigDecimal> sorted(List<BigDecimal> values) {
        return values.stream().sorted(Comparator.naturalOrder()).toList();
    }
}
