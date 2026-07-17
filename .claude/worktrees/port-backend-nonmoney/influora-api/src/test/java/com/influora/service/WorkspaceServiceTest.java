package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.Workspace;
import com.influora.domain.entity.WorkspaceMember;
import com.influora.domain.enums.MemberRole;
import com.influora.domain.enums.UserType;
import com.influora.repository.WorkspaceMemberRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.security.JwtService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Wiring tests for H-16 / L-9 (INFLUORA-PRODUCTION-READINESS-AUDIT-2026-07-14.md) — workspace
 * read/update/switch, none of which existed before this pass.
 */
@ExtendWith(MockitoExtension.class)
class WorkspaceServiceTest {

    private static final String WORKSPACE_ID = "01HWORKSPACE12345678A";
    private static final String OTHER_WORKSPACE_ID = "01HWORKSPACE99999999Z";
    private static final String USER_ID = "01HUSER000000000000OW";

    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private WorkspaceMemberRepository workspaceMemberRepository;
    @Mock private BrandContextService brandContext;
    @Mock private JwtService jwtService;
    @Mock private AuthPrincipal principal;

    private WorkspaceService service;

    @BeforeEach
    void setUp() {
        service = new WorkspaceService(workspaceRepository, workspaceMemberRepository, brandContext, jwtService);
    }

    @Test
    @DisplayName("switchWorkspace: caller not a member of the target workspace -> 403, no token minted")
    void switchWorkspace_notAMember_rejected() {
        when(principal.getUserType()).thenReturn(UserType.BRAND);
        when(principal.getUserId()).thenReturn(USER_ID);
        when(workspaceMemberRepository.findByWorkspaceIdAndUserIdAndActiveTrue(OTHER_WORKSPACE_ID, USER_ID))
                .thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> service.switchWorkspace(principal, OTHER_WORKSPACE_ID));

        assertEquals("FORBIDDEN", ex.getCode());
        verify(jwtService, never()).createAccessToken(any(), any(), any(), any());
    }

    @Test
    @DisplayName("switchWorkspace: active member of target -> mints an access token scoped to it")
    void switchWorkspace_activeMember_mintsToken() {
        when(principal.getUserType()).thenReturn(UserType.BRAND);
        when(principal.getUserId()).thenReturn(USER_ID);
        when(principal.getEmail()).thenReturn("owner@example.com");
        WorkspaceMember membership =
                WorkspaceMember.fromInvite("01HMEMBER00000000009", OTHER_WORKSPACE_ID, USER_ID, MemberRole.ADMIN);
        when(workspaceMemberRepository.findByWorkspaceIdAndUserIdAndActiveTrue(OTHER_WORKSPACE_ID, USER_ID))
                .thenReturn(Optional.of(membership));
        Workspace target = Workspace.newBrand(OTHER_WORKSPACE_ID, "Other Co", "other-co", "Retail", "1-10");
        when(workspaceRepository.findById(OTHER_WORKSPACE_ID)).thenReturn(Optional.of(target));
        when(jwtService.createAccessToken(USER_ID, UserType.BRAND, "owner@example.com", OTHER_WORKSPACE_ID))
                .thenReturn("new-access-token");
        when(jwtService.getAccessExpirySeconds()).thenReturn(900L);

        var issued = service.switchWorkspace(principal, OTHER_WORKSPACE_ID);

        assertEquals("new-access-token", issued.accessToken());
        assertEquals(900L, issued.expiresInSeconds());
    }

    @Test
    @DisplayName("switchWorkspace: target workspace suspended -> 403, no token minted")
    void switchWorkspace_suspendedTarget_rejected() {
        when(principal.getUserType()).thenReturn(UserType.BRAND);
        when(principal.getUserId()).thenReturn(USER_ID);
        WorkspaceMember membership =
                WorkspaceMember.fromInvite("01HMEMBER00000000009", OTHER_WORKSPACE_ID, USER_ID, MemberRole.ADMIN);
        when(workspaceMemberRepository.findByWorkspaceIdAndUserIdAndActiveTrue(OTHER_WORKSPACE_ID, USER_ID))
                .thenReturn(Optional.of(membership));
        Workspace target = Workspace.newBrand(OTHER_WORKSPACE_ID, "Other Co", "other-co", "Retail", "1-10");
        target.suspend("fraud review", "01HADMIN000000000001");
        when(workspaceRepository.findById(OTHER_WORKSPACE_ID)).thenReturn(Optional.of(target));

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> service.switchWorkspace(principal, OTHER_WORKSPACE_ID));

        assertEquals("WORKSPACE_SUSPENDED", ex.getCode());
        verify(jwtService, never()).createAccessToken(any(), any(), any(), any());
    }

    @Test
    @DisplayName("listMyWorkspaces: maps active memberships to workspace summaries")
    void listMyWorkspaces_mapsMemberships() {
        when(principal.getUserType()).thenReturn(UserType.BRAND);
        when(principal.getUserId()).thenReturn(USER_ID);
        WorkspaceMember membership =
                WorkspaceMember.fromInvite("01HMEMBER00000000009", WORKSPACE_ID, USER_ID, MemberRole.OWNER);
        when(workspaceMemberRepository.findByUserIdAndActiveTrueOrderByCreatedAtAsc(USER_ID))
                .thenReturn(List.of(membership));
        Workspace workspace = Workspace.newBrand(WORKSPACE_ID, "Acme Co", "acme-co", "Retail", "1-10");
        when(workspaceRepository.findAllById(List.of(WORKSPACE_ID))).thenReturn(List.of(workspace));

        List<WorkspaceService.WorkspaceSummary> summaries = service.listMyWorkspaces(principal);

        assertEquals(1, summaries.size());
        assertEquals("Acme Co", summaries.get(0).name());
        assertEquals("OWNER", summaries.get(0).role());
    }

    @Test
    @DisplayName("updateMyWorkspace: OWNER/ADMIN required, applies details and saves")
    void updateMyWorkspace_appliesAndSaves() {
        Workspace workspace = Workspace.newBrand(WORKSPACE_ID, "Old Name", "acme-co", "Retail", "1-10");
        WorkspaceMember actingMember =
                WorkspaceMember.owner("01HMEMBEROWNER0000000", WORKSPACE_ID, USER_ID);
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(actingMember);
        when(workspaceRepository.save(workspace)).thenReturn(workspace);

        Workspace result =
                service.updateMyWorkspace(
                        principal, "New Name", "Fashion", "11-50", "https://acme.example", "desc", "https://logo");

        assertEquals("New Name", result.getName());
        assertEquals("Fashion", result.getIndustry());
        assertTrue(result.getUpdatedAt() != null);
    }
}
