package com.influora.service.portfolio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.config.R2Properties;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.PlatformStat;
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
import com.influora.web.dto.portfolio.PortfolioDtos.PortfolioBrandView;
import com.influora.web.dto.portfolio.PortfolioDtos.PortfolioCustomLink;
import com.influora.web.dto.portfolio.PortfolioDtos.PortfolioPageResponse;
import com.influora.web.dto.portfolio.PortfolioDtos.PortfolioPatchRequest;
import com.influora.web.dto.portfolio.PortfolioDtos.PortfolioPinnedPost;
import com.influora.web.dto.portfolio.PortfolioDtos.PortfolioRateRow;
import com.influora.web.dto.portfolio.PortfolioDtos.PortfolioVisibility;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * F-0972 — the exhaustive counterpart to {@link PortfolioServicePublicVisibilityTest}.
 *
 * <p>That test asserts one field (a collab's brandName) for one flag, which is why four OTHER
 * components of {@link PortfolioVisibility} — {@code badges}, {@code platformStats}, {@code
 * languages} and {@code contactForm} — could sit on the record for months with ZERO readers
 * anywhere in {@code src/main}: the browser hid those sections while the unauthenticated,
 * {@code permitAll} {@code GET /portfolio/{username}} kept serving every handle, follower count,
 * engagement rate, language and audience city the creator had switched off.
 *
 * <p>A fifth field, {@code stats}, was never gated by anything at all.
 *
 * <p>The durable fix is not four more ternaries. This test <b>reflects over {@link
 * PortfolioVisibility}'s record components</b> and requires an observation for every one of them:
 *
 * <ul>
 *   <li>{@link #everyVisibilityFlagIsObservedByThisTest()} fails the moment a tenth flag is added
 *       to the record and nobody registers what it does — so an inert flag cannot reach main.
 *   <li>{@link #everyVisibilityFlagBindsForAnAnonymousViewer()} flips each flag off in turn and
 *       asserts the ANONYMOUS payload actually changes. A flag enforced only in the browser fails
 *       here, because the server response is identical with it on and off.
 *   <li>{@link #noVisibilityFlagBindsForTheOwner()} asserts the mirror image: the creator reading
 *       their own editor still sees everything they have hidden, or they cannot un-hide it.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class PortfolioServiceVisibilityMatrixTest {

    private static final String USERNAME = "riya";
    private static final String USER_ID = "01HCREATORUSER1234567";
    private static final String PROFILE_ID = "01HCREATORPROFILE1234";
    private static final String COLLAB_ID = "01HCOLLABMATRIX00001A";
    private static final String CAMPAIGN_ID = "01HCAMPAIGNMATRIX001A";
    private static final String WORKSPACE_ID = "01HWORKSPACEMATRIX01A";

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

    /**
     * What each visibility flag is supposed to remove from the ANONYMOUS payload. Keyed by the
     * record component's name so {@link #everyVisibilityFlagIsObservedByThisTest()} can prove the
     * map covers the record exhaustively.
     *
     * <p>{@code contactForm} governs {@code contact()} rather than the page payload and is
     * asserted separately by {@link #contactFormFlagBindsOnTheContactEndpoint()}; it is registered
     * here with a null observer so the exhaustiveness check still accounts for it.
     */
    private static final Map<String, Predicate<PortfolioPageResponse>> WITHHELD_WHEN_OFF =
            new LinkedHashMap<>();

    static {
        WITHHELD_WHEN_OFF.put("trustBar", page -> page.stats() == null);
        WITHHELD_WHEN_OFF.put("badges", page -> page.badges().isEmpty());
        WITHHELD_WHEN_OFF.put("platformStats", page -> page.platforms().isEmpty());
        WITHHELD_WHEN_OFF.put("pastCollabs", page -> page.collabs().isEmpty());
        WITHHELD_WHEN_OFF.put("contentPortfolio", page -> page.pinnedPosts().isEmpty());
        WITHHELD_WHEN_OFF.put("customLinks", page -> page.customLinks().isEmpty());
        WITHHELD_WHEN_OFF.put("rateCard", page -> page.rateCard().isEmpty());
        WITHHELD_WHEN_OFF.put("languages", page -> page.languages().isEmpty());
        WITHHELD_WHEN_OFF.put("contactForm", null);
    }

    /** The "off" value for each component: false for a boolean, "hidden" for the rateCard enum. */
    private static Object offValueFor(RecordComponent component) {
        if (component.getType() == boolean.class) {
            return Boolean.FALSE;
        }
        if (component.getName().equals("rateCard")) {
            return "hidden";
        }
        throw new IllegalStateException(
                "PortfolioVisibility."
                        + component.getName()
                        + " has type "
                        + component.getType()
                        + " and this test does not know what 'off' means for it. Teach it here"
                        + " rather than deleting the case -- an unexercised flag is how F-0972"
                        + " shipped.");
    }

    /** All-on baseline: every section present, so withholding one is observable. */
    private static PortfolioVisibility allVisible() {
        return new PortfolioVisibility(true, true, true, true, true, true, "public", true, true);
    }

    private static PortfolioVisibility withFlagOff(String name) {
        RecordComponent[] components = PortfolioVisibility.class.getRecordComponents();
        Object[] args = new Object[components.length];
        PortfolioVisibility base = allVisible();
        for (int i = 0; i < components.length; i++) {
            try {
                args[i] =
                        components[i].getName().equals(name)
                                ? offValueFor(components[i])
                                : components[i].getAccessor().invoke(base);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        try {
            return (PortfolioVisibility)
                    PortfolioVisibility.class.getDeclaredConstructors()[0].newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

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
        lenient().when(creatorProfileService.requireProfileByUsername(USERNAME)).thenReturn(profile);

        // One connected platform -> a non-empty platforms[] AND the "fast_responder" badge, so
        // both the platformStats and badges flags have something to withhold.
        lenient()
                .when(platformStatRepository.findByCreatorProfileId(PROFILE_ID))
                .thenReturn(
                        List.of(
                                PlatformStat.builder()
                                        .id("01HSTATMATRIX000001AB")
                                        .creatorProfileId(PROFILE_ID)
                                        .platform("INSTAGRAM")
                                        .handle("riyacreates")
                                        .followers(128_000L)
                                        .engagementRate(new BigDecimal("4.20"))
                                        .verified(true)
                                        .build()));

        Collaboration collab = Collaboration.invite(COLLAB_ID, CAMPAIGN_ID, USER_ID, "Fit", "INR");
        collab.transitionTo(CollaborationStatus.COMPLETED);
        lenient()
                .when(
                        collaborationRepository.findByCreatorIdAndStatus(
                                USER_ID, CollaborationStatus.COMPLETED))
                .thenReturn(List.of(collab));
        lenient()
                .when(campaignRepository.findById(CAMPAIGN_ID))
                .thenReturn(
                        Optional.of(
                                Campaign.builder()
                                        .id(CAMPAIGN_ID)
                                        .workspaceId(WORKSPACE_ID)
                                        .title("Festive launch")
                                        .build()));
        lenient()
                .when(workspaceRepository.findById(WORKSPACE_ID))
                .thenReturn(
                        Optional.of(
                                Workspace.newBrand(
                                        WORKSPACE_ID, "Nykaa Fashion", "nykaa", "Beauty", "201-500")));

        // Seed every creator-authored section so an all-on baseline is non-empty everywhere.
        service.updateMine(principal, seedPatch(allVisible()));
    }

    private static PortfolioPatchRequest seedPatch(PortfolioVisibility visibility) {
        return new PortfolioPatchRequest(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of("Hindi", "English"),
                List.of(new PortfolioCustomLink("l1", "My shop", "https://example.com", null, null)),
                List.of(
                        new PortfolioPinnedPost(
                                "p1", "INSTAGRAM", "https://example.com/p1", null, "Festive reel", 10L, 2L)),
                List.of(
                        new PortfolioRateRow(
                                "instagram_reel",
                                "Instagram Reel",
                                new BigDecimal("75000"),
                                new BigDecimal("95000"),
                                "INR")),
                null,
                visibility);
    }

    private PortfolioPageResponse publicPage() {
        return service.getPublic(USERNAME);
    }

    private void setVisibility(PortfolioVisibility visibility) {
        service.updateMine(principal, seedPatch(visibility));
    }

    // ------------------------------------------------------------------ tests --

    @Test
    @DisplayName(
            "F-0972: every PortfolioVisibility record component is accounted for by this test --"
                    + " adding a flag without an observation fails here, not in production")
    void everyVisibilityFlagIsObservedByThisTest() {
        List<String> unobserved = new ArrayList<>();
        for (RecordComponent component : PortfolioVisibility.class.getRecordComponents()) {
            if (!WITHHELD_WHEN_OFF.containsKey(component.getName())) {
                unobserved.add(component.getName());
            }
        }
        assertTrue(
                unobserved.isEmpty(),
                "PortfolioVisibility gained "
                        + unobserved
                        + " with no entry in WITHHELD_WHEN_OFF. Register what the flag withholds"
                        + " from the anonymous payload (or, if it governs a different endpoint, a"
                        + " null observer plus its own test). Four flags already shipped inert"
                        + " because nothing forced this.");

        // ...and the map must not outlive the record either.
        List<String> componentNames =
                java.util.Arrays.stream(PortfolioVisibility.class.getRecordComponents())
                        .map(RecordComponent::getName)
                        .toList();
        for (String observed : WITHHELD_WHEN_OFF.keySet()) {
            assertTrue(
                    componentNames.contains(observed),
                    "WITHHELD_WHEN_OFF names '"
                            + observed
                            + "', which is not a PortfolioVisibility component any more.");
        }
    }

    @Test
    @DisplayName(
            "F-0972: flipping any visibility flag off actually changes the ANONYMOUS payload --"
                    + " a browser-only gate cannot pass this")
    void everyVisibilityFlagBindsForAnAnonymousViewer() {
        setVisibility(allVisible());
        PortfolioPageResponse baseline = publicPage();

        // Guard the fixture itself: a baseline that is already empty would let every flag "pass"
        // vacuously, which is the failure mode this whole test exists to prevent.
        assertNotNull(baseline.stats(), "fixture: baseline stats must be present");
        assertFalse(baseline.badges().isEmpty(), "fixture: baseline badges must be non-empty");
        assertFalse(baseline.platforms().isEmpty(), "fixture: baseline platforms must be non-empty");
        assertFalse(baseline.collabs().isEmpty(), "fixture: baseline collabs must be non-empty");
        assertFalse(
                baseline.pinnedPosts().isEmpty(), "fixture: baseline pinnedPosts must be non-empty");
        assertFalse(
                baseline.customLinks().isEmpty(), "fixture: baseline customLinks must be non-empty");
        assertFalse(baseline.rateCard().isEmpty(), "fixture: baseline rateCard must be non-empty");
        assertFalse(baseline.languages().isEmpty(), "fixture: baseline languages must be non-empty");

        for (Map.Entry<String, Predicate<PortfolioPageResponse>> entry :
                WITHHELD_WHEN_OFF.entrySet()) {
            if (entry.getValue() == null) {
                continue; // contactForm — asserted on its own endpoint below.
            }
            setVisibility(withFlagOff(entry.getKey()));
            PortfolioPageResponse withheld = publicPage();
            assertTrue(
                    entry.getValue().test(withheld),
                    "PortfolioVisibility."
                            + entry.getKey()
                            + " = off did NOT remove its section from the unauthenticated"
                            + " GET /portfolio/{username} payload. The browser hiding it is not"
                            + " enforcement: anyone can curl this endpoint (F-0972).");
        }
    }

    @Test
    @DisplayName(
            "F-0972: no visibility flag binds for the OWNER -- the creator must still see what they"
                    + " have hidden, or they can never un-hide it")
    void noVisibilityFlagBindsForTheOwner() {
        for (String flag : WITHHELD_WHEN_OFF.keySet()) {
            setVisibility(withFlagOff(flag));
            PortfolioPageResponse mine = service.getMine(principal);
            assertNotNull(mine.stats(), "OWNER lost stats when " + flag + " was off");
            assertFalse(mine.badges().isEmpty(), "OWNER lost badges when " + flag + " was off");
            assertFalse(mine.platforms().isEmpty(), "OWNER lost platforms when " + flag + " was off");
            assertFalse(mine.collabs().isEmpty(), "OWNER lost collabs when " + flag + " was off");
            assertFalse(
                    mine.pinnedPosts().isEmpty(), "OWNER lost pinnedPosts when " + flag + " was off");
            assertFalse(
                    mine.customLinks().isEmpty(), "OWNER lost customLinks when " + flag + " was off");
            assertFalse(mine.languages().isEmpty(), "OWNER lost languages when " + flag + " was off");
            // rateCard is the one exception: "hidden" means hidden from the creator's own page
            // preview too, and buildRateCard has always treated it that way.
            if (!flag.equals("rateCard")) {
                assertFalse(
                        mine.rateCard().isEmpty(), "OWNER lost rateCard when " + flag + " was off");
            }
        }
    }

    @Test
    @DisplayName(
            "F-0973: contactForm = off makes POST /portfolio/{username}/contact 404 -- hiding the"
                    + " button did not stop the mail")
    void contactFormFlagBindsOnTheContactEndpoint() {
        setVisibility(allVisible());
        lenient()
                .when(
                        abuseThrottleService.tryConsume(
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.any(Duration.class),
                                org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(true);
        assertTrue(
                service.contact(USERNAME, "Ankit", "ankit@brand.com", "Collab?").delivered(),
                "fixture: contact must succeed while the form is enabled");

        setVisibility(withFlagOff("contactForm"));
        ApiException refused =
                assertThrows(
                        ApiException.class,
                        () -> service.contact(USERNAME, "Ankit", "ankit@brand.com", "Collab?"),
                        "a creator who disabled their contact form was still reachable by POSTing"
                                + " directly to the endpoint (F-0973)");
        assertEquals(
                "PORTFOLIO_NOT_FOUND",
                refused.getCode(),
                "a disabled contact form must be indistinguishable from an unknown handle");
    }

    @Test
    @DisplayName(
            "F-0975: a suspended creator's portfolio is not served and cannot be contacted, even"
                    + " though suspend() never flips `discoverable`")
    void suspendedCreatorIsNotServed() {
        profile.suspend("policy violation", "01HADMINMATRIX00001AB");
        assertTrue(profile.isDiscoverable(), "fixture: suspend() must leave discoverable alone");

        assertEquals(
                "PORTFOLIO_NOT_FOUND",
                assertThrows(ApiException.class, this::publicPage).getCode(),
                "a suspended creator kept a live public page at /@handle");
        assertEquals(
                "PORTFOLIO_NOT_FOUND",
                assertThrows(
                                ApiException.class,
                                () -> service.contact(USERNAME, "A", "a@b.com", "hi"))
                        .getCode(),
                "a suspended creator could still be emailed through their portfolio");

        service.recordPublicView(USERNAME);
        org.mockito.Mockito.verifyNoInteractions(portfolioEventRepository);
    }

    @Test
    @DisplayName(
            "F-0974: rateCard 'brands_only' resolves to real rows for BRAND and to nothing for"
                    + " ANONYMOUS; 'hidden' stays hidden from both")
    void brandsOnlyRateCardResolvesForABrandAndNotForThepublic() {
        Function<PortfolioVisibility, PortfolioVisibility> unused = Function.identity();
        setVisibility(
                new PortfolioVisibility(
                        true, true, true, true, true, true, "brands_only", true, true));

        assertTrue(
                publicPage().rateCard().isEmpty(),
                "'brands_only' must not reach an anonymous caller");
        PortfolioBrandView brandView = service.getForBrand(profile);
        assertFalse(
                brandView.rateCard().isEmpty(),
                "'brands_only' resolved to nothing for a signed-in brand too, which is what made"
                        + " the public page's \"sign in as a brand\" card a dead end (F-0974)");

        setVisibility(
                new PortfolioVisibility(true, true, true, true, true, true, "hidden", true, true));
        assertTrue(publicPage().rateCard().isEmpty(), "'hidden' must not reach the public");
        assertTrue(
                service.getForBrand(profile).rateCard().isEmpty(),
                "'hidden' must not reach a brand either");
        assertNull(unused.apply(null));
    }

    @Test
    @DisplayName(
            "F-0972: a BRAND viewer is bound by every section flag exactly as the public is --"
                    + " only the rate card differs")
    void brandViewerIsBoundByTheSameSectionFlags() {
        setVisibility(withFlagOff("contentPortfolio"));
        assertTrue(
                service.getForBrand(profile).contentPortfolio().isEmpty(),
                "a creator who hid their content portfolio should not have it shown to brands"
                        + " merely because a different brand is looking");

        setVisibility(withFlagOff("pastCollabs"));
        assertTrue(
                service.getForBrand(profile).pastCollabs().isEmpty(),
                "a creator's hidden collab history must stay hidden from brands too");

        setVisibility(withFlagOff("trustBar"));
        assertNull(
                service.getForBrand(profile).stats(),
                "trust stats must be withheld from a brand when the creator hid the trust bar");
    }

    /**
     * F-0980 — the batch projection must resolve EACH creator's own settings.
     *
     * <p>The independent re-audit of the F-0980 fix found that every other test in this repo
     * builds a SINGLE-creator page, so the correct per-creator form and the broken
     * "hoist loadSettings out of the loop" form are indistinguishable: with one creator they
     * produce identical output, and the 13 discovery cases, the reflective matrix above and the
     * producer gate all stay green either way. Hoisting would apply one creator's privacy choice
     * to an entire page of search results — a wider leak than the one F-0980 closed.
     *
     * <p>Two creators, opposite settings, one call, asserted in both orders. This is the only
     * assertion in the suite that can tell the two implementations apart.
     */
    @Test
    @DisplayName(
            "F-0980: a batch page applies EACH creator's own platformStats flag, not the page"
                    + " head's, in either order")
    void batchProjectionAppliesPerCreatorSettingsNotThePageHead() {
        String shownId = "01HCREATORSHOWN00001A";
        String hiddenId = "01HCREATORHIDDEN0001A";

        CreatorProfile shown = CreatorProfile.newForUser(shownId, "01HUSERSHOWN000001AB", "Shown");
        CreatorProfile hidden = CreatorProfile.newForUser(hiddenId, "01HUSERHIDDEN00001AB", "Hidden");
        shown.applyPortfolioSettingsJson(settingsJsonWithPlatformStats(true));
        hidden.applyPortfolioSettingsJson(settingsJsonWithPlatformStats(false));

        lenient()
                .when(platformStatRepository.findByCreatorProfileIdIn(List.of(shownId, hiddenId)))
                .thenReturn(List.of(platformRow(shownId), platformRow(hiddenId)));
        lenient()
                .when(platformStatRepository.findByCreatorProfileIdIn(List.of(hiddenId, shownId)))
                .thenReturn(List.of(platformRow(hiddenId), platformRow(shownId)));

        var byProfile =
                service.getVisiblePlatformStats(
                        List.of(shown, hidden), PortfolioService.ViewerMode.BRAND);
        assertFalse(
                byProfile.get(shownId).isEmpty(),
                "the creator who left platform stats ON lost their rows — the batch projection is"
                        + " applying another creator's setting, or over-blocking");
        assertTrue(
                byProfile.get(hiddenId).isEmpty(),
                "the creator who switched platform stats OFF had their handle and follower count"
                        + " served to a brand anyway, because the batch projection resolved"
                        + " settings once for the page instead of once per creator (F-0980)");

        // Page order must not decide the outcome: same two creators, head swapped.
        var reversed =
                service.getVisiblePlatformStats(
                        List.of(hidden, shown), PortfolioService.ViewerMode.BRAND);
        assertTrue(
                reversed.get(hiddenId).isEmpty(),
                "the hidden creator leaked when listed FIRST — page order must not affect the flag");
        assertFalse(
                reversed.get(shownId).isEmpty(),
                "the shown creator was blocked when listed SECOND — page order must not affect the"
                        + " flag");
    }

    private static String settingsJsonWithPlatformStats(boolean on) {
        return "{\"visibility\":{\"trustBar\":true,\"badges\":true,\"platformStats\":"
                + on
                + ",\"pastCollabs\":true,\"contentPortfolio\":true,\"customLinks\":true,"
                + "\"rateCard\":\"public\",\"languages\":true,\"contactForm\":true}}";
    }

    private static PlatformStat platformRow(String creatorProfileId) {
        return PlatformStat.builder()
                .id("01HSTAT" + creatorProfileId.substring(7))
                .creatorProfileId(creatorProfileId)
                .platform("INSTAGRAM")
                .handle("handle_" + creatorProfileId.substring(7, 12).toLowerCase())
                .followers(50_000L)
                .engagementRate(new BigDecimal("3.10"))
                .verified(true)
                .build();
    }
}
