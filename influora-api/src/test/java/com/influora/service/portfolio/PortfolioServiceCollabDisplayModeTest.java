package com.influora.service.portfolio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
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
import com.influora.web.dto.portfolio.PortfolioDtos.PortfolioCustomLink;
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
import org.springframework.http.HttpStatus;

/**
 * F-0665/F-0434 — the "Past collabs — what shows on your page" control (Name+logo / Name only /
 * Anonymous / Hide). {@code PortfolioPatchRequest} had no {@code collabs} field at all, so Jackson
 * silently dropped it from every PATCH body, and {@code PortfolioService#buildCollabs} separately
 * hardcoded {@code displayMode} to {@code "logo"} on every read — so the control could set state,
 * mark the form dirty, and "save" successfully, with the choice never actually persisted anywhere.
 *
 * <p>This class proves a genuine ROUND TRIP: PATCH a real collab's display mode, read the portfolio
 * back (a second, independent call — not just an echo of the request), and assert the value that
 * comes back is the one that was set — for the one real completed collaboration the mocks make
 * visible to {@code buildCollabs}, not a value in the request body.
 */
@ExtendWith(MockitoExtension.class)
class PortfolioServiceCollabDisplayModeTest {

    private static final String USER_ID = "01HCREATORUSER1234567";
    private static final String PROFILE_ID = "01HCREATORPROFILE1234";
    private static final String CAMPAIGN_ID = "01HCAMPAIGN123456789";
    private static final String WORKSPACE_ID = "01HWORKSPACE123456789";
    private static final String COLLAB_ID = "01HCOLLAB1234567890AB";

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
        profile.applyUsername("riya");
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);

        // One real completed collaboration — the exact object buildCollabs() turns into a
        // PortfolioCollab with id == COLLAB_ID on every getMine()/updateMine() call.
        Collaboration collaboration =
                Collaboration.invite(COLLAB_ID, CAMPAIGN_ID, USER_ID, "Great fit for your brand", "INR");
        collaboration.transitionTo(CollaborationStatus.COMPLETED);
        when(collaborationRepository.findByCreatorIdAndStatus(USER_ID, CollaborationStatus.COMPLETED))
                .thenReturn(List.of(collaboration));

        Campaign campaign =
                Campaign.builder().id(CAMPAIGN_ID).workspaceId(WORKSPACE_ID).title("Summer Launch").build();
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));

        Workspace workspace = Workspace.newBrand(WORKSPACE_ID, "Nykaa Fashion", "nykaa", "Beauty", "201-500");
        when(workspaceRepository.findById(WORKSPACE_ID)).thenReturn(Optional.of(workspace));
    }

    /** Same minimal shape a client PATCH sends: only id + displayMode carry real meaning. */
    private static PortfolioCollab collabPatchRow(String id, String displayMode) {
        return new PortfolioCollab(id, null, null, null, null, null, null, null, null, null, displayMode);
    }

    private static PortfolioPatchRequest patchWithCollabs(List<PortfolioCollab> collabs) {
        return new PortfolioPatchRequest(
                null, null, null, null, null, null, null, null, null, null, null, collabs, null);
    }

    @Test
    @DisplayName(
            "updateMine then getMine: a PATCHed collab display mode round-trips to a SEPARATE read,"
                    + " not just the value echoed back in the PATCH response")
    void collabDisplayMode_roundTripsAcrossASeparateRead() {
        PortfolioPageResponse patchResponse =
                service.updateMine(
                        principal, patchWithCollabs(List.of(collabPatchRow(COLLAB_ID, "hidden"))));

        assertEquals(1, patchResponse.collabs().size());
        assertEquals("hidden", patchResponse.collabs().get(0).displayMode());

        // The discriminating assertion: a fresh, independent getMine() call — proving the value
        // actually persisted to portfolio_settings_json rather than merely being echoed back from
        // the same request that set it (the exact non-discriminating shape the task warns against).
        PortfolioPageResponse freshRead = service.getMine(principal);

        assertEquals(1, freshRead.collabs().size());
        assertEquals(COLLAB_ID, freshRead.collabs().get(0).id());
        assertEquals(
                "hidden",
                freshRead.collabs().get(0).displayMode(),
                "the persisted display mode must survive a read that is not the PATCH response itself");
    }

    @Test
    @DisplayName("getMine: a collab with no stored preference defaults to \"logo\", not blank/null")
    void collabDisplayMode_defaultsToLogoWhenNeverSet() {
        PortfolioPageResponse page = service.getMine(principal);

        assertEquals(1, page.collabs().size());
        assertEquals("logo", page.collabs().get(0).displayMode());
    }

    @Test
    @DisplayName("updateMine: an unknown collab display mode is rejected, never silently defaulted or persisted")
    void collabDisplayMode_unknownValue_throwsAndDoesNotPersist() {
        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.updateMine(
                                        principal,
                                        patchWithCollabs(
                                                List.of(collabPatchRow(COLLAB_ID, "public_shoutout")))));

        assertEquals("INVALID_COLLAB_DISPLAY_MODE", ex.getCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());

        // Confirms the rejected value never made it into storage: a fresh read still shows the
        // untouched default rather than a half-applied "public_shoutout".
        PortfolioPageResponse afterRejectedPatch = service.getMine(principal);
        assertEquals("logo", afterRejectedPatch.collabs().get(0).displayMode());
    }

    @Test
    @DisplayName(
            "updateMine: a patch that doesn't mention collabs (e.g. editing bio) preserves the"
                    + " previously-set display mode instead of resetting it to the default")
    void collabDisplayMode_patchWithoutCollabs_preservesStoredValue() {
        service.updateMine(principal, patchWithCollabs(List.of(collabPatchRow(COLLAB_ID, "name_only"))));

        PortfolioPatchRequest bioOnlyPatch =
                new PortfolioPatchRequest(
                        null, null, "Updated bio", null, null, null, null, null, null, null, null, null,
                        null);
        PortfolioPageResponse page = service.updateMine(principal, bioOnlyPatch);

        assertEquals(
                "name_only",
                page.collabs().get(0).displayMode(),
                "a patch that never mentions collabs must not wipe the previously stored display mode");
    }

    /**
     * [F-0673, silent-data-loss] The shape NO existing portfolio test had: write ONE field, then
     * read a DIFFERENT one back.
     *
     * <p>{@code writeSettings} merges {@code "rateCard"} and {@code "collabDisplayModes"} into the
     * settings blob as extra top-level keys that are not fields on {@link PortfolioSettings}. With
     * a plain {@code ObjectMapper} ({@code FAIL_ON_UNKNOWN_PROPERTIES} defaults to true) and no
     * {@code @JsonIgnoreProperties(ignoreUnknown = true)} on that class, {@code loadSettings}'s
     * {@code readValue} threw, its catch swallowed the exception, and it returned a DEFAULT
     * instance — silently reverting visibility, custom links and pinned posts on the very next
     * read. All 29 pre-existing portfolio tests passed throughout, because every one of them only
     * re-read the field it had just written.
     *
     * <p>This shipped live in F-0498's already-promoted fix (which introduced the
     * {@code "rateCard"} key), and was found by a fresh-context review of the collab work that
     * added the second key.
     */
    @Test
    @DisplayName(
            "F-0673: writing a collab display mode must not silently wipe an UNRELATED setting"
                    + " (a custom link) that lives in the same JSON blob")
    void writingCollabDisplayModes_doesNotWipeUnrelatedSettings() {
        // A custom link lives INSIDE PortfolioSettings, so it is lost if the blob stops
        // deserialising. Deliberately not asserting on visibility or on the collab mode itself:
        // getVisibility() defaults to a non-null object when the blob is lost, and the collab mode
        // is read by a SEPARATE tree-level read that survives the failure — both would pass either
        // way. An earlier version of this test asserted exactly those two things and was vacuous:
        // removing the @JsonIgnoreProperties annotation left all 30 portfolio tests green.
        PortfolioPatchRequest linkPatch =
                new PortfolioPatchRequest(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of(new PortfolioCustomLink("lnk_1", "My shop", "https://shop.example", null, null)),
                        null,
                        null,
                        null,
                        null);
        service.updateMine(principal, linkPatch);

        // This is the write that puts the unknown "collabDisplayModes" key into the blob.
        service.updateMine(principal, patchWithCollabs(List.of(collabPatchRow(COLLAB_ID, "hidden"))));

        // A separate read. If the blob no longer deserialises, loadSettings swallows the exception
        // and returns a fresh PortfolioSettings — whose customLinks is empty.
        PortfolioPageResponse page = service.getMine(principal);

        assertEquals(
                1,
                page.customLinks().size(),
                "the creator's custom link vanished after an unrelated collab write — the settings"
                        + " blob failed to deserialise and loadSettings silently returned defaults"
                        + " (F-0673)");
        assertEquals("My shop", page.customLinks().get(0).label());
    }
}
