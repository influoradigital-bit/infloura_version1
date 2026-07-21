package com.influora.service.meera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.BrandAiCredit;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.CampaignTemplate;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.CampaignIntentType;
import com.influora.domain.enums.CampaignStatus;
import com.influora.domain.enums.CampaignTemplateCategory;
import com.influora.domain.enums.CampaignTemplateScope;
import com.influora.repository.BrandProfileRepository;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CampaignTemplateRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.web.dto.meera.MeeraContextDtos.ContextResponse;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Platform-AI Phase 1, Wave 1a (Priya A2/A4) — {@link MeeraContextService} orchestrates the
 * repository reads for {@code POST /internal/meera/context} and hands them to {@link
 * BrandContextAssembler}, which is where the actual field allow-list is enforced (see {@link
 * BrandContextAssemblerTest}). This test covers: audience guard (A4 — CREATOR is Phase 3, must be
 * rejected, never silently populated), the SYSTEM+CUSTOM template digest fetch, and the
 * last-N-campaigns funded/creator-count summary.
 */
@ExtendWith(MockitoExtension.class)
class MeeraContextServiceTest {

    private static final String WORKSPACE_ID = "01HWXYZWORKSPACE123456";

    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private BrandProfileRepository brandProfileRepository;
    @Mock private CampaignTemplateRepository templateRepository;
    @Mock private CampaignRepository campaignRepository;
    @Mock private CollaborationRepository collaborationRepository;
    @Mock private AICreditService creditService;
    @Mock private Workspace workspace;

    private MeeraContextService service;

    @BeforeEach
    void setUp() {
        service =
                new MeeraContextService(
                        workspaceRepository,
                        brandProfileRepository,
                        templateRepository,
                        campaignRepository,
                        collaborationRepository,
                        creditService,
                        new BrandContextAssembler());
    }

    @Test
    @DisplayName("audience=CREATOR is guarded, not populated — Phase 3 (Priya A4), rejects before touching any repo")
    void testCreatorAudienceGuarded() {
        ApiException ex =
                assertThrows(ApiException.class, () -> service.assemble(WORKSPACE_ID, "CREATOR"));
        assertEquals("AUDIENCE_NOT_SUPPORTED", ex.getCode());
        verifyNoInteractions(workspaceRepository, brandProfileRepository, templateRepository, campaignRepository);
    }

    @Test
    @DisplayName("unknown audience value is rejected the same way as CREATOR")
    void testUnknownAudienceGuarded() {
        assertThrows(ApiException.class, () -> service.assemble(WORKSPACE_ID, "SOMETHING_ELSE"));
    }

    @Test
    @DisplayName("workspace not found -> 404, never a silent empty context")
    void testWorkspaceNotFound() {
        when(workspaceRepository.findById(WORKSPACE_ID)).thenReturn(Optional.empty());
        ApiException ex = assertThrows(ApiException.class, () -> service.assemble(WORKSPACE_ID, "BRAND"));
        assertEquals("WORKSPACE_NOT_FOUND", ex.getCode());
    }

    @Test
    @DisplayName("template_digest = SYSTEM templates always + this workspace's CUSTOM ones only")
    void testTemplateDigestIncludesSystemAndOwnCustomOnly() {
        when(workspaceRepository.findById(WORKSPACE_ID)).thenReturn(Optional.of(workspace));
        when(workspace.getId()).thenReturn(WORKSPACE_ID);
        when(workspace.getName()).thenReturn("Acme");
        when(brandProfileRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.empty());
        when(campaignRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of());

        CampaignTemplate systemTemplate =
                CampaignTemplate.builder()
                        .id("sys1")
                        .name("Brand Awareness")
                        .category(CampaignTemplateCategory.AWARENESS)
                        .scope(CampaignTemplateScope.SYSTEM)
                        .campaignType(CampaignIntentType.HYPE)
                        .build();
        CampaignTemplate customTemplate =
                CampaignTemplate.builder()
                        .id("custom1")
                        .name("My Playbook")
                        .category(CampaignTemplateCategory.CUSTOM)
                        .scope(CampaignTemplateScope.CUSTOM)
                        .workspaceId(WORKSPACE_ID)
                        .campaignType(CampaignIntentType.DIRECT)
                        .build();
        when(templateRepository.findByScope(CampaignTemplateScope.SYSTEM)).thenReturn(List.of(systemTemplate));
        when(templateRepository.findByScopeAndWorkspaceId(CampaignTemplateScope.CUSTOM, WORKSPACE_ID))
                .thenReturn(List.of(customTemplate));

        when(creditService.getStatus(WORKSPACE_ID))
                .thenReturn(BrandAiCredit.builder().workspaceId(WORKSPACE_ID).creditsRemaining(50).build());

        ContextResponse response = service.assemble(WORKSPACE_ID, "BRAND");

        assertEquals(2, response.templateDigest().size());
        assertTrue(response.templateDigest().stream().anyMatch(t -> t.name().equals("Brand Awareness")));
        assertTrue(response.templateDigest().stream().anyMatch(t -> t.name().equals("My Playbook")));
        // Never another workspace's CUSTOM template — the fetch itself is tenant-scoped by the
        // repository call, asserted here via the exact mock invocation above.
    }

    @Test
    @DisplayName("past_campaign_summary: creator_count = distinct creators via collaborations, funded reflects status")
    void testPastCampaignSummaryCreatorCountAndFunded() {
        when(workspaceRepository.findById(WORKSPACE_ID)).thenReturn(Optional.of(workspace));
        when(workspace.getId()).thenReturn(WORKSPACE_ID);
        when(workspace.getName()).thenReturn("Acme");
        when(brandProfileRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(Optional.empty());
        when(templateRepository.findByScope(CampaignTemplateScope.SYSTEM)).thenReturn(List.of());
        when(templateRepository.findByScopeAndWorkspaceId(CampaignTemplateScope.CUSTOM, WORKSPACE_ID))
                .thenReturn(List.of());

        Campaign fundedCampaign =
                Campaign.builder()
                        .id("camp1")
                        .workspaceId(WORKSPACE_ID)
                        .title("Live campaign")
                        .status(CampaignStatus.ACTIVE)
                        .createdBy("user1")
                        .campaignType(CampaignIntentType.HYPE)
                        .build();
        Campaign draftCampaign =
                Campaign.builder()
                        .id("camp2")
                        .workspaceId(WORKSPACE_ID)
                        .title("Draft campaign")
                        .status(CampaignStatus.DRAFT)
                        .createdBy("user1")
                        .campaignType(CampaignIntentType.DIRECT)
                        .build();
        when(campaignRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of(fundedCampaign, draftCampaign));

        Collaboration c1 = Collaboration.invite("collab1", "camp1", "creator1", null, "INR");
        Collaboration c2 = Collaboration.invite("collab2", "camp1", "creator2", null, "INR");
        // Duplicate creator on the same campaign must not double-count.
        Collaboration c3 = Collaboration.invite("collab3", "camp1", "creator1", null, "INR");
        when(collaborationRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of(c1, c2, c3));

        when(creditService.getStatus(WORKSPACE_ID))
                .thenReturn(BrandAiCredit.builder().workspaceId(WORKSPACE_ID).creditsRemaining(10).build());

        ContextResponse response = service.assemble(WORKSPACE_ID, "BRAND");

        assertEquals(2, response.pastCampaignSummary().size());
        var funded = response.pastCampaignSummary().stream().filter(p -> p.type().equals("HYPE")).findFirst().get();
        assertEquals(2, funded.creatorCount());
        assertEquals(true, funded.funded());
        var draft = response.pastCampaignSummary().stream().filter(p -> p.type().equals("DIRECT")).findFirst().get();
        assertEquals(0, draft.creatorCount());
        assertEquals(false, draft.funded());
    }
}
