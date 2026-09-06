package com.influora.service.portfolio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.influora.config.R2Properties;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.CollaborationStatus;
import com.influora.integration.meta.client.InstagramInsightsClient;
import com.influora.integration.meta.oauth.MetaTokenStorage;
import com.influora.integration.storage.R2StorageService;
import com.influora.repository.AudienceDemographicsRepository;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.CreatorMetricsRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.DeliverableRepository;
import com.influora.repository.MetaOAuthTokenRepository;
import com.influora.repository.PlatformStatRepository;
import com.influora.repository.PortfolioEventRepository;
import com.influora.repository.ReviewRepository;
import com.influora.repository.UserRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.CreatorContextService;
import com.influora.service.CreatorProfileService;
import com.influora.service.ExternalCreatorLinkService;
import com.influora.service.security.AbuseThrottleService;
import com.influora.service.security.NoOpMalwareScanService;
import com.influora.web.dto.portfolio.PortfolioDtos.PortfolioCollab;
import com.influora.web.dto.portfolio.PortfolioDtos.PortfolioPageResponse;
import com.influora.web.dto.portfolio.PortfolioDtos.PortfolioPatchRequest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * F-0674 (privacy-leak-client-side-only) — a creator's "hidden"/"category" collab display-mode
 * choice was enforced ONLY in the browser ({@code creator-portfolio-public.tsx}:
 * {@code page.collabs.filter(c => c.displayMode !== 'hidden')} and
 * {@code c.displayMode === 'category' ? 'Fashion Brand' : c.brandName}); the server's own public
 * endpoint, {@link PortfolioService#getPublic(String)}, returned every collab with its REAL
 * {@code brandName} (and brandId/brandLogoUrl) regardless of display mode, so an unauthenticated
 * {@code curl /portfolio/{username}} exposed brand names a creator had explicitly chosen to hide
 * or anonymise.
 *
 * <p>Mirrors the same server-side visibility pattern {@link
 * PortfolioService#getVisiblePinnedPosts(CreatorProfile)} already uses for section-level
 * visibility, applied per-collab in {@code buildCollabs} instead.
 */
@ExtendWith(MockitoExtension.class)
class PortfolioServicePublicVisibilityTest {

    private static final String USERNAME = "riya";
    private static final String USER_ID = "01HCREATORUSER1234567";
    private static final String PROFILE_ID = "01HCREATORPROFILE1234";

    private static final String COLLAB_VISIBLE = "01HCOLLABVISIBLE0001A";
    private static final String CAMPAIGN_VISIBLE = "01HCAMPAIGNVISIBLE01A";
    private static final String WORKSPACE_VISIBLE = "01HWORKSPACEVISIBLE1A";
    private static final String REAL_BRAND_NAME_VISIBLE = "Nykaa Fashion";

    private static final String COLLAB_HIDDEN = "01HCOLLABHIDDEN00001A";
    private static final String CAMPAIGN_HIDDEN = "01HCAMPAIGNHIDDEN001A";
    private static final String WORKSPACE_HIDDEN = "01HWORKSPACEHIDDEN01A";
    private static final String REAL_BRAND_NAME_HIDDEN = "SecretStartup Confidential";

    private static final String COLLAB_CATEGORY = "01HCOLLABCATEGORY001A";
    private static final String CAMPAIGN_CATEGORY = "01HCAMPAIGNCATEGRY01A";
    private static final String WORKSPACE_CATEGORY = "01HWORKSPACECATEGRY1A";
    private static final String REAL_BRAND_NAME_CATEGORY = "Sugar Cosmetics";

    @Mock private CreatorContextService creatorContext;
    @Mock private CreatorProfileService creatorProfileService;
    @Mock private CreatorProfileRepository creatorProfileRepository;
    @Mock private PlatformStatRepository platformStatRepository;
    @Mock private CollaborationRepository collaborationRepository;
    @Mock private CampaignRepository campaignRepository;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private AudienceDemographicsRepository audienceDemographicsRepository;
    @Mock private ReviewRepository reviewRepository;
    @Mock private R2StorageService r2StorageService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private UserRepository userRepository;
    @Mock private DeliverableRepository deliverableRepository;
    @Mock private PortfolioEventRepository portfolioEventRepository;
    @Mock private MetaOAuthTokenRepository metaOAuthTokenRepository;
    @Mock private MetaTokenStorage metaTokenStorage;
    @Mock private InstagramInsightsClient instagramInsightsClient;
    @Mock private CreatorMetricsRepository creatorMetricsRepository;
    @Mock private ExternalCreatorLinkService externalCreatorLinkService;
    @Mock private AbuseThrottleService abuseThrottleService;

    private PortfolioService service;
    private AuthPrincipal principal;
    private CreatorProfile profile;

    @BeforeEach
    void setUp() {
        service =
                new PortfolioService(
                        creatorContext,
                        creatorProfileService,
                        creatorProfileRepository,
                        platformStatRepository,
                        collaborationRepository,
                        campaignRepository,
                        workspaceRepository,
                        audienceDemographicsRepository,
                        reviewRepository,
                        r2StorageService,
                        new R2Properties(),
                        new NoOpMalwareScanService(),
                        eventPublisher,
                        userRepository,
                        deliverableRepository,
                        portfolioEventRepository,
                        metaOAuthTokenRepository,
                        metaTokenStorage,
                        instagramInsightsClient,
                        creatorMetricsRepository,
                        externalCreatorLinkService,
                        abuseThrottleService);

        principal = new AuthPrincipal(USER_ID, "creator", null, null);
        profile = CreatorProfile.newForUser(PROFILE_ID, USER_ID, "Riya Sharma");
        profile.applyUsername(USERNAME);
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
        // lenient: only the getPublic-path tests actually invoke requireProfileByUsername; the
        // getMine-only test would otherwise fail strict-stubbing for an unused stub.
        lenient().when(creatorProfileService.requireProfileByUsername(USERNAME)).thenReturn(profile);

        List<Collaboration> collaborations =
                List.of(
                        completedCollab(COLLAB_VISIBLE, CAMPAIGN_VISIBLE),
                        completedCollab(COLLAB_HIDDEN, CAMPAIGN_HIDDEN),
                        completedCollab(COLLAB_CATEGORY, CAMPAIGN_CATEGORY));
        when(collaborationRepository.findByCreatorIdAndStatus(USER_ID, CollaborationStatus.COMPLETED))
                .thenReturn(collaborations);

        stubCampaignAndWorkspace(
                CAMPAIGN_VISIBLE, WORKSPACE_VISIBLE, REAL_BRAND_NAME_VISIBLE, "Beauty");
        stubCampaignAndWorkspace(
                CAMPAIGN_HIDDEN, WORKSPACE_HIDDEN, REAL_BRAND_NAME_HIDDEN, "Beauty");
        stubCampaignAndWorkspace(
                CAMPAIGN_CATEGORY, WORKSPACE_CATEGORY, REAL_BRAND_NAME_CATEGORY, "Beauty");

        // Set the display modes a creator actually persists via the portfolio editor: one left at
        // the default ("logo"), one "hidden", one "category". COLLAB_VISIBLE is deliberately left
        // out of this patch so it exercises the DEFAULT_COLLAB_DISPLAY_MODE fallback too.
        service.updateMine(
                principal,
                patchWithCollabs(
                        List.of(
                                collabPatchRow(COLLAB_HIDDEN, "hidden"),
                                collabPatchRow(COLLAB_CATEGORY, "category"))));
    }

    private static Collaboration completedCollab(String collabId, String campaignId) {
        Collaboration c = Collaboration.invite(collabId, campaignId, USER_ID, "Great fit", "INR");
        c.transitionTo(CollaborationStatus.COMPLETED);
        return c;
    }

    private void stubCampaignAndWorkspace(
            String campaignId, String workspaceId, String brandName, String industry) {
        Campaign campaign =
                Campaign.builder().id(campaignId).workspaceId(workspaceId).title("Launch").build();
        when(campaignRepository.findById(campaignId)).thenReturn(Optional.of(campaign));
        Workspace workspace =
                Workspace.newBrand(
                        workspaceId, brandName, workspaceId.toLowerCase(), industry, "201-500");
        when(workspaceRepository.findById(workspaceId)).thenReturn(Optional.of(workspace));
    }

    private static PortfolioCollab collabPatchRow(String id, String displayMode) {
        return new PortfolioCollab(id, null, null, null, null, null, null, null, null, null, displayMode);
    }

    private static PortfolioPatchRequest patchWithCollabs(List<PortfolioCollab> collabs) {
        return new PortfolioPatchRequest(
                null, null, null, null, null, null, null, null, null, null, null, collabs, null);
    }

    @Test
    @DisplayName(
            "F-0674: getPublic omits a \"hidden\" collab from the response entirely — not returned"
                    + " with a flag for the client to filter")
    void getPublic_hiddenCollab_isAbsentFromResponse() {
        PortfolioPageResponse page = service.getPublic(USERNAME);

        assertEquals(2, page.collabs().size(), "hidden collab must not be counted/returned at all");
        assertTrue(page.collabs().stream().noneMatch(c -> COLLAB_HIDDEN.equals(c.id())));

        // The discriminating assertion: the real brand name string is absent from the payload
        // entirely, not just unattached to a "hidden" flag the client would have to honor.
        String payload = page.collabs().toString();
        assertFalse(
                payload.contains(REAL_BRAND_NAME_HIDDEN),
                "the hidden collab's real brand name leaked into the public payload: " + payload);
    }

    @Test
    @DisplayName(
            "F-0674: getPublic replaces a \"category\" collab's real brandName with a"
                    + " server-computed anonymised label, not the real name")
    void getPublic_categoryCollab_realBrandNameReplacedByAnonymisedLabel() {
        PortfolioPageResponse page = service.getPublic(USERNAME);

        PortfolioCollab categoryCollab =
                page.collabs().stream()
                        .filter(c -> COLLAB_CATEGORY.equals(c.id()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("category collab missing from response"));

        assertEquals("Beauty Brand", categoryCollab.brandName());
        assertEquals(null, categoryCollab.brandId(), "brandId is as identifying as the name");
        assertEquals(null, categoryCollab.brandLogoUrl(), "logo would defeat the anonymisation");

        String payload = page.collabs().toString();
        assertFalse(
                payload.contains(REAL_BRAND_NAME_CATEGORY),
                "the category collab's real brand name leaked into the public payload: " + payload);
    }

    @Test
    @DisplayName("F-0674: getPublic still returns the untouched collab under its real brand name")
    void getPublic_visibleCollab_unaffected() {
        PortfolioPageResponse page = service.getPublic(USERNAME);

        PortfolioCollab visible =
                page.collabs().stream()
                        .filter(c -> COLLAB_VISIBLE.equals(c.id()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("visible collab missing from response"));
        assertEquals(REAL_BRAND_NAME_VISIBLE, visible.brandName());
        assertEquals("logo", visible.displayMode());
    }

    @Test
    @DisplayName(
            "F-0674: getMine (authenticated, creator-facing) is UNCHANGED — the creator still sees"
                    + " every real collab name, including hidden/category ones, so they can edit them")
    void getMine_stillReturnsRealNamesForEveryCollab() {
        PortfolioPageResponse mine = service.getMine(principal);

        assertEquals(3, mine.collabs().size(), "the creator's own view must show every collab");

        PortfolioCollab hidden =
                mine.collabs().stream().filter(c -> COLLAB_HIDDEN.equals(c.id())).findFirst().orElseThrow();
        assertEquals(REAL_BRAND_NAME_HIDDEN, hidden.brandName());
        assertEquals("hidden", hidden.displayMode());

        PortfolioCollab category =
                mine.collabs().stream()
                        .filter(c -> COLLAB_CATEGORY.equals(c.id()))
                        .findFirst()
                        .orElseThrow();
        assertEquals(REAL_BRAND_NAME_CATEGORY, category.brandName());
        assertEquals("category", category.displayMode());
    }
}
