package com.influora.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.influora.domain.entity.Workspace;
import com.influora.domain.entity.WorkspaceMember;
import com.influora.domain.enums.MemberRole;
import com.influora.domain.enums.UserType;
import com.influora.domain.enums.WorkspaceType;
import com.influora.repository.WorkspaceMemberRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.security.JwtService;
import com.influora.service.brand.AnalyzeSiteTriggerService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * W4-2 / A10 / TrendSpark — verifies that {@code WorkspaceService.updateMyWorkspace} triggers
 * {@code AnalyzeSiteTriggerService} when a websiteUrl changes, and does NOT trigger when the URL
 * is blank or unchanged.
 */
@ExtendWith(MockitoExtension.class)
class WorkspaceServiceAnalyzeSiteTest {

    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private WorkspaceMemberRepository workspaceMemberRepository;
    @Mock private BrandContextService brandContext;
    @Mock private JwtService jwtService;
    @Mock private AnalyzeSiteTriggerService analyzeSiteTrigger;

    private WorkspaceService service;

    @BeforeEach
    void setUp() {
        service =
                new WorkspaceService(
                        workspaceRepository,
                        workspaceMemberRepository,
                        brandContext,
                        jwtService,
                        analyzeSiteTrigger);
    }

    @Test
    void updateMyWorkspace_triggersAnalysisWhenWebsiteUrlChanges() {
        AuthPrincipal principal = mockPrincipal();
        Workspace workspace = mockWorkspace("https://old.example.com");
        WorkspaceMember member = mockMember(MemberRole.OWNER);

        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(brandContext.requireMember(principal, workspace.getId())).thenReturn(member);
        when(workspaceRepository.save(any(Workspace.class))).thenReturn(workspace);

        Workspace result =
                service.updateMyWorkspace(
                        principal,
                        "Acme Inc",
                        "Technology",
                        "10-50",
                        "https://new.example.com",
                        null,
                        null);

        assertNotNull(result);
        verify(analyzeSiteTrigger, times(1))
                .trigger(workspace.getId(), "https://new.example.com");
    }

    @Test
    void updateMyWorkspace_triggersAnalysisWhenWebsiteUrlSetForFirstTime() {
        AuthPrincipal principal = mockPrincipal();
        Workspace workspace = mockWorkspace(null);
        WorkspaceMember member = mockMember(MemberRole.ADMIN);

        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(brandContext.requireMember(principal, workspace.getId())).thenReturn(member);
        when(workspaceRepository.save(any(Workspace.class))).thenReturn(workspace);

        service.updateMyWorkspace(
                principal,
                "Acme Inc",
                "Technology",
                "10-50",
                "https://acme.example.com",
                null,
                null);

        verify(analyzeSiteTrigger, times(1))
                .trigger(workspace.getId(), "https://acme.example.com");
    }

    @Test
    void updateMyWorkspace_doesNotTriggerWhenWebsiteUrlUnchanged() {
        AuthPrincipal principal = mockPrincipal();
        Workspace workspace = mockWorkspace("https://acme.example.com");
        WorkspaceMember member = mockMember(MemberRole.OWNER);

        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(brandContext.requireMember(principal, workspace.getId())).thenReturn(member);
        when(workspaceRepository.save(any(Workspace.class))).thenReturn(workspace);

        service.updateMyWorkspace(
                principal,
                "Acme Inc",
                "Technology",
                "10-50",
                "https://acme.example.com",
                null,
                null);

        verify(analyzeSiteTrigger, never()).trigger(anyString(), anyString());
    }

    @Test
    void updateMyWorkspace_doesNotTriggerWhenWebsiteUrlClearedToBlank() {
        AuthPrincipal principal = mockPrincipal();
        Workspace workspace = mockWorkspace("https://old.example.com");
        WorkspaceMember member = mockMember(MemberRole.OWNER);

        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(brandContext.requireMember(principal, workspace.getId())).thenReturn(member);
        when(workspaceRepository.save(any(Workspace.class))).thenReturn(workspace);

        service.updateMyWorkspace(
                principal, "Acme Inc", "Technology", "10-50", "", null, null);

        verify(analyzeSiteTrigger, never()).trigger(anyString(), anyString());
    }

    @Test
    void updateMyWorkspace_doesNotTriggerWhenBothUrlsBlank() {
        AuthPrincipal principal = mockPrincipal();
        Workspace workspace = mockWorkspace("");
        WorkspaceMember member = mockMember(MemberRole.OWNER);

        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(brandContext.requireMember(principal, workspace.getId())).thenReturn(member);
        when(workspaceRepository.save(any(Workspace.class))).thenReturn(workspace);

        service.updateMyWorkspace(
                principal, "Acme Inc", "Technology", "10-50", null, null, null);

        verify(analyzeSiteTrigger, never()).trigger(anyString(), anyString());
    }

    private AuthPrincipal mockPrincipal() {
        return new AuthPrincipal("user-001", "test@example.com", UserType.BRAND, "workspace-001");
    }

    private Workspace mockWorkspace(String currentWebsiteUrl) {
        Workspace workspace = mock(Workspace.class);
        when(workspace.getId()).thenReturn("workspace-001");
        when(workspace.getWebsiteUrl()).thenReturn(currentWebsiteUrl);
        return workspace;
    }

    private WorkspaceMember mockMember(MemberRole role) {
        WorkspaceMember member = mock(WorkspaceMember.class);
        return member;
    }
}
