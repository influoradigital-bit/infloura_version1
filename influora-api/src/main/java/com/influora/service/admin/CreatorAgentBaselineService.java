package com.influora.service.admin;

import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.CreatorProfile;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.DealMessageRepository;
import com.influora.repository.DealMessageRepository.FirstMessageBySenderRow;
import com.influora.repository.MetaOAuthTokenRepository;
import com.influora.web.dto.admin.AdminCreatorAgentDtos.BaselinesResponse;
import com.influora.web.dto.admin.AdminCreatorAgentDtos.BriefsPerCreatorPerMonth;
import com.influora.web.dto.admin.AdminCreatorAgentDtos.SampleLabelCompliance;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * T-MEERA-CREATOR-PHASE-A (SPEC.md 2.1, A1) — pre-approval baseline metrics for {@code GET
 * /admin/creator-agent/baselines}: creators by tier, briefs (invites+applications) per active
 * creator per month, Meta connect rate, and median creator reply time. Admin-only, occasional-use
 * read — computed in-memory over already-small tables rather than a dedicated aggregation query,
 * same tradeoff other admin rollups in this codebase make.
 */
@Service
public class CreatorAgentBaselineService {

    private static final int WINDOW_DAYS = 30;

    /**
     * SPEC.md &sect;2.1's {@code sample_label_compliance} is explicitly "hand sample noted in
     * response, not automated yet (Phase D)" — this is that hand sample, not a live computation.
     * Update this constant (or replace it with a real Phase-D measurement) rather than treating it
     * as load-bearing data.
     */
    private static final SampleLabelCompliance HAND_SAMPLE_LABEL_COMPLIANCE = new SampleLabelCompliance(100, 73, 0.73);

    private final CreatorProfileRepository creatorProfileRepository;
    private final CollaborationRepository collaborationRepository;
    private final DealMessageRepository dealMessageRepository;
    private final MetaOAuthTokenRepository metaOAuthTokenRepository;

    public CreatorAgentBaselineService(
            CreatorProfileRepository creatorProfileRepository,
            CollaborationRepository collaborationRepository,
            DealMessageRepository dealMessageRepository,
            MetaOAuthTokenRepository metaOAuthTokenRepository) {
        this.creatorProfileRepository = creatorProfileRepository;
        this.collaborationRepository = collaborationRepository;
        this.dealMessageRepository = dealMessageRepository;
        this.metaOAuthTokenRepository = metaOAuthTokenRepository;
    }

    @Transactional(readOnly = true)
    public BaselinesResponse getBaselines() {
        List<CreatorProfile> allCreators = creatorProfileRepository.findAll();

        return new BaselinesResponse(
                creatorsByTier(allCreators),
                briefsPerCreatorPerMonth(),
                metaConnectRate(allCreators.size()),
                medianCreatorReplyHours(),
                HAND_SAMPLE_LABEL_COMPLIANCE,
                Instant.now());
    }

    /** Same tier bucketing as {@code RateEstimationService.determineTier}, plus MEGA (1M+) — that method has no MEGA bucket. */
    private static Map<String, Long> creatorsByTier(List<CreatorProfile> creators) {
        Map<String, Long> byTier = new TreeMap<>();
        for (CreatorProfile c : creators) {
            String tier = c.getTierOverride() != null ? c.getTierOverride().name() : deriveTier(c.getTotalFollowers());
            byTier.merge(tier, 1L, Long::sum);
        }
        return byTier;
    }

    private static String deriveTier(long followers) {
        if (followers >= 1_000_000) return "MEGA";
        if (followers >= 500_000) return "MACRO";
        if (followers >= 50_000) return "MID";
        if (followers >= 10_000) return "MICRO";
        return "NANO";
    }

    /** Distinct creators with >=1 collaboration (invite or application) opened in the last {@link #WINDOW_DAYS}. */
    private BriefsPerCreatorPerMonth briefsPerCreatorPerMonth() {
        Instant windowStart = Instant.now().minus(WINDOW_DAYS, ChronoUnit.DAYS);
        Map<String, Long> countsByCreator = new HashMap<>();
        for (Collaboration c : collaborationRepository.findAll()) {
            if (c.getAppliedAt() != null && c.getAppliedAt().isAfter(windowStart)) {
                countsByCreator.merge(c.getCreatorId(), 1L, Long::sum);
            }
        }
        List<Long> counts = new ArrayList<>(countsByCreator.values());
        Collections.sort(counts);
        return new BriefsPerCreatorPerMonth(percentile(counts, 50), percentile(counts, 75), percentile(counts, 90));
    }

    /** Nearest-rank percentile over an already-sorted list; 0 for an empty list (no active creators this window). */
    private static double percentile(List<Long> sorted, int pct) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int rank = (int) Math.ceil(pct / 100.0 * sorted.size());
        int index = Math.max(0, Math.min(sorted.size(), rank) - 1);
        return sorted.get(index);
    }

    private double metaConnectRate(int totalCreators) {
        if (totalCreators == 0) {
            return 0;
        }
        long connected = metaOAuthTokenRepository.countDistinctConnectedCreatorProfiles();
        return (double) connected / totalCreators;
    }

    /**
     * Median, across every deal that has messages from BOTH sides, of (first creator message -
     * first brand message) in hours. A deal where the creator wrote first (negative diff, e.g. an
     * application's opening message) is excluded — that is not a reply, it's the opening move.
     */
    private Double medianCreatorReplyHours() {
        Map<String, Instant> firstBrandByCollaboration = new HashMap<>();
        Map<String, Instant> firstCreatorByCollaboration = new HashMap<>();
        for (FirstMessageBySenderRow row : dealMessageRepository.findFirstMessageTimestampsBySender()) {
            switch (row.getSenderType()) {
                case brand -> firstBrandByCollaboration.put(row.getCollaborationId(), row.getFirstAt());
                case creator -> firstCreatorByCollaboration.put(row.getCollaborationId(), row.getFirstAt());
                default -> {
                    // system-only rows are excluded by the repository query itself.
                }
            }
        }

        List<Double> replyHours = new ArrayList<>();
        for (Map.Entry<String, Instant> entry : firstBrandByCollaboration.entrySet()) {
            Instant creatorFirst = firstCreatorByCollaboration.get(entry.getKey());
            if (creatorFirst == null || !creatorFirst.isAfter(entry.getValue())) {
                continue;
            }
            replyHours.add(Duration.between(entry.getValue(), creatorFirst).toMinutes() / 60.0);
        }
        if (replyHours.isEmpty()) {
            return null;
        }
        Collections.sort(replyHours);
        int mid = replyHours.size() / 2;
        return replyHours.size() % 2 == 1
                ? replyHours.get(mid)
                : (replyHours.get(mid - 1) + replyHours.get(mid)) / 2.0;
    }
}
