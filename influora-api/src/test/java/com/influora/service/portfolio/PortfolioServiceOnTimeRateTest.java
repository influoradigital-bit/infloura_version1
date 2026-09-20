package com.influora.service.portfolio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.config.R2Properties;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.Deliverable;
import com.influora.domain.enums.CollaborationStatus;
import com.influora.domain.enums.DeliverableStatus;
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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
 * F-0589 (metric inflated by default) and F-0588 (unbounded N+1) — both in {@code
 * PortfolioService}'s stats path, which produces the PUBLIC, brand-visible "On-Time Delivery"
 * number on a creator's portfolio and the {@code on_time} badge awarded at {@code >= 90}.
 *
 * <p><b>F-0589 ruling under test: the unmeasurable is EXCLUDED from the denominator.</b> The
 * previous behaviour counted a deliverable with no deadline, a deliverable that was never
 * submitted, and a collaboration with zero deliverable rows as ON TIME, over a denominator of
 * {@code completed.size()} — so a creator for whom we held no timeliness evidence at all published
 * a flat 100% and collected the badge. Every scenario in this class is built so that the number an
 * unmeasurable row pads differs from the number the measurable rows alone support, which is what
 * makes these assertions able to fail: a test that only checks a genuinely-late or genuinely
 * on-time deliverable passes identically under both rulings and proves nothing about either.
 *
 * <p>The two N+1 assertions verify REPOSITORY CALL COUNTS, not results — the batched and the
 * per-row implementations return byte-identical stats, so only the call count can tell them apart.
 * {@code never()} verifications use {@code any()} rather than {@code anyString()}: {@code
 * anyString()} is type-matching and does not match {@code null}, which has previously let a
 * {@code never()}-verify pass vacuously in this codebase.
 */
@ExtendWith(MockitoExtension.class)
class PortfolioServiceOnTimeRateTest {

    private static final String USER_ID = "01HCREATORUSER1234567";
    private static final String PROFILE_ID = "01HCREATORPROFILE1234";
    private static final String WORKSPACE_A = "01HWORKSPACEA12345678";
    private static final String WORKSPACE_B = "01HWORKSPACEB12345678";

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

    private final List<Collaboration> completed = new ArrayList<>();
    private final List<Deliverable> deliverables = new ArrayList<>();
    private int nextId = 0;

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
        CreatorProfile profile = CreatorProfile.newForUser(PROFILE_ID, USER_ID, "Riya Sharma");
        profile.applyUsername("riya");
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(profile);
    }

    // ---------------------------------------------------------------- fixtures

    private String seq(String prefix) {
        return prefix + String.format("%05d", ++nextId);
    }

    /** A COMPLETED collaboration on a campaign of the given workspace, with no deliverables yet. */
    private Collaboration completedCollab() {
        Collaboration collaboration =
                Collaboration.invite(seq("01HCOLLAB1234567"), seq("01HCAMPAIGN12345"), USER_ID, "fit", "INR");
        collaboration.transitionTo(CollaborationStatus.COMPLETED);
        completed.add(collaboration);
        return collaboration;
    }

    /**
     * A deliverable whose timeliness IS measurable — it carries a deadline and a submission. {@code
     * applySubmit} stamps {@code submittedAt} with "now", so a deadline in the future is genuinely
     * on time and a deadline in the past is genuinely late. No reflection, no clock injection.
     */
    private void measurableDeliverable(Collaboration collaboration, boolean onTime) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Deliverable deliverable = newDeliverable(collaboration, onTime ? today.plusDays(5) : today.minusDays(5));
        deliverable.applySubmit(null, null, null, DeliverableStatus.SUBMITTED);
        deliverables.add(deliverable);
    }

    /** Has a submission but NO deadline — nothing was committed to, so lateness is undefined. */
    private void deliverableWithNoDeadline(Collaboration collaboration) {
        Deliverable deliverable = newDeliverable(collaboration, null);
        deliverable.applySubmit(null, null, null, DeliverableStatus.SUBMITTED);
        deliverables.add(deliverable);
    }

    /** Has a deadline but was NEVER submitted — there is no submission moment to compare. */
    private void deliverableNeverSubmitted(Collaboration collaboration) {
        deliverables.add(newDeliverable(collaboration, LocalDate.now(ZoneOffset.UTC).minusDays(5)));
    }

    private Deliverable newDeliverable(Collaboration collaboration, LocalDate deadline) {
        return Deliverable.builder()
                .id(seq("01HDELIVERABLE12"))
                .collaborationId(collaboration.getId())
                .creatorProfileId(PROFILE_ID)
                .slotIndex(deliverables.size())
                .title("Slot")
                .deadline(deadline)
                .build();
    }

    /** Wires the fixtures built above into the repositories and reads the portfolio back. */
    private PortfolioPageResponse readPortfolio() {
        when(collaborationRepository.findByCreatorIdAndStatus(USER_ID, CollaborationStatus.COMPLETED))
                .thenReturn(List.copyOf(completed));
        when(deliverableRepository.findByCollaborationIdIn(anyList()))
                .thenReturn(List.copyOf(deliverables));
        return service.getMine(principal);
    }

    // ------------------------------------------------- F-0589: the three unmeasurables

    @Test
    @DisplayName(
            "F-0589: a deliverable with NO DEADLINE is excluded, not credited — the rate is null"
                    + " with a sample size of 0, never 100")
    void deliverableWithoutDeadline_isExcludedNotCountedOnTime() {
        deliverableWithNoDeadline(completedCollab());

        PortfolioPageResponse page = readPortfolio();

        assertNull(
                page.stats().onTimeRate(),
                "a creator whose only deliverable carries no deadline has no measurable timeliness;"
                        + " publishing a number here is a claim we cannot support");
        assertEquals(0, page.stats().onTimeSampleSize(), "nothing was measurable, so nothing was measured");
        assertFalse(
                page.badges().contains("on_time"),
                "the public on_time badge must not be awarded off an unmeasurable record");
    }

    @Test
    @DisplayName(
            "F-0589: a deliverable that was NEVER SUBMITTED is excluded, not credited — the rate is"
                    + " null with a sample size of 0, never 100")
    void deliverableNeverSubmitted_isExcludedNotCountedOnTime() {
        deliverableNeverSubmitted(completedCollab());

        PortfolioPageResponse page = readPortfolio();

        assertNull(
                page.stats().onTimeRate(),
                "a deliverable with no submittedAt has no submission moment to compare to its"
                        + " deadline — it cannot be evidence of being on time");
        assertEquals(0, page.stats().onTimeSampleSize());
        assertFalse(page.badges().contains("on_time"));
    }

    @Test
    @DisplayName(
            "F-0589 (headline): a creator whose completed collaborations have ZERO deliverable rows"
                    + " shows no data — NOT 100% on-time, and no on_time badge")
    void collaborationWithZeroDeliverables_showsNoDataNotAPerfectScore() {
        completedCollab();
        completedCollab();

        PortfolioPageResponse page = readPortfolio();

        assertEquals(2, page.stats().totalCollabs(), "the collabs themselves are still real and still counted");
        assertNull(
                page.stats().onTimeRate(),
                "zero deliverable rows is an absence of evidence, not a perfect delivery record");
        assertEquals(0, page.stats().onTimeSampleSize());
        assertFalse(
                page.badges().contains("on_time"),
                "this is the exact shape that used to hand out a public reliability badge for no"
                        + " delivery history at all");
    }

    // ------------------------------------------- F-0589: real lateness still counts, undiluted

    @Test
    @DisplayName(
            "F-0589: a genuinely LATE delivery is not diluted by unmeasurable collaborations — one"
                    + " late collab plus one empty collab is 0% over a sample of 1, not 50%")
    void genuineLateness_isNotDilutedByUnmeasurableCollaborations() {
        measurableDeliverable(completedCollab(), false);
        completedCollab(); // zero deliverables — used to contribute a free "on time"

        PortfolioPageResponse page = readPortfolio();

        assertEquals(
                0,
                page.stats().onTimeRate(),
                "every deliverable we can actually measure for this creator was late; an empty"
                        + " collaboration must not launder that into a partial pass");
        assertEquals(1, page.stats().onTimeSampleSize(), "exactly one collaboration was measurable");
        assertFalse(page.badges().contains("on_time"));
    }

    @Test
    @DisplayName(
            "F-0589: genuinely on-time and genuinely late deliveries are scored against each other"
                    + " only — one on time, one late, one empty is 50% over a sample of 2, not 67%")
    void onTimeAndLate_areScoredAgainstEachOtherOnly() {
        measurableDeliverable(completedCollab(), true);
        measurableDeliverable(completedCollab(), false);
        completedCollab(); // zero deliverables

        PortfolioPageResponse page = readPortfolio();

        assertEquals(
                50,
                page.stats().onTimeRate(),
                "the rate must be one on-time out of two measurable collaborations; the empty third"
                        + " collaboration is evidence of nothing and must not raise the score");
        assertEquals(2, page.stats().onTimeSampleSize());
    }

    // ------------------------------------------------------------- F-0589: badge boundary

    /**
     * 8 on time, 2 genuinely late, and 10 completed collaborations with no deliverable rows at all.
     * Measurable record: 8/10 = 80%. Padded record (the pre-fix behaviour): (8 + 10) / 20 = exactly
     * 90% — precisely the badge threshold. The rate and the badge are asserted in two separate
     * tests so that neither claim can hide behind the other's failure.
     */
    private void eightyPercentRealRecordPaddedToNinetyByEmptyCollaborations() {
        for (int i = 0; i < 8; i++) {
            measurableDeliverable(completedCollab(), true);
        }
        for (int i = 0; i < 2; i++) {
            measurableDeliverable(completedCollab(), false);
        }
        for (int i = 0; i < 10; i++) {
            completedCollab(); // zero deliverables
        }
    }

    @Test
    @DisplayName(
            "F-0589 badge boundary: 8 on-time / 2 late padded by 10 empty collaborations publishes"
                    + " 80%, not the exactly-90% the padding used to produce")
    void badgeBoundary_paddingByUnmeasurableCollaborationsNoLongerInflatesTheRate() {
        eightyPercentRealRecordPaddedToNinetyByEmptyCollaborations();

        PortfolioPageResponse page = readPortfolio();

        assertEquals(
                80,
                page.stats().onTimeRate(),
                "8 of the 10 measurable collaborations were on time; the 10 empty ones are not"
                        + " deliveries and must not count toward the numerator or the denominator");
        assertEquals(10, page.stats().onTimeSampleSize());
    }

    @Test
    @DisplayName(
            "F-0589 badge boundary: the public on_time badge is NOT awarded to that padded record —"
                    + " the padding alone used to carry it over the 90 threshold")
    void badgeBoundary_paddingByUnmeasurableCollaborationsNoLongerEarnsTheBadge() {
        eightyPercentRealRecordPaddedToNinetyByEmptyCollaborations();

        PortfolioPageResponse page = readPortfolio();

        assertFalse(
                page.badges().contains("on_time"),
                "this creator's real measurable record is 80% — the on_time badge is awarded at 90"
                        + " and must not be reachable by padding with collaborations we cannot"
                        + " measure");
    }

    @Test
    @DisplayName(
            "F-0589 badge boundary: exactly 90% over a real measurable sample still earns the badge"
                    + " — the threshold stays inclusive and is not rounded up to 91 by a"
                    + " never-submitted deliverable")
    void badgeBoundary_exactly90OverRealDeliveriesStillEarnsTheBadge() {
        for (int i = 0; i < 9; i++) {
            measurableDeliverable(completedCollab(), true);
        }
        measurableDeliverable(completedCollab(), false);
        deliverableNeverSubmitted(completedCollab());

        PortfolioPageResponse page = readPortfolio();

        assertEquals(
                90,
                page.stats().onTimeRate(),
                "9 on time out of 10 measurable collaborations is exactly 90; the never-submitted"
                        + " eleventh must not round the published number up to 91");
        assertEquals(10, page.stats().onTimeSampleSize());
        assertTrue(
                page.badges().contains("on_time"),
                "a genuine 90% record must still earn the badge — the fix must not silently raise"
                        + " the bar for creators who really are on time");
    }

    // ------------------------------------------------------------------ F-0588: the N+1s

    @Test
    @DisplayName(
            "F-0588: the deliverables for ALL completed collaborations are read in ONE batched"
                    + " query — not one findByCollaborationIdOrderBySlotIndexAsc per collaboration")
    void deliverables_areReadInOneBatchedQueryNotOnePerCollaboration() {
        measurableDeliverable(completedCollab(), true);
        measurableDeliverable(completedCollab(), true);
        measurableDeliverable(completedCollab(), false);

        readPortfolio();

        verify(deliverableRepository, times(1)).findByCollaborationIdIn(anyList());
        // any(), NOT anyString(): anyString() is type-matching and does not match null, which would
        // make this never()-verify pass vacuously against a call that passed a null id.
        verify(deliverableRepository, never()).findByCollaborationIdOrderBySlotIndexAsc(any());
    }

    @Test
    @DisplayName(
            "F-0588 (unnamed in the ledger): countRepeatBrands resolves every campaign in ONE"
                    + " batched query instead of a findById per completed collaboration, and still"
                    + " counts repeat brands identically")
    void campaigns_areReadInOneBatchedQueryAndRepeatBrandsIsUnchanged() {
        Collaboration first = completedCollab();
        Collaboration second = completedCollab();
        Collaboration third = completedCollab();

        // Two campaigns on workspace A (a repeat brand) and one on workspace B (not).
        List<Campaign> campaigns =
                List.of(
                        Campaign.builder().id(first.getCampaignId()).workspaceId(WORKSPACE_A).title("A1").build(),
                        Campaign.builder().id(second.getCampaignId()).workspaceId(WORKSPACE_A).title("A2").build(),
                        Campaign.builder().id(third.getCampaignId()).workspaceId(WORKSPACE_B).title("B1").build());
        when(campaignRepository.findAllById(anyList())).thenReturn(campaigns);
        for (Campaign campaign : campaigns) {
            when(campaignRepository.findById(campaign.getId())).thenReturn(Optional.of(campaign));
        }

        PortfolioPageResponse page = readPortfolio();

        assertEquals(
                1,
                page.stats().repeatBrands(),
                "batching the campaign lookup must not change the repeat-brand count: workspace A"
                        + " appears on two completed collaborations, workspace B on one");
        verify(campaignRepository, times(1)).findAllById(anyList());
        // The only remaining per-id campaign reads are buildCollabs', which is bounded to 12 rows.
        // Before this fix there were 3 more on top of these, one per collaboration, from
        // countRepeatBrands.
        verify(campaignRepository, times(3)).findById(any());
    }
}
