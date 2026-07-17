package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.common.ApiException;
import com.influora.config.InfluoraEnvironment;
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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.env.MockEnvironment;

/**
 * Wiring tests for Task 23 (Phase 3c subscription billing) — seat invite/add-member flow. Per
 * MP-1 (wiki/tech/security.md — this is quota/access-control-adjacent, not a direct money path,
 * but the same "wiring test, not just a calculation test" discipline applies): every test below
 * asserts the actual repository call fires (or does NOT fire) on the relevant branch, not merely
 * that a computed value is correct.
 */
@ExtendWith(MockitoExtension.class)
class WorkspaceMemberServiceTest {

    private static final String WORKSPACE_ID = "01HWORKSPACE12345678A";
    private static final String OWNER_USER_ID = "01HUSER000000000000OW";
    private static final String INVITER_USER_ID = "01HUSER000000000000IN";
    private static final String INVITEE_EMAIL = "invitee@example.com";

    @Mock private WorkspaceMemberRepository workspaceMemberRepository;
    @Mock private WorkspaceMemberInviteRepository workspaceMemberInviteRepository;
    @Mock private BrandContextService brandContext;
    @Mock private SubscriptionService subscriptionService;
    @Mock private UserRepository userRepository;
    @Mock private EmailOutboxRepository emailOutboxRepository;
    @Mock private AuthPrincipal principal;
    @Mock private Workspace workspace;
    @Mock private WorkspaceMember actingMember;
    @Mock private Plan plan;
    @Mock private User invitedUser;
    @Mock private Msg91EmailClient msg91EmailClient;
    @Mock private WorkspaceRepository workspaceRepository;

    private WorkspaceMemberService service;

    @BeforeEach
    void setUp() {
        service =
                new WorkspaceMemberService(
                        workspaceMemberRepository,
                        workspaceMemberInviteRepository,
                        brandContext,
                        subscriptionService,
                        userRepository,
                        emailOutboxRepository,
                        // Real JwtService: createRefreshTokenValue()/hashToken() touch no injected
                        // config, so a mock would add nothing but noise (same rationale
                        // CampaignServiceTest uses for a real CampaignValidator).
                        new JwtService(null),
                        // W0-3: InfluoraEnvironment now derives isDev() from the active Spring
                        // profile; a bare MockEnvironment with no active profile is "not dev"
                        // (fail-closed), same as the old default before this test cared either way.
                        new InfluoraEnvironment(new MockEnvironment()),
                        new ObjectMapper(),
                        msg91EmailClient,
                        workspaceRepository);
    }

    // ===================== inviteMember =====================

    @Test
    @DisplayName("Free tier (seatLimit=1) is always at cap from the signup OWNER row -> invite always 402")
    void inviteMember_freeTierAlwaysAtCap() {
        stubOwnerWorkspace();
        when(subscriptionService.getActivePlanForWorkspace(WORKSPACE_ID)).thenReturn(plan);
        when(plan.getSeatLimit()).thenReturn(1);
        when(plan.getName()).thenReturn("Free");
        when(userRepository.findByEmailIgnoreCase(INVITEE_EMAIL)).thenReturn(Optional.empty());
        when(workspaceMemberRepository.countByWorkspaceIdAndActiveTrue(WORKSPACE_ID)).thenReturn(1L);
        when(workspaceMemberInviteRepository.countByWorkspaceIdAndStatusAndExpiresAtAfter(
                        eq(WORKSPACE_ID), eq(MemberInviteStatus.PENDING), any(Instant.class)))
                .thenReturn(0L);
        when(workspaceMemberInviteRepository.findByWorkspaceIdAndEmailIgnoreCaseAndStatus(
                        WORKSPACE_ID, INVITEE_EMAIL, MemberInviteStatus.PENDING))
                .thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.inviteMember(principal, INVITEE_EMAIL, MemberRole.MEMBER));

        assertEquals("UPGRADE_REQUIRED", ex.getCode());
        assertEquals(402, ex.getStatus().value());
        verify(workspaceMemberInviteRepository, never()).save(any(WorkspaceMemberInvite.class));
        verify(emailOutboxRepository, never()).save(any());
    }

    @Test
    @DisplayName("Pro tier under cap -> invite row saved and invite email queued (wiring, not just the count)")
    void inviteMember_underCap_savesInviteAndQueuesEmail() {
        stubOwnerWorkspace();
        when(subscriptionService.getActivePlanForWorkspace(WORKSPACE_ID)).thenReturn(plan);
        when(plan.getSeatLimit()).thenReturn(5);
        when(userRepository.findByEmailIgnoreCase(INVITEE_EMAIL)).thenReturn(Optional.of(invitedUser));
        when(workspaceMemberRepository.findByWorkspaceIdAndUserIdAndActiveTrue(eq(WORKSPACE_ID), anyString()))
                .thenReturn(Optional.empty());
        when(invitedUser.getId()).thenReturn("01HUSER000000000000IV");
        when(workspaceMemberRepository.countByWorkspaceIdAndActiveTrue(WORKSPACE_ID)).thenReturn(1L);
        when(workspaceMemberInviteRepository.countByWorkspaceIdAndStatusAndExpiresAtAfter(
                        eq(WORKSPACE_ID), eq(MemberInviteStatus.PENDING), any(Instant.class)))
                .thenReturn(0L);
        when(workspaceMemberInviteRepository.findByWorkspaceIdAndEmailIgnoreCaseAndStatus(
                        WORKSPACE_ID, INVITEE_EMAIL, MemberInviteStatus.PENDING))
                .thenReturn(Optional.empty());
        when(workspaceMemberInviteRepository.save(any(WorkspaceMemberInvite.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(emailOutboxRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(workspace.getName()).thenReturn("Acme Co");

        WorkspaceMemberInvite invite = service.inviteMember(principal, INVITEE_EMAIL, MemberRole.MANAGER);

        assertNotNull(invite);
        assertEquals(MemberInviteStatus.PENDING, invite.getStatus());
        assertEquals(MemberRole.MANAGER, invite.getRole());
        verify(workspaceMemberInviteRepository, times(1)).save(any(WorkspaceMemberInvite.class));
        verify(emailOutboxRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("At cap (active + pending >= seatLimit) -> 402, no invite row created")
    void inviteMember_atCapWithPendingInvites_rejected() {
        stubOwnerWorkspace();
        when(subscriptionService.getActivePlanForWorkspace(WORKSPACE_ID)).thenReturn(plan);
        when(plan.getSeatLimit()).thenReturn(5);
        when(plan.getName()).thenReturn("Pro");
        when(userRepository.findByEmailIgnoreCase(INVITEE_EMAIL)).thenReturn(Optional.empty());
        when(workspaceMemberRepository.countByWorkspaceIdAndActiveTrue(WORKSPACE_ID)).thenReturn(2L);
        when(workspaceMemberInviteRepository.countByWorkspaceIdAndStatusAndExpiresAtAfter(
                        eq(WORKSPACE_ID), eq(MemberInviteStatus.PENDING), any(Instant.class)))
                .thenReturn(3L); // 2 active + 3 pending == 5 == seatLimit -> at cap
        when(workspaceMemberInviteRepository.findByWorkspaceIdAndEmailIgnoreCaseAndStatus(
                        WORKSPACE_ID, INVITEE_EMAIL, MemberInviteStatus.PENDING))
                .thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.inviteMember(principal, INVITEE_EMAIL, MemberRole.MEMBER));

        assertEquals("UPGRADE_REQUIRED", ex.getCode());
        verify(workspaceMemberInviteRepository, never()).save(any(WorkspaceMemberInvite.class));
    }

    @Test
    @DisplayName("Second invite while one is PENDING -> re-sends the SAME row (rotated token), not a second row")
    void inviteMember_duplicatePending_resendsSameRow() {
        stubOwnerWorkspace();
        when(userRepository.findByEmailIgnoreCase(INVITEE_EMAIL)).thenReturn(Optional.empty());
        WorkspaceMemberInvite existing =
                WorkspaceMemberInvite.builder()
                        .id("01HINVITE000000000001")
                        .workspaceId(WORKSPACE_ID)
                        .email(INVITEE_EMAIL)
                        .role(MemberRole.MEMBER)
                        .inviteTokenHash("old-hash")
                        .invitedByUserId(INVITER_USER_ID)
                        .expiresAt(Instant.now().plus(1, ChronoUnit.DAYS))
                        .build();
        when(workspaceMemberInviteRepository.findByWorkspaceIdAndEmailIgnoreCaseAndStatus(
                        WORKSPACE_ID, INVITEE_EMAIL, MemberInviteStatus.PENDING))
                .thenReturn(Optional.of(existing));
        when(workspaceMemberInviteRepository.save(any(WorkspaceMemberInvite.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        // No email queued for this test: invitedUser resolves to empty (documented "no account
        // yet" branch — queueInviteEmail short-circuits before touching emailOutboxRepository).

        WorkspaceMemberInvite result = service.inviteMember(principal, INVITEE_EMAIL, MemberRole.MEMBER);

        assertEquals("01HINVITE000000000001", result.getId());
        assertTrue(result.getExpiresAt().isAfter(Instant.now().plus(6, ChronoUnit.DAYS)));
        // Dedup path must never re-check/consume the seat limit -- it's the same PENDING row.
        verify(subscriptionService, never()).getActivePlanForWorkspace(anyString());
        verify(workspaceMemberInviteRepository, times(1)).save(any(WorkspaceMemberInvite.class));
    }

    @Test
    @DisplayName("Inviting OWNER role -> 400 INVALID_ROLE, nothing persisted")
    void inviteMember_ownerRoleRejected() {
        stubOwnerWorkspace();

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.inviteMember(principal, INVITEE_EMAIL, MemberRole.OWNER));

        assertEquals("INVALID_ROLE", ex.getCode());
        verify(workspaceMemberInviteRepository, never()).save(any(WorkspaceMemberInvite.class));
    }

    // ===================== acceptInvite =====================

    @Test
    @DisplayName("Happy path: accept creates the WorkspaceMember row and marks the invite ACCEPTED (both writes fire)")
    void acceptInvite_happyPath_createsMemberAndMarksAccepted() {
        String rawToken = new JwtService(null).createRefreshTokenValue();
        String hash = JwtService.hashToken(rawToken);
        WorkspaceMemberInvite invite = pendingInvite(hash, INVITEE_EMAIL, MemberRole.MANAGER);

        when(principal.getEmail()).thenReturn(INVITEE_EMAIL);
        when(principal.getUserId()).thenReturn("01HUSER000000000000AC");
        when(workspaceMemberInviteRepository.findByInviteTokenHash(hash)).thenReturn(Optional.of(invite));
        when(workspaceMemberRepository.findByWorkspaceIdAndUserIdAndActiveTrue(WORKSPACE_ID, "01HUSER000000000000AC"))
                .thenReturn(Optional.empty());
        when(subscriptionService.getActivePlanForWorkspace(WORKSPACE_ID)).thenReturn(plan);
        when(plan.getSeatLimit()).thenReturn(5);
        when(workspaceMemberRepository.countByWorkspaceIdAndActiveTrue(WORKSPACE_ID)).thenReturn(1L);
        when(workspaceMemberRepository.save(any(WorkspaceMember.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        WorkspaceMember member = service.acceptInvite(principal, rawToken);

        assertEquals(MemberRole.MANAGER, member.getRole());
        assertTrue(member.isActive());
        verify(workspaceMemberRepository, times(1)).save(any(WorkspaceMember.class));
        verify(workspaceMemberInviteRepository, times(1)).save(invite);
        assertEquals(MemberInviteStatus.ACCEPTED, invite.getStatus());
    }

    @Test
    @DisplayName("Expired invite -> 410, invite marked EXPIRED, NO WorkspaceMember row is ever created")
    void acceptInvite_expired_marksExpiredAndCreatesNoMember() {
        String rawToken = new JwtService(null).createRefreshTokenValue();
        String hash = JwtService.hashToken(rawToken);
        WorkspaceMemberInvite invite =
                WorkspaceMemberInvite.builder()
                        .id("01HINVITE000000000002")
                        .workspaceId(WORKSPACE_ID)
                        .email(INVITEE_EMAIL)
                        .role(MemberRole.MEMBER)
                        .inviteTokenHash(hash)
                        .invitedByUserId(INVITER_USER_ID)
                        .expiresAt(Instant.now().minus(1, ChronoUnit.DAYS)) // already expired
                        .build();

        when(workspaceMemberInviteRepository.findByInviteTokenHash(hash)).thenReturn(Optional.of(invite));

        ApiException ex = assertThrows(ApiException.class, () -> service.acceptInvite(principal, rawToken));

        assertEquals("INVITE_EXPIRED", ex.getCode());
        assertEquals(410, ex.getStatus().value());
        assertEquals(MemberInviteStatus.EXPIRED, invite.getStatus());
        verify(workspaceMemberInviteRepository, times(1)).save(invite);
        verify(workspaceMemberRepository, never()).save(any(WorkspaceMember.class));
    }

    @Test
    @DisplayName("Seat limit re-checked at accept time -> at cap rejects even though invite itself is valid")
    void acceptInvite_seatLimitReCheckedAtAcceptTime() {
        String rawToken = new JwtService(null).createRefreshTokenValue();
        String hash = JwtService.hashToken(rawToken);
        WorkspaceMemberInvite invite = pendingInvite(hash, INVITEE_EMAIL, MemberRole.MEMBER);

        when(principal.getEmail()).thenReturn(INVITEE_EMAIL);
        when(principal.getUserId()).thenReturn("01HUSER000000000000AC");
        when(workspaceMemberInviteRepository.findByInviteTokenHash(hash)).thenReturn(Optional.of(invite));
        when(workspaceMemberRepository.findByWorkspaceIdAndUserIdAndActiveTrue(WORKSPACE_ID, "01HUSER000000000000AC"))
                .thenReturn(Optional.empty());
        when(subscriptionService.getActivePlanForWorkspace(WORKSPACE_ID)).thenReturn(plan);
        when(plan.getSeatLimit()).thenReturn(1);
        when(plan.getName()).thenReturn("Free");
        // Workspace downgraded to Free (or another accept landed) since the invite was sent --
        // already at the 1-seat cap.
        when(workspaceMemberRepository.countByWorkspaceIdAndActiveTrue(WORKSPACE_ID)).thenReturn(1L);

        ApiException ex = assertThrows(ApiException.class, () -> service.acceptInvite(principal, rawToken));

        assertEquals("UPGRADE_REQUIRED", ex.getCode());
        assertEquals(402, ex.getStatus().value());
        verify(workspaceMemberRepository, never()).save(any(WorkspaceMember.class));
        // Invite must remain PENDING -- not silently consumed by a rejected accept attempt.
        assertEquals(MemberInviteStatus.PENDING, invite.getStatus());
    }

    @Test
    @DisplayName("Caller's own email doesn't match the invite -> 403, no member created")
    void acceptInvite_emailMismatch_rejected() {
        String rawToken = new JwtService(null).createRefreshTokenValue();
        String hash = JwtService.hashToken(rawToken);
        WorkspaceMemberInvite invite = pendingInvite(hash, INVITEE_EMAIL, MemberRole.MEMBER);

        when(principal.getEmail()).thenReturn("someone-else@example.com");
        when(workspaceMemberInviteRepository.findByInviteTokenHash(hash)).thenReturn(Optional.of(invite));

        ApiException ex = assertThrows(ApiException.class, () -> service.acceptInvite(principal, rawToken));

        assertEquals("INVITE_EMAIL_MISMATCH", ex.getCode());
        assertEquals(403, ex.getStatus().value());
        verify(workspaceMemberRepository, never()).save(any(WorkspaceMember.class));
    }

    // ===================== deactivateMember =====================

    @Test
    @DisplayName("Deactivate frees the seat: active flips false and the row is saved")
    void deactivateMember_freesSeat() {
        WorkspaceMember target =
                WorkspaceMember.fromInvite("01HMEMBER00000000001", WORKSPACE_ID, "01HUSERTARGET0000001", MemberRole.MEMBER);
        stubOwnerWorkspace();
        when(workspaceMemberRepository.findByIdAndWorkspaceId("01HMEMBER00000000001", WORKSPACE_ID))
                .thenReturn(Optional.of(target));

        service.deactivateMember(principal, "01HMEMBER00000000001");

        assertTrue(!target.isActive());
        verify(workspaceMemberRepository, times(1)).save(target);
    }

    @Test
    @DisplayName("Cannot deactivate the sole active OWNER -> 409, row never saved")
    void deactivateMember_soleOwnerProtected() {
        WorkspaceMember target = WorkspaceMember.owner("01HMEMBER00000000002", WORKSPACE_ID, "01HUSEROWNER0000002");
        stubOwnerWorkspace();
        when(workspaceMemberRepository.findByIdAndWorkspaceId("01HMEMBER00000000002", WORKSPACE_ID))
                .thenReturn(Optional.of(target));
        when(workspaceMemberRepository.countByWorkspaceIdAndRoleAndActiveTrue(WORKSPACE_ID, MemberRole.OWNER))
                .thenReturn(1L);

        ApiException ex =
                assertThrows(ApiException.class, () -> service.deactivateMember(principal, "01HMEMBER00000000002"));

        assertEquals("CANNOT_REMOVE_SOLE_OWNER", ex.getCode());
        verify(workspaceMemberRepository, never()).save(any(WorkspaceMember.class));
    }

    // ===================== helpers =====================

    private void stubOwnerWorkspace() {
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(actingMember);
        when(workspace.getId()).thenReturn(WORKSPACE_ID);
        // brandContext.requireRole(actingMember, ...) is itself mocked (no-op) in these tests, so
        // it never actually calls actingMember.getRole() -- stubbing that getter here would be an
        // unnecessary stubbing under Mockito's strict stubs.
    }

    private WorkspaceMemberInvite pendingInvite(String tokenHash, String email, MemberRole role) {
        return WorkspaceMemberInvite.builder()
                .id("01HINVITE000000000003")
                .workspaceId(WORKSPACE_ID)
                .email(email)
                .role(role)
                .inviteTokenHash(tokenHash)
                .invitedByUserId(INVITER_USER_ID)
                .expiresAt(Instant.now().plus(6, ChronoUnit.DAYS))
                .build();
    }
}
