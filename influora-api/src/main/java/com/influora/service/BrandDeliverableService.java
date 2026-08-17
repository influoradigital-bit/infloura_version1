package com.influora.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.common.ApiException;
import com.influora.common.JsonLists;
import com.influora.common.TextSanitizer;
import com.influora.config.R2Properties;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.Deliverable;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.CollaborationStatus;
import com.influora.domain.enums.DeliverableStatus;
import com.influora.domain.enums.MeeraInteractionEventType;
import com.influora.integration.storage.R2StorageService;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.DeliverableRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.meera.MeeraInteractionLogService;
import com.influora.web.dto.deliverable.BrandDeliverableDtos.DeliverableDetailResponse;
import com.influora.web.dto.deliverable.BrandDeliverableDtos.DeliverableFileDetail;
import com.influora.web.dto.deliverable.BrandDeliverableDtos.ReviewResponse;
import com.influora.web.dto.deliverable.BrandDeliverableDtos.ReviseRequest;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Brand deliverable review (Week 3 P0 Task #21). Workspace scope always from {@link
 * BrandContextService} — deliverable resolved via collaboration → campaign join-through (same trust
 * boundary as {@link DealService} brand paths). Foreign workspace probes return uniform 404.
 *
 * <p><b>[B3 fix]</b> {@link #approve} previously only flipped {@code Deliverable.status} — nothing
 * downstream ever ran, even though the whole point of approving a deliverable is that it can
 * unlock payment. It now also attempts an escrow release for the deliverable's linked milestone
 * (gated on that milestone's {@code release_condition}, B5) inside this SAME transaction — see
 * {@link EscrowService#tryReleaseOnApproval} for exactly what "attempts" means (no-op, not a
 * failure, when the milestone isn't funded yet or the condition isn't satisfied yet). When a
 * release does happen, {@code EscrowService} marks the milestone RELEASED and publishes {@code
 * PayoutReleasedEvent} itself — this method does not duplicate either of those.
 */
@Service
public class BrandDeliverableService {

    private static final Logger log = LoggerFactory.getLogger(BrandDeliverableService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BrandContextService brandContext;
    private final DeliverableRepository deliverableRepository;
    private final R2StorageService r2StorageService;
    private final R2Properties r2Properties;
    private final EscrowService escrowService;
    private final CollaborationLifecycleService collaborationLifecycleService;
    private final MeeraInteractionLogService meeraInteractionLogService;
    private final CollaborationRepository collaborationRepository;

    public BrandDeliverableService(
            BrandContextService brandContext,
            DeliverableRepository deliverableRepository,
            R2StorageService r2StorageService,
            R2Properties r2Properties,
            EscrowService escrowService,
            CollaborationLifecycleService collaborationLifecycleService,
            MeeraInteractionLogService meeraInteractionLogService,
            CollaborationRepository collaborationRepository) {
        this.brandContext = brandContext;
        this.deliverableRepository = deliverableRepository;
        this.r2StorageService = r2StorageService;
        this.r2Properties = r2Properties;
        this.escrowService = escrowService;
        this.collaborationLifecycleService = collaborationLifecycleService;
        this.meeraInteractionLogService = meeraInteractionLogService;
        this.collaborationRepository = collaborationRepository;
    }

    @Transactional(readOnly = true)
    public DeliverableDetailResponse getDetail(AuthPrincipal principal, String deliverableId) {
        Deliverable deliverable = requireBrandDeliverable(principal, deliverableId);
        return toDetailResponse(deliverable);
    }

    @Transactional
    public ReviewResponse approve(AuthPrincipal principal, String deliverableId) {
        Workspace workspace = brandContext.requireBrandWorkspace(principal);
        Deliverable deliverable = requireBrandDeliverable(workspace, deliverableId);
        if (!canReview(deliverable.getStatus())) {
            throw new ApiException(
                    "INVALID_STATE",
                    "Cannot approve deliverable in current state",
                    HttpStatus.CONFLICT);
        }
        // [CR-22a, Kabir finding #1] Approval fires tryReleaseOnApproval below — real money
        // leaving the clearing wallet — and previously had zero CollaborationStatus awareness.
        // A deliverable can only reach SUBMITTED/RESUBMITTED once a Contract exists
        // (ContractService#generate materializes the rows), which is CONTRACT_PENDING+ — a status
        // DealService#reject can no longer cancel post-CR-22a-narrowing. So this is defense in
        // depth (mirrors the same guard in ContractService/EscrowService), not closing a live
        // race: there is no code path left that can put a deliverable-bearing collaboration into
        // CANCELLED.
        requireNotCancelled(deliverable.getCollaborationId());
        deliverable.applyApprove();
        deliverableRepository.save(deliverable);

        // W2-1 — recompute the collaboration's review-phase status: REVIEW_PENDING while other
        // deliverables are still pending review, COMPLETED once every deliverable is resolved.
        collaborationLifecycleService.onDeliverableReviewed(deliverable.getCollaborationId());

        // [B3] Approval unlocks payment — attempt the escrow release for this deliverable's
        // milestone (B5 release_condition gate applies inside EscrowService). Same transaction as
        // the status flip above: a genuine failure here (not a "not eligible yet" no-op) rolls the
        // whole approval back rather than leaving an approved-but-inconsistent state.
        EscrowService.ReleaseOutcome release;
        try {
            release = escrowService.tryReleaseOnApproval(workspace.getId(), deliverable.getMilestoneId());
        } catch (ApiException e) {
            log.error(
                    "Escrow release attempt failed for deliverable {} (milestone {}) during approval —"
                            + " rolling back the approval too",
                    deliverableId,
                    deliverable.getMilestoneId(),
                    e);
            throw e;
        }

        // F-0223 — the release outcome travels with the approval. Eight conditions inside
        // tryReleaseOnApproval end in "held", and every one of them used to be reported to the
        // brand as a plain success while the creator was paid nothing.
        if (!release.released()) {
            log.warn(
                    "Deliverable {} approved but escrow was NOT released (milestone {}, reason {}) —"
                            + " the creator has not been paid for this approval",
                    deliverableId,
                    deliverable.getMilestoneId(),
                    release.heldReason());
        }
        return new ReviewResponse(
                DeliverableStatus.APPROVED, release.released(), release.heldReason());
    }

    @Transactional
    public ReviewResponse requestRevision(
            AuthPrincipal principal, String deliverableId, ReviseRequest request) {
        Workspace workspace = brandContext.requireBrandWorkspace(principal);
        Deliverable deliverable = requireBrandDeliverable(workspace, deliverableId);
        if (!canReview(deliverable.getStatus())) {
            throw new ApiException(
                    "INVALID_STATE",
                    "Cannot request revision in current state",
                    HttpStatus.CONFLICT);
        }
        String feedback = request != null ? request.feedback() : null;
        if (feedback == null || feedback.isBlank()) {
            throw new ApiException(
                    "INVALID_REQUEST", "feedback is required", HttpStatus.BAD_REQUEST);
        }
        String sanitizedFeedback = TextSanitizer.sanitizePlainText(feedback.trim());
        deliverable.applyRevision(sanitizedFeedback);
        deliverableRepository.save(deliverable);
        // W2-1 — the creator must revise before this collaboration can proceed.
        collaborationLifecycleService.onDeliverableReviewed(deliverable.getCollaborationId());
        // Phase 2 item 2.3 — flywheel logging. sanitizedFeedback is HTML-stripped only
        // (TextSanitizer), NOT PII-redacted; MeeraInteractionLogService.record redacts it via
        // SensitiveTextRedactor as its own first line before persisting. campaignId is left null
        // here — a Deliverable resolves to a campaign only via collaboration -> campaign, an extra
        // join not worth adding for this optional field (the column is nullable by design).
        meeraInteractionLogService.record(
                workspace.getId(),
                null,
                MeeraInteractionEventType.REVISION_REQUESTED,
                null,
                null,
                null,
                sanitizedFeedback,
                null);
        return ReviewResponse.withoutRelease(DeliverableStatus.REVISION_REQUESTED);
    }

    /**
     * B6 — {@code DeliverableStatus.REJECTED} was declared in the enum but unreachable (no writer
     * anywhere in the codebase). Lets the brand terminally reject a submitted deliverable instead of
     * looping {@link #requestRevision} forever.
     */
    @Transactional
    public ReviewResponse reject(AuthPrincipal principal, String deliverableId, ReviseRequest request) {
        Deliverable deliverable = requireBrandDeliverable(principal, deliverableId);
        if (!canReview(deliverable.getStatus())) {
            throw new ApiException(
                    "INVALID_STATE",
                    "Cannot reject deliverable in current state",
                    HttpStatus.CONFLICT);
        }
        String feedback = request != null ? request.feedback() : null;
        if (feedback == null || feedback.isBlank()) {
            throw new ApiException(
                    "INVALID_REQUEST", "feedback is required", HttpStatus.BAD_REQUEST);
        }
        // D-9 (Priya review): approve() has this same defense-in-depth guard (see its comment —
        // structurally unreachable today, but reject() is newly reachable from the UI as of this
        // change and shouldn't be the one path missing it) — matches CollaborationLifecycleService's
        // (indirectly, via onDeliverableReviewed below) treatment of a terminal decision.
        requireNotCancelled(deliverable.getCollaborationId());
        deliverable.applyReject(TextSanitizer.sanitizePlainText(feedback.trim()));
        deliverableRepository.save(deliverable);
        // W2-1 — a rejected deliverable still counts as "resolved" for collaboration completion
        // (B6: rejection is a final brand decision on that slot, not an indefinite block — see
        // CollaborationLifecycleService's DELIVERABLE_RESOLVED javadoc).
        collaborationLifecycleService.onDeliverableReviewed(deliverable.getCollaborationId());
        return ReviewResponse.withoutRelease(DeliverableStatus.REJECTED);
    }

    /**
     * [CR-22a, Kabir finding #1] Shared guard — see {@link #approve}'s javadoc for why this is
     * belt-and-suspenders rather than closing a live race.
     */
    private void requireNotCancelled(String collaborationId) {
        Collaboration collaboration =
                collaborationRepository
                        .findById(collaborationId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "COLLABORATION_NOT_FOUND",
                                                "Collaboration not found",
                                                HttpStatus.NOT_FOUND));
        if (collaboration.getStatus() == CollaborationStatus.CANCELLED) {
            throw new ApiException(
                    "COLLABORATION_CANCELLED",
                    "This deal was cancelled and its deliverables can no longer be approved",
                    HttpStatus.CONFLICT);
        }
    }

    private Deliverable requireBrandDeliverable(AuthPrincipal principal, String deliverableId) {
        Workspace workspace = brandContext.requireBrandWorkspace(principal);
        return requireBrandDeliverable(workspace, deliverableId);
    }

    private Deliverable requireBrandDeliverable(Workspace workspace, String deliverableId) {
        return deliverableRepository
                .findByIdAndWorkspaceId(deliverableId, workspace.getId())
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "DELIVERABLE_NOT_FOUND",
                                        "Deliverable not found",
                                        HttpStatus.NOT_FOUND));
    }

    private static boolean canReview(DeliverableStatus status) {
        return status == DeliverableStatus.SUBMITTED || status == DeliverableStatus.RESUBMITTED;
    }

    private DeliverableDetailResponse toDetailResponse(Deliverable deliverable) {
        List<StoredFile> files = readFilesJson(deliverable.getFilesJson());
        DeliverableStatus status = deliverable.getStatus();
        boolean canReview = canReview(status);
        return new DeliverableDetailResponse(
                deliverable.getId(),
                deliverable.getTitle(),
                status,
                deliverable.getVersionNumber(),
                files.stream().map(this::toFileDetail).toList(),
                deliverable.getCaption(),
                JsonLists.stringListFromJson(deliverable.getHashtagsJson()),
                deliverable.getCreatorNotes(),
                deliverable.getReviewNotes(),
                deliverable.getSubmittedAt(),
                canReview,
                canReview,
                canReview);
    }

    private DeliverableFileDetail toFileDetail(StoredFile file) {
        return new DeliverableFileDetail(
                file.id(),
                file.fileType(),
                file.fileName(),
                resolveDownloadUrl(file.url()),
                resolveDownloadUrl(file.thumbnailUrl()),
                file.fileSize());
    }

    /**
     * M-19-4 pattern — resolve a stored R2 object key (or legacy public URL) to a time-limited
     * presigned GET. Reused from {@link CreatorDeliverableService}.
     */
    private String resolveDownloadUrl(String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        String key = toObjectKey(stored);
        if (key == null || !r2StorageService.isAvailable()) {
            return stored;
        }
        try {
            return r2StorageService.presignGet(key).uploadUrl();
        } catch (RuntimeException e) {
            return stored;
        }
    }

    private String toObjectKey(String stored) {
        if (!stored.startsWith("http://") && !stored.startsWith("https://")) {
            return stored;
        }
        String base = r2Properties.getPublicUrl();
        if (base == null || base.isBlank()) {
            return null;
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (stored.startsWith(base + "/")) {
            return stored.substring(base.length() + 1);
        }
        return null;
    }

    private static List<StoredFile> readFilesJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<StoredFile>>() {});
        } catch (IOException e) {
            return List.of();
        }
    }

    private record StoredFile(
            String id,
            String fileType,
            String fileName,
            String url,
            String thumbnailUrl,
            long fileSize,
            Integer durationSeconds,
            String md5Hash) {}
}
