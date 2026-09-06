package com.influora.service.portfolio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.config.R2Properties;
import com.influora.domain.entity.CreatorProfile;
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
import com.influora.web.dto.portfolio.PortfolioDtos.PortfolioPageResponse;
import com.influora.web.dto.portfolio.PortfolioDtos.PortfolioPatchRequest;
import com.influora.web.dto.portfolio.PortfolioDtos.PortfolioRateRow;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

/**
 * F-0498 — {@code PortfolioPatchRequest.rateCard} used to be collapsed down to
 * {@code rateCard.get(0).min()/max()}, discarding every row past the first plus every row's
 * label/currency, and {@code buildRateCard} then fabricated two identical generic rows
 * ("Instagram Post"/"Instagram Reel") from that single pair. This class proves: (1) a 2+ row rate
 * card round-trips with its real per-row label/min/max/currency, (2) a min>max row is rejected
 * with the same {@code INVALID_RATE_RANGE} error the onboarding/profile-edit paths already use,
 * and (3) a row count beyond {@code PortfolioService.MAX_RATE_CARD_ROWS} is rejected.
 */
@ExtendWith(MockitoExtension.class)
class PortfolioServiceRateCardTest {

    private static final String USER_ID = "01HCREATORUSER1234567";
    private static final String PROFILE_ID = "01HCREATORPROFILE1234";

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
    }

    private static PortfolioPatchRequest patchWithRateCard(List<PortfolioRateRow> rateCard) {
        return new PortfolioPatchRequest(
                null, null, null, null, null, null, null, null, null, null, rateCard, null, null);
    }

    @Test
    @DisplayName(
            "updateMine: a 2+ row rate card round-trips with its real per-row label/min/max/currency"
                    + " instead of collapsing to row[0] and fabricating two generic rows")
    void updateMine_multiRowRateCard_roundTripsRealRows() {
        List<PortfolioRateRow> rateCard =
                List.of(
                        new PortfolioRateRow(
                                "row_post", "Sponsored Post", new BigDecimal("5000"), new BigDecimal("15000"), "INR"),
                        new PortfolioRateRow(
                                "row_story", "Story Set", new BigDecimal("2000"), new BigDecimal("8000"), "INR"),
                        new PortfolioRateRow(
                                "row_reel", "UGC Reel", new BigDecimal("9000"), new BigDecimal("25000"), "INR"));

        PortfolioPageResponse page = service.updateMine(principal, patchWithRateCard(rateCard));

        assertEquals(3, page.rateCard().size(), "every row the client sent must come back, not just row[0]");
        assertEquals(new PortfolioRateRow("row_post", "Sponsored Post", new BigDecimal("5000"), new BigDecimal("15000"), "INR"), page.rateCard().get(0));
        assertEquals(new PortfolioRateRow("row_story", "Story Set", new BigDecimal("2000"), new BigDecimal("8000"), "INR"), page.rateCard().get(1));
        assertEquals(new PortfolioRateRow("row_reel", "UGC Reel", new BigDecimal("9000"), new BigDecimal("25000"), "INR"), page.rateCard().get(2));

        // The profile-level aggregate (still read by CreatorDiscoveryService's rate filter) must
        // be the true floor/ceiling across all rows, not just row[0]'s pair.
        assertEquals(new BigDecimal("2000"), profile.getRateMin());
        assertEquals(new BigDecimal("25000"), profile.getRateMax());

        verify(creatorProfileRepository).save(profile);
    }

    @Test
    @DisplayName("updateMine: a row with min > max is rejected with INVALID_RATE_RANGE, same as onboarding/profile-edit")
    void updateMine_rowMinExceedsMax_throwsInvalidRateRange() {
        List<PortfolioRateRow> rateCard =
                List.of(
                        new PortfolioRateRow(
                                "row_post", "Sponsored Post", new BigDecimal("5000"), new BigDecimal("15000"), "INR"),
                        new PortfolioRateRow(
                                "row_bad", "Broken Row", new BigDecimal("15000"), new BigDecimal("5000"), "INR"));

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> service.updateMine(principal, patchWithRateCard(rateCard)));

        assertEquals("INVALID_RATE_RANGE", ex.getCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        verify(creatorProfileRepository, never()).save(profile);
    }

    @Test
    @DisplayName("updateMine: a rate card beyond MAX_RATE_CARD_ROWS is rejected, never persisted")
    void updateMine_tooManyRows_throwsRateCardTooLarge() {
        List<PortfolioRateRow> rateCard = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            rateCard.add(
                    new PortfolioRateRow(
                            "row_" + i, "Format " + i, new BigDecimal("1000"), new BigDecimal("2000"), "INR"));
        }

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> service.updateMine(principal, patchWithRateCard(rateCard)));

        assertEquals("RATE_CARD_TOO_LARGE", ex.getCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        verify(creatorProfileRepository, never()).save(profile);
    }

    @Test
    @DisplayName("updateMine: exactly the cap (9 rows, one per DeliverableType) is accepted")
    void updateMine_exactlyAtCap_isAccepted() {
        List<PortfolioRateRow> rateCard = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            rateCard.add(
                    new PortfolioRateRow(
                            "row_" + i, "Format " + i, new BigDecimal("1000"), new BigDecimal("2000"), "INR"));
        }

        PortfolioPageResponse page = service.updateMine(principal, patchWithRateCard(rateCard));

        assertEquals(9, page.rateCard().size());
        verify(creatorProfileRepository).save(profile);
    }

    @Test
    @DisplayName("updateMine: a patch that doesn't touch rateCard (e.g. editing bio) preserves the previously stored rows")
    void updateMine_patchWithoutRateCard_preservesStoredRows() {
        List<PortfolioRateRow> rateCard =
                List.of(
                        new PortfolioRateRow(
                                "row_post", "Sponsored Post", new BigDecimal("5000"), new BigDecimal("15000"), "INR"));
        service.updateMine(principal, patchWithRateCard(rateCard));

        PortfolioPatchRequest bioOnlyPatch =
                new PortfolioPatchRequest(
                        null, null, "Updated bio", null, null, null, null, null, null, null, null, null,
                        null);
        PortfolioPageResponse page = service.updateMine(principal, bioOnlyPatch);

        assertEquals(
                1,
                page.rateCard().size(),
                "a patch that never mentions rateCard must not wipe the previously stored rows");
        assertEquals("row_post", page.rateCard().get(0).id());
    }
}
