package com.influora.job;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.domain.entity.Deliverable;
import com.influora.domain.enums.DeliverableStatus;
import com.influora.domain.enums.DisputeStatus;
import com.influora.domain.enums.EscrowStatus;
import com.influora.integration.storage.R2StorageService;
import com.influora.repository.DeliverableRepository;
import com.influora.repository.DisputeRepository;
import com.influora.repository.EscrowHoldRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * DPF-8 — R2 lifecycle job for deliverable media cleanup. Deletes ONLY superseded revisions +
 * abandoned drafts, NOT approved content (see wiki/tech/deliverable-retention-policy.md §3–4).
 *
 * <p>Guards BEFORE any deletion (Kabir red-team, wiki/errors/DPF-8-kabir-redteam.md):
 *
 * <ol>
 *   <li>No active dispute on the collaboration ({@code OPEN}/{@code UNDER_REVIEW})
 *   <li>No unreleased escrow hold on the collaboration — the FULL unreleased set ({@code FUNDED},
 *       {@code FROZEN}, {@code PENDING}), not just {@code FUNDED} (H-DPF8-1: {@code FROZEN} is
 *       precisely the disputed/held-money state and must never be invisible to this guard)
 * </ol>
 *
 * <p>Deletion = R2 object delete + clear {@code filesJson} — but ONLY after the R2 delete has
 * actually succeeded (H-DPF8-2), and via a dedicated {@link Deliverable#applyMediaCleanup()} that
 * never touches {@code status}/{@code versionNumber} (H-DPF8-3).
 *
 * <p><b>Residual risk (M-DPF8-2, documented per Kabir's explicit "or document the mitigation"
 * allowance):</b> the guard check and the external R2 delete cannot share one atomic transaction
 * — R2 is outside the database and its side effect cannot be rolled back. Each scheduled method
 * re-checks {@link #canDelete} freshly (no caching) immediately before the destructive call for
 * that specific deliverable, and repository writes are per-item (not one job-spanning
 * transaction), so the race window is narrowed from "whole job runtime" to "this iteration only."
 * A dispute/escrow change landing in that narrow window is not caught. If job volume/concurrency
 * grows, revisit with a pessimistic lock on the escrow hold rows for the duration of the delete.
 *
 * <p><b>Known no-op (flagged by Kabir as a fail-safe design gap, not a security hole):</b> the
 * lean {@link Deliverable} row's {@code filesJson} only ever holds the CURRENT version's keys —
 * {@link Deliverable#applyUpload} overwrites it on every new upload. So {@code
 * cleanupSupersededRevisions}'s per-old-version key lookup will typically find nothing to delete
 * for {@code v1..vN-1} until a historical-key store exists. Left as-is intentionally: it fails
 * safe (deletes nothing) rather than fails dangerous.
 */
@Component
public class DeliverableCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(DeliverableCleanupJob.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<DisputeStatus> ACTIVE_DISPUTE_STATUSES =
            Set.of(DisputeStatus.OPEN, DisputeStatus.UNDER_REVIEW);

    /**
     * H-DPF8-1 — the FULL unreleased escrow set. {@code FROZEN} = disputed/held money; {@code
     * PENDING} = payment in flight. Both must block deletion exactly like {@code FUNDED}; only
     * {@code RELEASED}/{@code REFUNDED} are terminal enough to allow cleanup.
     */
    private static final Set<EscrowStatus> UNRELEASED_ESCROW_STATUSES =
            Set.of(EscrowStatus.FUNDED, EscrowStatus.FROZEN, EscrowStatus.PENDING);

    @Value("${influora.cleanup.dry-run:true}")
    private boolean dryRun;

    private final DeliverableRepository deliverableRepository;
    private final DisputeRepository disputeRepository;
    private final EscrowHoldRepository escrowHoldRepository;
    private final R2StorageService r2StorageService;

    public DeliverableCleanupJob(
            DeliverableRepository deliverableRepository,
            DisputeRepository disputeRepository,
            EscrowHoldRepository escrowHoldRepository,
            R2StorageService r2StorageService) {
        this.deliverableRepository = deliverableRepository;
        this.disputeRepository = disputeRepository;
        this.escrowHoldRepository = escrowHoldRepository;
        this.r2StorageService = r2StorageService;
    }

    /**
     * Cleanup superseded revisions — runs daily at 2 AM. Targets deliverables where the brand
     * approved a later version (APPROVED/POSTED/VERIFIED) and the old version is 30+ days old.
     *
     * <p>Never mutates the entity — old-version keys live outside the current {@code filesJson}
     * (see class javadoc "known no-op" note), so there is nothing to clear on this deliverable's
     * row even when a matching key is found and deleted.
     */
    @Scheduled(cron = "0 0 2 * * *")
    @SchedulerLock(name = "DeliverableCleanupJob_cleanupSupersededRevisions", lockAtMostFor = "PT20M", lockAtLeastFor = "PT1M")
    public void cleanupSupersededRevisions() {
        if (!r2StorageService.isAvailable()) {
            log.warn("R2 not available; skipping superseded-revision cleanup");
            return;
        }

        Instant cutoff = Instant.now().minus(30, ChronoUnit.DAYS);
        Set<DeliverableStatus> approvedStatuses =
                Set.of(DeliverableStatus.APPROVED, DeliverableStatus.POSTED, DeliverableStatus.VERIFIED);

        List<Deliverable> candidates =
                deliverableRepository.findByStatusInAndApprovedAtBefore(approvedStatuses, cutoff);

        log.info(
                "DPF-8 superseded-revision cleanup: {} candidates (cutoff={}, dry-run={})",
                candidates.size(),
                cutoff,
                dryRun);

        int processed = 0;
        int skipped = 0;
        for (Deliverable d : candidates) {
            try {
                // Only clean if multiple versions exist (superseded revisions v1..vN-1 present)
                if (d.getVersionNumber() <= 1) {
                    continue;
                }

                // M-DPF8-2 — fresh, uncached re-check immediately before the irreversible call.
                if (!canDelete(d)) {
                    log.info(
                            "Skipping deliverable {} — active dispute or unreleased escrow",
                            d.getId());
                    skipped++;
                    continue;
                }

                int currentVersion = d.getVersionNumber();
                for (int v = 1; v < currentVersion; v++) {
                    deleteVersionMedia(d, v, "superseded-revision");
                }
                processed++;
            } catch (Exception e) {
                log.error("Superseded-revision cleanup failed for deliverable {}", d.getId(), e);
            }
        }

        log.info(
                "DPF-8 superseded-revision cleanup done: {} processed, {} skipped (dry-run={})",
                processed,
                skipped,
                dryRun);
    }

    /**
     * Cleanup abandoned drafts — runs daily at 2:30 AM. Targets deliverables that are NOT approved
     * and haven't been touched in 90+ days, with no unreleased escrow.
     */
    @Scheduled(cron = "0 30 2 * * *")
    @SchedulerLock(name = "DeliverableCleanupJob_cleanupAbandonedDrafts", lockAtMostFor = "PT20M", lockAtLeastFor = "PT1M")
    public void cleanupAbandonedDrafts() {
        if (!r2StorageService.isAvailable()) {
            log.warn("R2 not available; skipping abandoned-draft cleanup");
            return;
        }

        Instant cutoff = Instant.now().minus(90, ChronoUnit.DAYS);
        Set<DeliverableStatus> notApprovedStatuses =
                Set.of(
                        DeliverableStatus.PENDING,
                        DeliverableStatus.DRAFT,
                        DeliverableStatus.SUBMITTED,
                        DeliverableStatus.REVISION_REQUESTED,
                        DeliverableStatus.RESUBMITTED,
                        DeliverableStatus.REJECTED);

        List<Deliverable> candidates =
                deliverableRepository.findByStatusInAndUpdatedAtBefore(notApprovedStatuses, cutoff);

        log.info(
                "DPF-8 abandoned-draft cleanup: {} candidates (cutoff={}, dry-run={})",
                candidates.size(),
                cutoff,
                dryRun);

        int cleaned = 0;
        int skipped = 0;
        int failed = 0;
        for (Deliverable d : candidates) {
            try {
                // M-DPF8-2 — fresh, uncached re-check immediately before the irreversible call.
                if (!canDelete(d)) {
                    log.info(
                            "Skipping deliverable {} — active dispute or unreleased escrow",
                            d.getId());
                    skipped++;
                    continue;
                }

                // The lean row's filesJson only ever holds the current version's keys, so delete
                // everything referenced there rather than looping a version range that (per the
                // class javadoc "known no-op" note) would mostly miss.
                DeletionResult result = deleteAllMedia(d, "abandoned-draft");

                if (dryRun) {
                    if (result.anyFound()) {
                        cleaned++;
                    }
                    continue;
                }

                if (!result.anyFound()) {
                    continue;
                }

                if (result.allSucceeded()) {
                    // H-DPF8-2 — only clear filesJson once the R2 objects are actually gone.
                    // H-DPF8-3 — dedicated cleanup mutator; never touches status/versionNumber.
                    d.applyMediaCleanup();
                    deliverableRepository.save(d);
                    cleaned++;
                } else {
                    failed++;
                    log.error(
                            "Partial/failed R2 delete for deliverable {} — filesJson NOT cleared,"
                                    + " will retry next run",
                            d.getId());
                }
            } catch (Exception e) {
                failed++;
                log.error("Abandoned-draft cleanup failed for deliverable {}", d.getId(), e);
            }
        }

        log.info(
                "DPF-8 abandoned-draft cleanup done: {} cleaned, {} skipped, {} failed (dry-run={})",
                cleaned,
                skipped,
                failed,
                dryRun);
    }

    /**
     * Guard: can we safely delete this deliverable's media? Checks for active dispute + the FULL
     * unreleased escrow set (H-DPF8-1). Always a fresh DB read — never cached — so re-invoking
     * this immediately before a destructive call is a meaningful TOCTOU mitigation (M-DPF8-2).
     */
    private boolean canDelete(Deliverable deliverable) {
        String collaborationId = deliverable.getCollaborationId();

        // Fail CLOSED on an unknown collaboration. Without an id neither check below can run, and
        // "we could not establish whether money is held against this" must never be treated as
        // "no money is held against this" — that conflation is the whole bug this method had.
        if (collaborationId == null || collaborationId.isBlank()) {
            log.warn(
                    "Refusing to delete media for deliverable {} — no collaborationId, so escrow"
                            + " and dispute state cannot be established",
                    deliverable.getId());
            return false;
        }

        if (disputeRepository.existsByCollaborationIdAndStatusIn(
                collaborationId, ACTIVE_DISPUTE_STATUSES)) {
            return false;
        }

        // [SEC: CR-35 follow-on, renamed CR-50] Milestone-aware, and that is the entire fix. This
        // used to call `existsByCollaborationIdAndStatusIn`, which matches ONLY the direct
        // `collaboration_id` column — NULL on every hold the ordinary brand escrow flow creates,
        // since only `ConfirmLaunchExecutor` ever bound it. So the guard returned "no unreleased
        // escrow" for precisely the escrow-backed collaborations it exists to protect, and this job
        // deleted creator deliverable media out from under funded, frozen and in-flight holds.
        //
        // It was not theoretical: `influora.cleanup.dry-run` defaults to true, but
        // `application-prod.yml` sets it to FALSE and `docker-compose.hostinger.yml` runs
        // SPRING_PROFILES_ACTIVE=prod — so on any production deploy this ran for real, nightly.
        //
        // The CR-35 backfill populates the column and CR-35's Fix 2 binds it at creation, but
        // neither is sufficient on its own: campaign-level funding has no milestone to bind from
        // and stays NULL permanently by design. The guard has to be correct without the column,
        // which is what the milestone-linkage union gives it. CR-50 deleted the unsafe direct-column
        // methods (`existsByCollaborationIdAndStatus[In]`) from EscrowHoldRepository entirely and
        // renamed this one to `hasEscrowForCollaboration` — it is now the ONLY collaboration-scoped
        // escrow-existence query on the repository, so a future caller cannot regress to the wrong
        // one by name; see that method's javadoc for the full history.
        return !escrowHoldRepository.hasEscrowForCollaboration(
                collaborationId, UNRELEASED_ESCROW_STATUSES);
    }

    /** Delete every key referenced anywhere in the deliverable's current {@code filesJson}. */
    private DeletionResult deleteAllMedia(Deliverable deliverable, String reason) {
        return deleteKeys(deliverable, extractKeys(deliverable.getFilesJson(), null), reason);
    }

    /** Delete only keys belonging to a specific version (path contains {@code /v{version}/}). */
    private DeletionResult deleteVersionMedia(Deliverable deliverable, int version, String reason) {
        return deleteKeys(deliverable, extractKeys(deliverable.getFilesJson(), version), reason);
    }

    /**
     * H-DPF8-2 — reads the REAL key fields ({@code url}/{@code thumbnailUrl}, which the upload
     * path persists as R2 object keys — see {@code CreatorDeliverableService.StoredFile} — never
     * {@code objectKey}, a field that does not exist on the serialized record).
     */
    private Set<String> extractKeys(String filesJson, Integer versionFilter) {
        if (filesJson == null || filesJson.isBlank()) {
            return Set.of();
        }
        List<Map<String, Object>> files = parseFilesJson(filesJson);
        Set<String> keys = new LinkedHashSet<>();
        for (Map<String, Object> file : files) {
            addKeyIfMatches(keys, (String) file.get("url"), versionFilter);
            addKeyIfMatches(keys, (String) file.get("thumbnailUrl"), versionFilter);
        }
        return keys;
    }

    private static void addKeyIfMatches(Set<String> keys, String key, Integer versionFilter) {
        if (key == null || key.isBlank()) {
            return;
        }
        if (versionFilter == null || key.contains("/v" + versionFilter + "/")) {
            keys.add(key);
        }
    }

    /**
     * M-DPF8-1 — logs intent BEFORE the destructive call, in both dry-run and live mode, so a
     * wrongful/interrupted delete is always traceable from the log alone.
     */
    private DeletionResult deleteKeys(Deliverable deliverable, Set<String> keys, String reason) {
        if (keys.isEmpty()) {
            return DeletionResult.NONE_FOUND;
        }

        boolean allSucceeded = true;
        for (String key : keys) {
            if (dryRun) {
                log.info(
                        "DRY-RUN: would delete R2 object {} (deliverable={}, reason={})",
                        key,
                        deliverable.getId(),
                        reason);
                continue;
            }

            log.info(
                    "Deleting R2 object {} (deliverable={}, reason={})",
                    key,
                    deliverable.getId(),
                    reason);
            try {
                r2StorageService.deleteObject(key);
                log.info(
                        "Deleted R2 object {} (deliverable={}, reason={})",
                        key,
                        deliverable.getId(),
                        reason);
            } catch (Exception e) {
                allSucceeded = false;
                log.error(
                        "Failed to delete R2 object {} (deliverable={}, reason={})",
                        key,
                        deliverable.getId(),
                        reason,
                        e);
            }
        }
        return new DeletionResult(true, allSucceeded);
    }

    private List<Map<String, Object>> parseFilesJson(String json) {
        try {
            return MAPPER.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.error("Failed to parse filesJson: {}", json, e);
            return Collections.emptyList();
        }
    }

    /** {@code anyFound} = at least one key matched; {@code allSucceeded} = every attempted delete succeeded. */
    private record DeletionResult(boolean anyFound, boolean allSucceeded) {
        static final DeletionResult NONE_FOUND = new DeletionResult(false, true);
    }
}
