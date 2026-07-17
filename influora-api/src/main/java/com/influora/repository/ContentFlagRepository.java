package com.influora.repository;

import com.influora.domain.entity.ContentFlag;
import com.influora.domain.enums.ContentFlagStatus;
import com.influora.domain.enums.ContentFlagType;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ContentFlagRepository extends JpaRepository<ContentFlag, String> {

    /**
     * Open (not-yet-actioned) flag count for one piece of content — backs {@code
     * AdminCreatorService}'s {@code CreatorDetail.flaggedContentCount} rollup (contentType=PROFILE,
     * contentId=creatorProfileId). Caller now passes {@code {PENDING, ESCALATED, REVIEWED}} (Priya's
     * ruling, 2026-07-15 — ESCALATED is unresolved work, it must count as an active flag against the
     * creator same as PENDING). The field's javadoc in admin.types.ts ("count of open PENDING +
     * REVIEWED ContentFlag rows") predates ESCALATED and is now incomplete on the frontend side —
     * flagged, not fixed here (that file is Ananya's, out of scope for this backend-only pass).
     */
    long countByContentTypeAndContentIdAndStatusIn(
            ContentFlagType contentType, String contentId, Collection<ContentFlagStatus> statuses);

    /**
     * Flags in a single exact status, oldest-first. No production caller as of Priya's 2026-07-15
     * ruling — {@code ApprovalWorkflowService}'s CONTENT_MODERATION queue slice now uses {@link
     * #findPendingReviewQueue} instead, so its PENDING-only read doesn't silently drop ESCALATED
     * rows the way {@code AdminModerationService}'s did before that fix. Left on the interface
     * (not deleted) because {@code AdminModerationServiceTest} still asserts it is never invoked, as
     * a regression guard against reintroducing a PENDING-only read on that path.
     */
    Page<ContentFlag> findByStatusOrderByCreatedAtAsc(ContentFlagStatus status, Pageable pageable);

    /**
     * The unified "needs a human decision" read for content flags: PENDING flags plus any ESCALATED
     * flags, with ESCALATED sorted first (a senior admin needs to see reassigned-up flags ahead of
     * the ordinary FIFO backlog), then oldest-first within each group.
     *
     * <p>Two callers as of Priya's 2026-07-15 ruling: {@code AdminModerationService#listFlags} (the
     * moderation queue this method was originally added for) and {@code
     * ApprovalWorkflowService#contentModerationQueue} (the unified pending-approvals queue, switched
     * over from the PENDING-only {@link #findByStatusOrderByCreatedAtAsc} because ESCALATED is
     * unresolved work and must not vanish from either queue). Both callers surface each row's real
     * {@code status} rather than assuming PENDING, since a row returned here may be ESCALATED.
     *
     * <p>Pass a {@link Pageable} built WITHOUT a {@link org.springframework.data.domain.Sort} — the
     * ordering is fully specified in the query below; a Pageable-supplied Sort would be appended
     * after it and is unnecessary.
     */
    @Query(
            "SELECT f FROM ContentFlag f WHERE f.status IN ("
                    + "com.influora.domain.enums.ContentFlagStatus.PENDING, "
                    + "com.influora.domain.enums.ContentFlagStatus.ESCALATED) "
                    + "ORDER BY CASE WHEN f.status = com.influora.domain.enums.ContentFlagStatus.ESCALATED "
                    + "THEN 0 ELSE 1 END, f.createdAt ASC")
    Page<ContentFlag> findPendingReviewQueue(Pageable pageable);

    /**
     * M-28 — exact count counterpart to {@link #findByStatusOrderByCreatedAtAsc}'s bounded-page
     * list. No longer called directly by {@code AdminDashboardService.operations}'s {@code
     * reviewBacklog} KPI — see {@link #countByStatusIn}, added so that count also includes ESCALATED
     * (Priya's ruling, 2026-07-15: an escalated flag is still unreviewed work, the backlog must not
     * under-report it). Kept for any exact-single-status count a future caller genuinely needs.
     */
    long countByStatus(ContentFlagStatus status);

    /**
     * Multi-status count counterpart to {@link #findPendingReviewQueue} — backs {@code
     * AdminDashboardService.operations}'s {@code reviewBacklog} KPI with {@code {PENDING,
     * ESCALATED}} (Priya's ruling, 2026-07-15). A true {@code COUNT}, not the bounded 50-row page
     * {@link #findPendingReviewQueue} returns.
     */
    long countByStatusIn(Collection<ContentFlagStatus> statuses);

    /** True when this user already flagged this content (V46 unique gate). */
    boolean existsByContentIdAndFlaggedByUserId(String contentId, String flaggedByUserId);
}
