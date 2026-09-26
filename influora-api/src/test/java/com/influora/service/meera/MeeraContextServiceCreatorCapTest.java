package com.influora.service.meera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.influora.config.CreatorCreditProperties;
import com.influora.domain.entity.CreatorAgentPreferences;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.enums.VerificationStatus;
import com.influora.repository.BrandProfileRepository;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CampaignTemplateRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.CreatorAgentPreferencesRepository;
import com.influora.repository.CreatorMetricsRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.DeliverableMetricRepository;
import com.influora.repository.EscrowHoldRepository;
import com.influora.repository.MetaOAuthTokenRepository;
import com.influora.repository.UtmCampaignRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.web.dto.meera.MeeraContextDtos.CreatorContextResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * T-CREATOR-CREDITS-V2 round 2 (SPEC.md A38, B20, C22) — {@code
 * MeeraContextServiceCreatorCapTest#backstopOnlyWhenFlagOn}: with {@code
 * CREATOR_CREDITS_ENABLED} on, the creator context's {@code ai_monthly_cap_usd} is ALWAYS present
 * and at least the configured USD backstop (25.00 in this test) — 30 credits/day * 31 days costs
 * at most ~$0.74/day, which the pre-existing $0.75/MONTH default would otherwise cut off on the
 * first paid day. With the flag off, the field is byte-identical to the pre-B20 contract:
 * omitted when the creator has no admin override, and exactly her own override when she does.
 * Mirrors {@link MeeraContextServiceTest}'s own setup (same constructor, same minimal creator
 * profile stub) since no narrower seam exists to call {@code resolveAiMonthlyCapUsd} directly (it
 * is {@code private}).
 */
@ExtendWith(MockitoExtension.class)
class MeeraContextServiceCreatorCapTest {

    private static final String CREATOR_USER_ID = "01HCREATORCAPTEST00001";
    private static final BigDecimal BACKSTOP = new BigDecimal("25.00");
    private static final BigDecimal BRIEF_BACKSTOP = new BigDecimal("12.00");

    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private BrandProfileRepository brandProfileRepository;
    @Mock private CampaignTemplateRepository templateRepository;
    @Mock private CampaignRepository campaignRepository;
    @Mock private CollaborationRepository collaborationRepository;
    @Mock private EscrowHoldRepository escrowHoldRepository;
    @Mock private DeliverableMetricRepository deliverableMetricRepository;
    @Mock private UtmCampaignRepository utmCampaignRepository;
    @Mock private AICreditService creditService;
    @Mock private CreatorProfileRepository creatorProfileRepository;
    @Mock private CreatorAgentPreferencesRepository creatorAgentPreferencesRepository;
    @Mock private CreatorMetricsRepository creatorMetricsRepository;
    @Mock private com.influora.service.analytics.AnalyticsService analyticsService;
    @Mock private MetaOAuthTokenRepository metaOAuthTokenRepository;

    private MeeraContextService serviceWith(CreatorCreditProperties creditProperties) {
        return new MeeraContextService(
                workspaceRepository,
                brandProfileRepository,
                templateRepository,
                campaignRepository,
                collaborationRepository,
                escrowHoldRepository,
                deliverableMetricRepository,
                utmCampaignRepository,
                creditService,
                new BrandContextAssembler(),
                creatorProfileRepository,
                creatorAgentPreferencesRepository,
                creatorMetricsRepository,
                analyticsService,
                metaOAuthTokenRepository,
                creditProperties);
    }

    private static CreatorCreditProperties creditProperties(boolean enabled) {
        return new CreatorCreditProperties(
                enabled, 1, 1, 3, 30, 40, 15, 90, "Asia/Kolkata", BACKSTOP, BRIEF_BACKSTOP, 3);
    }

    @BeforeEach
    void setUp() {
        CreatorProfile profile = mock(CreatorProfile.class);
        when(creatorProfileRepository.findByUserId(CREATOR_USER_ID)).thenReturn(Optional.of(profile));
        when(profile.getId()).thenReturn("profile-cap-1");
        when(profile.getDisplayName()).thenReturn("Cap Tester");
        when(profile.getCity()).thenReturn("Mumbai");
        when(profile.getCategoriesJson()).thenReturn(null);
        when(profile.getTotalFollowers()).thenReturn(1000L);
        when(profile.getGstin()).thenReturn(null);
        when(profile.getIdentityKycStatus()).thenReturn(VerificationStatus.VERIFIED);
        when(profile.getTierOverride()).thenReturn(null);
        when(creatorMetricsRepository.findByCreatorProfileIdAndDataSourceOrderByTimeDesc(
                        eq("profile-cap-1"), eq("META_API"), any()))
                .thenReturn(List.of());
        when(collaborationRepository.findByCreatorId(CREATOR_USER_ID)).thenReturn(List.of());
    }

    private CreatorContextResponse assembleWith(CreatorCreditProperties creditProperties) {
        Object result = serviceWith(creditProperties).assemble(CREATOR_USER_ID, "CREATOR");
        assertTrue(result instanceof CreatorContextResponse);
        return (CreatorContextResponse) result;
    }

    @Test
    @DisplayName(
            "A38: flag ON, no admin override -> ai_monthly_cap_usd is the backstop itself, \"25.00\""
                    + " (>= 25.00)")
    void flagOn_noOverride_usesBackstop() {
        when(creatorAgentPreferencesRepository.findByCreatorId("profile-cap-1")).thenReturn(Optional.empty());

        CreatorContextResponse response = assembleWith(creditProperties(true));

        assertEquals("25.00", response.aiMonthlyCapUsd());
        assertTrue(new BigDecimal(response.aiMonthlyCapUsd()).compareTo(BACKSTOP) >= 0);
    }

    @Test
    @DisplayName(
            "A38: flag ON, admin override BELOW the backstop -> the backstop still wins (never lets"
                    + " a stale/low override cut a paid creator off within the first day)")
    void flagOn_overrideBelowBackstop_backstopWins() {
        CreatorAgentPreferences prefs = mock(CreatorAgentPreferences.class);
        when(prefs.getAiMonthlyCapUsd()).thenReturn(new BigDecimal("0.75"));
        stubPrefsDefaults(prefs);
        when(creatorAgentPreferencesRepository.findByCreatorId("profile-cap-1")).thenReturn(Optional.of(prefs));

        CreatorContextResponse response = assembleWith(creditProperties(true));

        assertEquals("25.00", response.aiMonthlyCapUsd());
    }

    @Test
    @DisplayName("A38: flag ON, admin override ABOVE the backstop -> the override wins (still >= 25.00)")
    void flagOn_overrideAboveBackstop_overrideWins() {
        CreatorAgentPreferences prefs = mock(CreatorAgentPreferences.class);
        when(prefs.getAiMonthlyCapUsd()).thenReturn(new BigDecimal("40.00"));
        stubPrefsDefaults(prefs);
        when(creatorAgentPreferencesRepository.findByCreatorId("profile-cap-1")).thenReturn(Optional.of(prefs));

        CreatorContextResponse response = assembleWith(creditProperties(true));

        assertEquals("40.00", response.aiMonthlyCapUsd());
    }

    @Test
    @DisplayName(
            "A38: flag OFF -> byte-identical to the pre-B20 contract: no admin override means the"
                    + " field is entirely absent (null), never the 25.00 backstop leaking through")
    void flagOff_noOverride_fieldAbsent() {
        when(creatorAgentPreferencesRepository.findByCreatorId("profile-cap-1")).thenReturn(Optional.empty());

        CreatorContextResponse response = assembleWith(creditProperties(false));

        assertNull(response.aiMonthlyCapUsd());
    }

    @Test
    @DisplayName(
            "A38: flag OFF -> a creator's own override (e.g. 0.75) is rendered exactly as before,"
                    + " NEVER floored up to the 25.00 backstop")
    void flagOff_withOverride_rendersOverrideUnchanged() {
        CreatorAgentPreferences prefs = mock(CreatorAgentPreferences.class);
        when(prefs.getAiMonthlyCapUsd()).thenReturn(new BigDecimal("0.75"));
        stubPrefsDefaults(prefs);
        when(creatorAgentPreferencesRepository.findByCreatorId("profile-cap-1")).thenReturn(Optional.of(prefs));

        CreatorContextResponse response = assembleWith(creditProperties(false));

        assertEquals("0.75", response.aiMonthlyCapUsd());
    }

    @Test
    @DisplayName(
            "A38 (exact acceptance wording): with the flag on, the creator context emits"
                    + " ai_monthly_cap_usd >= 25.00 (the configured USD backstop, regardless of any lower"
                    + " admin override); with the flag off, both the cap-presence and its value are"
                    + " completely unchanged from the pre-B20 contract")
    void backstopOnlyWhenFlagOn() {
        // -- flag ON, no admin override at all -> the backstop itself, "25.00".
        when(creatorAgentPreferencesRepository.findByCreatorId("profile-cap-1")).thenReturn(Optional.empty());
        CreatorContextResponse onNoOverride = assembleWith(creditProperties(true));
        assertEquals("25.00", onNoOverride.aiMonthlyCapUsd());
        assertTrue(new BigDecimal(onNoOverride.aiMonthlyCapUsd()).compareTo(BACKSTOP) >= 0);

        // -- flag ON, an admin override BELOW the backstop -> the backstop still wins (a stale/low
        // override must never cut a paid creator off within the first day).
        CreatorAgentPreferences lowOverridePrefs = mock(CreatorAgentPreferences.class);
        when(lowOverridePrefs.getAiMonthlyCapUsd()).thenReturn(new BigDecimal("0.75"));
        stubPrefsDefaults(lowOverridePrefs);
        when(creatorAgentPreferencesRepository.findByCreatorId("profile-cap-1"))
                .thenReturn(Optional.of(lowOverridePrefs));
        CreatorContextResponse onLowOverride = assembleWith(creditProperties(true));
        assertEquals("25.00", onLowOverride.aiMonthlyCapUsd());
        assertTrue(new BigDecimal(onLowOverride.aiMonthlyCapUsd()).compareTo(BACKSTOP) >= 0);

        // -- flag ON, an admin override ABOVE the backstop -> the override itself wins (still, by
        // construction, >= 25.00).
        CreatorAgentPreferences highOverridePrefs = mock(CreatorAgentPreferences.class);
        when(highOverridePrefs.getAiMonthlyCapUsd()).thenReturn(new BigDecimal("40.00"));
        stubPrefsDefaults(highOverridePrefs);
        when(creatorAgentPreferencesRepository.findByCreatorId("profile-cap-1"))
                .thenReturn(Optional.of(highOverridePrefs));
        CreatorContextResponse onHighOverride = assembleWith(creditProperties(true));
        assertEquals("40.00", onHighOverride.aiMonthlyCapUsd());
        assertTrue(new BigDecimal(onHighOverride.aiMonthlyCapUsd()).compareTo(BACKSTOP) >= 0);

        // -- flag OFF, no override -> the field is entirely ABSENT (null) — never the backstop
        // leaking through when the feature is off.
        when(creatorAgentPreferencesRepository.findByCreatorId("profile-cap-1")).thenReturn(Optional.empty());
        CreatorContextResponse offNoOverride = assembleWith(creditProperties(false));
        assertNull(offNoOverride.aiMonthlyCapUsd());

        // -- flag OFF, WITH an override -> rendered exactly as before B20 (0.75), never floored up
        // to 25.00.
        when(creatorAgentPreferencesRepository.findByCreatorId("profile-cap-1"))
                .thenReturn(Optional.of(lowOverridePrefs));
        CreatorContextResponse offWithOverride = assembleWith(creditProperties(false));
        assertEquals("0.75", offWithOverride.aiMonthlyCapUsd());
    }

    /** Minimal stubbing so {@code assembleCreatorContext} can read every other {@code prefs} field it touches without a NPE. */
    private static void stubPrefsDefaults(CreatorAgentPreferences prefs) {
        when(prefs.getExcludedCategoriesJson()).thenReturn(null);
        when(prefs.getBlockedBrandsJson()).thenReturn(null);
        when(prefs.getWorkingDaysJson()).thenReturn(null);
        when(prefs.getApprovalLevel()).thenReturn(CreatorAgentPreferences.APPROVAL_LEVEL_DRAFT_ONLY);
        when(prefs.isRepresented()).thenReturn(false);
        when(prefs.isNegotiationHoldout()).thenReturn(false);
        when(prefs.getBrandTone()).thenReturn(null);
        when(prefs.getAgencyName()).thenReturn(null);
        when(prefs.getWorkingHoursStart()).thenReturn(null);
        when(prefs.getWorkingHoursEnd()).thenReturn(null);
        when(prefs.getWorkingHoursTimezone()).thenReturn(CreatorAgentPreferences.DEFAULT_WORKING_HOURS_TIMEZONE);
        when(prefs.getWeeklySponsoredLimit()).thenReturn(null);
        when(prefs.getFloorCurrency()).thenReturn(CreatorAgentPreferences.DEFAULT_FLOOR_CURRENCY);
        when(prefs.isConsentAccepted()).thenReturn(false);
        when(prefs.getConsentVersion()).thenReturn(null);
        when(prefs.getHoldoutUntil()).thenReturn(null);
        when(prefs.isRateCardShareable()).thenReturn(false);
        when(prefs.getApprovedDraftCount()).thenReturn(0);
    }
}
