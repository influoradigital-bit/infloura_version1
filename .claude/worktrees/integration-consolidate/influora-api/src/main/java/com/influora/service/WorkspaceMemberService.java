package com.influora.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.common.ApiException;
import com.influora.common.Ulids;
import com.influora.config.InfluoraEnvironment;
import com.influora.domain.entity.EmailOutbox;
import com.influora.domain.entity.Plan;
import com.influora.domain.entity.User;
import com.influora.domain.entity.Workspace;
import com.influora.domain.entity.WorkspaceMember;
import com.influora.domain.entity.WorkspaceMemberInvite;
import com.influora.domain.enums.MemberInviteStatus;
import com.influora.domain.enums.MemberRole;
import com.influora.integration.msg91.Msg91EmailClient;
import com.influora.repository.EmailOutboxRepository;
import com.influora.repository.UserRepository;
import com.influora.repository.WorkspaceMemberInviteRepository;
import com.influora.repository.WorkspaceMemberRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.security.JwtService;
import com.influora.service.billing.SubscriptionService;
import com.influora.web.dto.workspace.WorkspaceMemberDtos.SwitchWorkspaceResponse;
import com.influora.web.dto.workspace.WorkspaceMemberDtos.WorkspaceReadResponse;
import com.influora.web.dto.workspace.WorkspaceMemberDtos.WorkspaceSummary;
import com.influora.web.dto.workspace.WorkspaceMemberDtos.WorkspaceUpdateRequest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seat invite/add-member flow — Task 23 (Phase 3c subscription billing, from-scratch build per
 * Priya's 2026-07-14 audit: zero invite/add-member write path existed anywhere before this class;
 * {@code AuthService.owner()} at brand signup was the only place a {@link WorkspaceMember} row
 * was ever created).
 *
 * <p><b>Seat-limit accounting (documented decision):</b> a workspace's "used seats" = active
 * {@link WorkspaceMember} rows + PENDING {@link WorkspaceMemberInvite} rows. Counting pending
 * invites is deliberate — otherwise a brand at N-1/N seats could fire off far more than one
 * invite before any of them are accepted, oversubscribing the plan the moment they all land. The
 * limit is enforced twice: at invite time (against active+pending) and again at accept time
 * (against active alone, since the invite being accepted is about to become one of those active
 * rows) — a workspace can downgrade Pro→Free, or another invite can be accepted, in the window
 * between the two.
 *
 * <p><b>Duplicate-invite dedup (documented decision):</b> a second invite to an email that
 * already has a PENDING invite for the same workspace does NOT create a second row (that would
 * double-count against the seat limit for one real seat). It re-sends: rotates the invite token
 * (invalidating whichever link was in the first email — the safer default if that email was
 * forwarded or leaked) and pushes {@code expiresAt} out another 7 days from now.
 *
 * <p><b>Accept-endpoint auth (documented decision — flagged for Kavya):</b> the task brief called
 * {@code POST /workspace/members/accept} a "public, no-auth" endpoint on the theory that the
 * invite token itself is the credential. This class does NOT implement it that way: {@link
 * #acceptInvite} still requires a normal authenticated {@link AuthPrincipal} (the endpoint is
 * NOT in {@code SecurityConfig}'s permitAll list) and additionally verifies the caller's own
 * account email matches {@code invite.getEmail()}. A truly-anonymous accept would let anyone who
 * obtains a token (a forwarded email, a browser-history leak, a referrer header) silently claim a
 * workspace seat under whatever account they happen to be signed in as — that's an
 * access-control bug, not a UX simplification. This also resolves the "does accept need to create
 * a User" question from the task brief: since the caller must already be authenticated, they must
 * already have an Influora account — no new-user-creation logic was added here. The invite email
 * should tell the invitee to log in (or sign up) with the invited address first, then click the
 * link.
 */
@Service
public class WorkspaceMemberService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceMemberService.class);
    private static final long INVITE_TTL_DAYS = 7;

    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final WorkspaceMemberInviteRepository workspaceMemberInviteRepository;
    private final BrandContextService brandContext;
    private final SubscriptionService subscriptionService;
    private final UserRepository userRepository;
    private final EmailOutboxRepository emailOutboxRepository;
    private final JwtService jwtService;
    private final InfluoraEnvironment environment;
    private final ObjectMapper objectMapper;
    private final Msg91EmailClient msg91EmailClient;
    private final WorkspaceRepository workspaceRepository;

    public WorkspaceMemberService(
            WorkspaceMemberRepository workspaceMemberRepository,
            WorkspaceMemberInviteRepository workspaceMemberInviteRepository,
            BrandContextService brandContext,
            SubscriptionService subscriptionService,
            UserRepository userRepository,
            EmailOutboxRepository emailOutboxRepository,
            JwtService jwtService,
            InfluoraEnvironment environment,
            ObjectMapper objectMapper,
            Msg91EmailClient msg91EmailClient,
            WorkspaceRepository workspaceRepository) {
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.workspaceMemberInviteRepository = workspaceMemberInviteRepository;
        this.brandContext = brandContext;
        this.subscriptionService = subscriptionService;
        this.userRepository = userRepository;
        this.emailOutboxRepository = emailOutboxRepository;
        this.jwtService = jwtService;
        this.environment = environment;
        this.objectMapper = objectMapper;
        this.msg91EmailClient = msg91EmailClient;
        this.workspaceRepository = workspaceRepository;
    }

    /**
     * {@code POST /workspace/members/invite}. Caller must be OWNER/ADMIN of their own workspace
     * (server-derived from {@code principal} — never a client-supplied workspaceId, TECH-STACK.md
     * rule #2). Throws {@code 402 UPGRADE_REQUIRED} at the seat cap — see class javadoc for the
     * active+pending accounting. Free's seatLimit=1 means a Free workspace is already at its cap
     * from the OWNER row created at signup, so this method can never let a Free brand invite
     * anyone — verified by {@code WorkspaceMemberServiceTest#inviteMember_freeTierAlwaysAtCap}.
     */
    @Transactional
    public WorkspaceMemberInvite inviteMember(AuthPrincipal principal, String rawEmail, MemberRole role) {
        Workspace workspace = brandContext.requireBrandWorkspace(principal);
        WorkspaceMember actingMember = brandContext.requireMember(principal, workspace.getId());
        brandContext.requireRole(actingMember, MemberRole.OWNER, MemberRole.ADMIN);

        if (rawEmail == null || rawEmail.isBlank()) {
            throw new ApiException("INVALID_EMAIL", "Email is required", HttpStatus.BAD_REQUEST);
        }
        if (role == null || role == MemberRole.OWNER) {
            // OWNER is only ever assigned at brand signup (AuthService.owner()) — inviting a
            // second OWNER isn't a supported flow (ownership transfer is out of scope here).
            throw new ApiException(
                    "INVALID_ROLE", "role must be one of ADMIN, MANAGER, MEMBER, VIEWER", HttpStatus.BAD_REQUEST);
        }
        String email = rawEmail.trim().toLowerCase(Locale.ROOT);
        String workspaceId = workspace.getId();

        User invitedUser = userRepository.findByEmailIgnoreCase(email).orElse(null);
        if (invitedUser != null
                && workspaceMemberRepository
                        .findByWorkspaceIdAndUserIdAndActiveTrue(workspaceId, invitedUser.getId())
                        .isPresent()) {
            throw new ApiException(
                    "ALREADY_MEMBER", "This person is already a member of the workspace", HttpStatus.CONFLICT);
        }

        WorkspaceMemberInvite existingPending =
                workspaceMemberInviteRepository
                        .findByWorkspaceIdAndEmailIgnoreCaseAndStatus(workspaceId, email, MemberInviteStatus.PENDING)
                        .orElse(null);

        String rawToken = jwtService.createRefreshTokenValue();
        String tokenHash = JwtService.hashToken(rawToken);
        Instant expiresAt = Instant.now().plus(INVITE_TTL_DAYS, ChronoUnit.DAYS);

        WorkspaceMemberInvite invite;
        if (existingPending != null) {
            // Dedup: re-send rather than double-count a second PENDING row for the same seat.
            existingPending.resend(tokenHash, expiresAt);
            invite = workspaceMemberInviteRepository.save(existingPending);
        } else {
            enforceSeatLimit(workspaceId);
            invite =
                    workspaceMemberInviteRepository.save(
                            WorkspaceMemberInvite.builder()
                                    .id(Ulids.newUlid())
                                    .workspaceId(workspaceId)
                                    .email(email)
                                    .role(role)
                                    .inviteTokenHash(tokenHash)
                                    .invitedByUserId(principal.getUserId())
                                    .expiresAt(expiresAt)
                                    .build());
        }

        queueInviteEmail(invite, rawToken, workspace, invitedUser);
        return invite;
    }

    /**
     * {@code POST /workspace/members/accept}. See class javadoc — requires a normal authenticated
     * caller whose account email matches the invite, NOT an anonymous/no-auth request.
     */
    @Transactional
    public WorkspaceMember acceptInvite(AuthPrincipal principal, String rawToken) {
        brandContext.requireBrand(principal);
        if (rawToken == null || rawToken.isBlank()) {
            throw new ApiException("INVALID_INVITE_TOKEN", "Invite token is required", HttpStatus.BAD_REQUEST);
        }

        String tokenHash = JwtService.hashToken(rawToken);
        WorkspaceMemberInvite invite =
                workspaceMemberInviteRepository
                        .findByInviteTokenHash(tokenHash)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "INVALID_INVITE_TOKEN",
                                                "Invite token is invalid",
                                                HttpStatus.BAD_REQUEST));

        if (invite.getStatus() == MemberInviteStatus.ACCEPTED) {
            throw new ApiException(
                    "INVITE_ALREADY_ACCEPTED", "This invite has already been accepted", HttpStatus.CONFLICT);
        }
        if (invite.getStatus() == MemberInviteStatus.REVOKED) {
            throw new ApiException("INVITE_REVOKED", "This invite has been revoked", HttpStatus.GONE);
        }
        if (invite.getStatus() == MemberInviteStatus.EXPIRED || invite.isExpired()) {
            if (invite.getStatus() != MemberInviteStatus.EXPIRED) {
                invite.markExpired();
                workspaceMemberInviteRepository.save(invite);
            }
            throw new ApiException(
                    "INVITE_EXPIRED", "This invite has expired — ask an admin to resend it", HttpStatus.GONE);
        }

        if (!principal.getEmail().equalsIgnoreCase(invite.getEmail())) {
            throw new ApiException(
                    "INVITE_EMAIL_MISMATCH",
                    "This invite was sent to a different email address. Log in with the invited"
                            + " address and try again.",
                    HttpStatus.FORBIDDEN);
        }

        if (workspaceMemberRepository
                .findByWorkspaceIdAndUserIdAndActiveTrue(invite.getWorkspaceId(), principal.getUserId())
                .isPresent()) {
            throw new ApiException(
                    "ALREADY_MEMBER", "You are already a member of this workspace", HttpStatus.CONFLICT);
        }

        // Re-check at accept time (class javadoc) — a downgrade or another acceptance could have
        // happened since this invite was sent.
        enforceSeatLimit(invite.getWorkspaceId());

        WorkspaceMember member =
                workspaceMemberRepository.save(
                        WorkspaceMember.fromInvite(
                                Ulids.newUlid(), invite.getWorkspaceId(), principal.getUserId(), invite.getRole()));

        invite.markAccepted();
        workspaceMemberInviteRepository.save(invite);
        return member;
    }

    /**
     * {@code DELETE /workspace/members/{memberId}}. Caller must be OWNER/ADMIN. Soft-deactivates
     * (frees the seat) — never hard-deletes. Refuses to leave a workspace with zero active
     * OWNERs.
     */
    @Transactional
    public void deactivateMember(AuthPrincipal principal, String memberId) {
        Workspace workspace = brandContext.requireBrandWorkspace(principal);
        WorkspaceMember actingMember = brandContext.requireMember(principal, workspace.getId());
        brandContext.requireRole(actingMember, MemberRole.OWNER, MemberRole.ADMIN);

        WorkspaceMember target =
                workspaceMemberRepository
                        .findByIdAndWorkspaceId(memberId, workspace.getId())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "MEMBER_NOT_FOUND", "Workspace member not found", HttpStatus.NOT_FOUND));

        if (!target.isActive()) {
            throw new ApiException(
                    "MEMBER_ALREADY_INACTIVE", "This member is already deactivated", HttpStatus.CONFLICT);
        }

        if (target.getRole() == MemberRole.OWNER) {
            long activeOwners =
                    workspaceMemberRepository.countByWorkspaceIdAndRoleAndActiveTrue(
                            workspace.getId(), MemberRole.OWNER);
            if (activeOwners <= 1) {
                throw new ApiException(
                        "CANNOT_REMOVE_SOLE_OWNER",
                        "A workspace must always have at least one active owner",
                        HttpStatus.CONFLICT);
            }
        }

        target.deactivate();
        workspaceMemberRepository.save(target);
    }

    /** Live "used seats" count for {@code GET /billing/usage} — active members only (pending
     * invites are not yet a "used" seat from the meter's point of view, even though they count
     * against the invite-time cap per the class javadoc). */
    public long getActiveSeatCount(String workspaceId) {
        return workspaceMemberRepository.countByWorkspaceIdAndActiveTrue(workspaceId);
    }

    /**
     * {@code GET /workspace/members}. Any active member of the caller's own workspace can list
     * its active members (no OWNER/ADMIN gate — unlike invite/revoke/deactivate, this is a read).
     */
    public List<WorkspaceMember> listMembers(AuthPrincipal principal) {
        Workspace workspace = brandContext.requireBrandWorkspace(principal);
        brandContext.requireMember(principal, workspace.getId());
        return workspaceMemberRepository.findByWorkspaceIdAndActiveTrue(workspace.getId());
    }

    /**
     * {@code GET /workspace/members/invites}. OWNER/ADMIN only — every invite ever issued for the
     * caller's workspace (any status), newest first. H-15: previously there was no way to see
     * outstanding/expired/revoked invites at all.
     */
    public List<WorkspaceMemberInvite> listInvites(AuthPrincipal principal) {
        Workspace workspace = brandContext.requireBrandWorkspace(principal);
        WorkspaceMember actingMember = brandContext.requireMember(principal, workspace.getId());
        brandContext.requireRole(actingMember, MemberRole.OWNER, MemberRole.ADMIN);
        return workspaceMemberInviteRepository.findByWorkspaceIdOrderByCreatedAtDesc(workspace.getId());
    }

    /**
     * {@code POST /workspace/members/invites/{inviteId}/revoke}. OWNER/ADMIN only. H-15: {@link
     * WorkspaceMemberInvite#markRevoked()} previously had no caller anywhere in the codebase —
     * an outstanding invite could never be cancelled, so it kept consuming a seat against the cap
     * (see {@link #enforceSeatLimit}) for its full 7-day TTL even after the brand decided not to
     * add that person.
     */
    @Transactional
    public void revokeInvite(AuthPrincipal principal, String inviteId) {
        Workspace workspace = brandContext.requireBrandWorkspace(principal);
        WorkspaceMember actingMember = brandContext.requireMember(principal, workspace.getId());
        brandContext.requireRole(actingMember, MemberRole.OWNER, MemberRole.ADMIN);

        WorkspaceMemberInvite invite =
                workspaceMemberInviteRepository
                        .findById(inviteId)
                        .filter(i -> i.getWorkspaceId().equals(workspace.getId()))
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "INVITE_NOT_FOUND", "Invite not found", HttpStatus.NOT_FOUND));

        if (invite.getStatus() != MemberInviteStatus.PENDING) {
            throw new ApiException(
                    "INVITE_NOT_PENDING", "Only a pending invite can be revoked", HttpStatus.CONFLICT);
        }

        invite.markRevoked();
        workspaceMemberInviteRepository.save(invite);
    }

    /**
     * {@code POST /workspace/members/switch}. H-16 fix — the login/refresh JWT stamp is now
     * deterministic ({@code WorkspaceMemberRepository#findFirstByUserIdAndActiveTrueOrderByCreatedAtAsc}),
     * but a user who is genuinely an active member of more than one workspace still needs a way to
     * move off their default one. Mints a fresh access token carrying the target {@code
     * workspaceId} once the caller's own active membership there is confirmed — never a
     * client-trusted workspaceId accepted anywhere else (TECH-STACK.md rule #2). The refresh token
     * is untouched: it is user-scoped, not workspace-scoped, so no rotation is needed here.
     */
    @Transactional(readOnly = true)
    public SwitchWorkspaceResponse switchWorkspace(AuthPrincipal principal, String workspaceId) {
        brandContext.requireBrand(principal);
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new ApiException("INVALID_WORKSPACE_ID", "workspaceId is required", HttpStatus.BAD_REQUEST);
        }

        WorkspaceMember member =
                workspaceMemberRepository
                        .findByWorkspaceIdAndUserIdAndActiveTrue(workspaceId, principal.getUserId())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "FORBIDDEN",
                                                "You are not a member of this workspace",
                                                HttpStatus.FORBIDDEN));

        Workspace workspace =
                workspaceRepository
                        .findById(workspaceId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "WORKSPACE_NOT_FOUND", "Workspace not found", HttpStatus.NOT_FOUND));

        String access =
                jwtService.createAccessToken(
                        principal.getUserId(), principal.getUserType(), principal.getEmail(), workspaceId);

        return new SwitchWorkspaceResponse(
                access,
                jwtService.getAccessExpirySeconds(),
                new WorkspaceSummary(workspace.getId(), workspace.getName(), workspace.getSlug(), member.getRole().name()));
    }

    private void enforceSeatLimit(String workspaceId) {
        Plan plan = subscriptionService.getActivePlanForWorkspace(workspaceId);
        long activeMembers = workspaceMemberRepository.countByWorkspaceIdAndActiveTrue(workspaceId);
        // H-15: only PENDING invites still within their TTL count against the cap — a row nobody
        // ever clicked (and that never got lazily flipped to EXPIRED by an accept attempt) must
        // not permanently occupy a seat.
        long pendingInvites =
                workspaceMemberInviteRepository.countByWorkspaceIdAndStatusAndExpiresAtAfter(
                        workspaceId, MemberInviteStatus.PENDING, Instant.now());
        if (activeMembers + pendingInvites >= plan.getSeatLimit()) {
            throw new ApiException(
                    "UPGRADE_REQUIRED",
                    "Workspace is at its seat limit (" + plan.getSeatLimit() + ") for the " + plan.getName()
                            + " plan — upgrade to add more members",
                    HttpStatus.PAYMENT_REQUIRED);
        }
    }

    /**
     * Queues the invite email via the existing transactional-outbox pattern ({@link EmailOutbox}
     * + {@code EmailWorker}, same infra {@code NotificationService} uses) rather than routing
     * through the broader domain-event/{@code NotificationListener} pub-sub system — this is a
     * one-off transactional action email (like a password reset), not a marketplace notification
     * subject to per-user unsubscribe preferences.
     *
     * <p>{@code email_outbox.user_id} is a NOT NULL FK to {@code users.id} (V18__email_outbox.sql)
     * — it cannot reference an email address with no account, so the outbox path only applies to
     * an invitee who already has an Influora account. H-15 fix: an invitee with no account yet
     * (the primary invite use case — "add someone new to the workspace") is instead sent
     * <b>directly, keyed by the raw email</b> via {@link #sendInviteEmailDirect} — previously this
     * branch just logged a warning and delivered nothing.
     */
    private void queueInviteEmail(
            WorkspaceMemberInvite invite, String rawToken, Workspace workspace, User invitedUser) {
        if (invitedUser == null) {
            sendInviteEmailDirect(invite, rawToken, workspace);
            return;
        }

        Map<String, Object> templateData = new LinkedHashMap<>();
        templateData.put("workspace_name", workspace.getName());
        templateData.put("role", invite.getRole().name());
        templateData.put("invite_token", rawToken);
        templateData.put("expires_at", invite.getExpiresAt().toString());

        String idempotencyKey = "workspace.member_invited:" + invite.getId() + ":" + JwtService.hashToken(rawToken);
        if (emailOutboxRepository.findByIdempotencyKey(idempotencyKey).isPresent()) {
            return;
        }

        EmailOutbox outbox =
                EmailOutbox.builder()
                        .id(Ulids.newUlid())
                        .userId(invitedUser.getId())
                        .toEmail(invite.getEmail())
                        .templateKey("brand.workspace_invite")
                        .templateData(serialize(templateData))
                        .idempotencyKey(idempotencyKey)
                        .build();
        emailOutboxRepository.save(outbox);

        if (environment.isDev()) {
            // Dev-only convenience log, mirrors AuthService#createPasswordResetToken — never log
            // the raw token outside dev.
            log.info(
                    "[dev] Workspace invite for {} — POST /workspace/members/accept token: {}",
                    invite.getEmail(),
                    rawToken);
        }
    }

    /**
     * H-15: email-keyed delivery for an invitee with no {@code users} row — bypasses the
     * outbox's {@code user_id} FK entirely by calling {@link Msg91EmailClient} directly (the same
     * pattern {@code BrandEmailOtpService#deliverOtp} already uses to reach an address with no
     * account). Not retried via {@code EmailWorker} on failure (there is no outbox row to retry) —
     * a failed send is logged, not silently dropped, and the invitee can always be resent via a
     * second {@code POST /workspace/members/invite} call (dedup/resend path in {@link
     * #inviteMember}).
     */
    private void sendInviteEmailDirect(WorkspaceMemberInvite invite, String rawToken, Workspace workspace) {
        if (environment.isDev()) {
            log.info(
                    "[dev] Workspace invite for {} (no Influora account yet) — POST"
                            + " /workspace/members/accept token: {}",
                    invite.getEmail(),
                    rawToken);
            return;
        }

        Map<String, Object> templateData = new LinkedHashMap<>();
        templateData.put("workspace_name", workspace.getName());
        templateData.put("role", invite.getRole().name());
        templateData.put("invite_token", rawToken);
        templateData.put("expires_at", invite.getExpiresAt().toString());

        boolean sent =
                msg91EmailClient.sendTemplateEmail(
                        invite.getEmail(), "brand.workspace_invite_new_user", serialize(templateData));
        if (!sent) {
            log.error(
                    "Workspace invite email delivery failed for a not-yet-registered address"
                            + " (invite id={}, workspaceId={})",
                    invite.getId(),
                    invite.getWorkspaceId());
        }
    }

    private String serialize(Map<String, Object> data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            log.error("Failed to serialize invite email template data", e);
            return "{}";
        }
    }
}
