package com.influora.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.UserType;
import com.influora.domain.enums.WorkspaceType;
import com.influora.repository.UserRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.brand.AnalyzeSiteTriggerService;
import com.influora.web.dto.onboarding.OnboardingDtos.BrandCompanyRequest;
import com.influora.web.dto.onboarding.OnboardingDtos.WorkspaceIdResponse;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * W4-2 / A10 / TrendSpark — verifies that {@code OnboardingService.saveBrandCompany} triggers
 * {@code AnalyzeSiteTriggerService} when a websiteUrl is provided or changed, and does NOT trigger
 * when the URL is blank or unchanged.
 */
@ExtendWith(MockitoExtension.class)
class OnboardingServiceAnalyzeSiteTest {

    @Mock private UserRepository userRepository;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private BrandContextService brandContext;
    @Mock private WorkspaceSlugService slugService;
    @Mock private AnalyzeSiteTriggerService analyzeSiteTrigger;

    private OnboardingService service;

    @BeforeEach
    void setUp() {
        service =
                new OnboardingService(
                        userRepository,
                        workspaceRepository,
                        brandContext,
                        slugService,
                        analyzeSiteTrigger);
    }

    @Test
    void saveBrandCompany_triggersAnalysisWhenNewWebsiteUrlProvided() {
        AuthPrincipal principal = mockPrincipal();
        Workspace workspace = mockWorkspace(null);
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(workspaceRepository.save(any(Workspace.class))).thenReturn(workspace);

        BrandCompanyRequest req =
                new BrandCompanyRequest(
                        "Acme Inc",
                        "acme",
                        WorkspaceType.BRAND,
                        "Technology",
                        "10-50",
                        "https://acme.example.com",
                        null,
                        null);

        WorkspaceIdResponse response = service.saveBrandCompany(principal, req);

        assertNotNull(response);
        verify(analyzeSiteTrigger, times(1))
                .trigger(workspace.getId(), "https://acme.example.com");
    }

    @Test
    void saveBrandCompany_triggersAnalysisWhenWebsiteUrlChanges() {
        AuthPrincipal principal = mockPrincipal();
        Workspace workspace = mockWorkspace("https://old.example.com");
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(workspaceRepository.save(any(Workspace.class))).thenReturn(workspace);

        BrandCompanyRequest req =
                new BrandCompanyRequest(
                        "Acme Inc",
                        "acme",
                        WorkspaceType.BRAND,
                        "Technology",
                        "10-50",
                        "https://new.example.com",
                        null,
                        null);

        service.saveBrandCompany(principal, req);

        verify(analyzeSiteTrigger, times(1))
                .trigger(workspace.getId(), "https://new.example.com");
    }

    @Test
    void saveBrandCompany_doesNotTriggerWhenWebsiteUrlBlank() {
        AuthPrincipal principal = mockPrincipal();
        Workspace workspace = mockWorkspace(null);
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(workspaceRepository.save(any(Workspace.class))).thenReturn(workspace);

        BrandCompanyRequest req =
                new BrandCompanyRequest(
                        "Acme Inc",
                        "acme",
                        WorkspaceType.BRAND,
                        "Technology",
                        "10-50",
                        "",
                        null,
                        null);

        service.saveBrandCompany(principal, req);

        verify(analyzeSiteTrigger, never()).trigger(anyString(), anyString());
    }

    @Test
    void saveBrandCompany_doesNotTriggerWhenWebsiteUrlUnchanged() {
        AuthPrincipal principal = mockPrincipal();
        Workspace workspace = mockWorkspace("https://acme.example.com");
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(workspaceRepository.save(any(Workspace.class))).thenReturn(workspace);

        BrandCompanyRequest req =
                new BrandCompanyRequest(
                        "Acme Inc",
                        "acme",
                        WorkspaceType.BRAND,
                        "Technology",
                        "10-50",
                        "https://acme.example.com",
                        null,
                        null);

        service.saveBrandCompany(principal, req);

        verify(analyzeSiteTrigger, never()).trigger(anyString(), anyString());
    }

    @Test
    void saveBrandCompany_doesNotTriggerWhenBothUrlsBlank() {
        AuthPrincipal principal = mockPrincipal();
        Workspace workspace = mockWorkspace("");
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(workspaceRepository.save(any(Workspace.class))).thenReturn(workspace);

        BrandCompanyRequest req =
                new BrandCompanyRequest(
                        "Acme Inc",
                        "acme",
                        WorkspaceType.BRAND,
                        "Technology",
                        "10-50",
                        null,
                        null,
                        null);

        service.saveBrandCompany(principal, req);

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
}
