package com.influora.repository;

import com.influora.domain.entity.WorkspaceMemberInvite;
import com.influora.domain.enums.MemberInviteStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Task 23 (Phase 3c subscription billing) — seat invite/add-member flow. */
public interface WorkspaceMemberInviteRepository extends JpaRepository<WorkspaceMemberInvite, String> {

    /** Token lookup for {@code POST /workspace/members/accept} — {@code inviteTokenHash} is unique. */
    Optional<WorkspaceMemberInvite> findByInviteTokenHash(String inviteTokenHash);

    List<WorkspaceMemberInvite> findByWorkspaceIdAndStatus(String workspaceId, MemberInviteStatus status);

    /** {@code GET /workspace/members/invites} — every invite ever issued for a workspace, newest first (H-15). */
    List<WorkspaceMemberInvite> findByWorkspaceIdOrderByCreatedAtDesc(String workspaceId);

    /**
     * The "used seats" half of the seat-limit gate contributed by outstanding invites — PENDING
     * invites count against {@code plan.getSeatLimit()} alongside active {@code WorkspaceMember}
     * rows (see {@code WorkspaceMemberService#inviteMember} javadoc for why: otherwise a brand
     * could spam invites past the cap and race acceptance).
     *
     * @deprecated H-15: counts PENDING rows whose {@code expiresAt} has already passed but were
     *     never touched by {@link WorkspaceMemberService#acceptInvite} (the only place that
     *     flips a row to EXPIRED) — a workspace with old unaccepted invites reads as
     *     permanently over its seat cap. Use {@link #countByWorkspaceIdAndStatusAndExpiresAtAfter}
     *     instead. Kept only because it's still a valid general-purpose count.
     */
    @Deprecated
    long countByWorkspaceIdAndStatus(String workspaceId, MemberInviteStatus status);

    /**
     * H-15 fix: same seat-accounting purpose as {@link #countByWorkspaceIdAndStatus} but excludes
     * PENDING rows past their {@code expiresAt} — an invite nobody ever clicked (and that never
     * got lazily flipped to EXPIRED by an accept attempt) must not permanently occupy a seat.
     */
    long countByWorkspaceIdAndStatusAndExpiresAtAfter(
            String workspaceId, MemberInviteStatus status, Instant now);

    /** Dedup lookup — is there already a PENDING invite for this workspace+email? */
    Optional<WorkspaceMemberInvite> findByWorkspaceIdAndEmailIgnoreCaseAndStatus(
            String workspaceId, String email, MemberInviteStatus status);
}
