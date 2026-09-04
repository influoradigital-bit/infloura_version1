package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.CreatorAgentPreferences;
import com.influora.domain.entity.CreatorProfile;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.CreatorAgentPreferencesRepository;
import com.influora.repository.CreatorMetricsRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.service.scoring.RateEstimationService;
import com.influora.service.scoring.RateEstimationService.RateEstimation;
import com.influora.web.dto.creator.CreatorAgentDtos.PreferencesResponse;
import com.influora.web.dto.creator.CreatorAgentDtos.UpdatePreferencesRequest;
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
 * Gate fix round 1 (Priya Q2, T-MEERA-CREATOR-PHASE-A) — {@link CreatorAgentPreferencesService}
 * had zero test coverage before this: "cutting this wire breaks no test." Covers: lazy-create with
 * computed defaults on first GET (last completed deal rate, falling through to the platform
 * fallback), PUT full-replace of every field, the represented-without-agency-name validation, and
 * DPDP consent record/withdraw idempotency.
 */
@ExtendWith(MockitoExtension.class)
class CreatorAgentPreferencesServiceTest {

    private static final String USER_ID = "user-1";
    private static final String PROFILE_ID = "profile-1";

    @Mock private CreatorAgentPreferencesRepository preferencesRepository;
    @Mock private CreatorProfileRepository creatorProfileRepository;
    @Mock private CollaborationRepository collaborationRepository;
    @Mock private CreatorMetricsRepository creatorMetricsRepository;
    @Mock private RateEstimationService rateEstimationService;
    @Mock private CreatorProfile profile;

    private CreatorAgentPreferencesService service;

    @BeforeEach
    void setUp() {
        service =
                new CreatorAgentPreferencesService(
                        preferencesRepository,
                        creatorProfileRepository,
                        collaborationRepository,
                        creatorMetricsRepository,
                        rateEstimationService);
        // lenient: not every test below exercises the profile lookup (e.g. the 404-on-missing-
        // profile test overrides findByUserId to Optional.empty() and never reaches profile.getId()
        // /getUserId() at all) -- strict Mockito flags those as unnecessary stubbings otherwise.
        org.mockito.Mockito.lenient().when(creatorProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));
        org.mockito.Mockito.lenient().when(creatorProfileRepository.findById(PROFILE_ID)).thenReturn(Optional.of(profile));
        org.mockito.Mockito.lenient().when(profile.getId()).thenReturn(PROFILE_ID);
        org.mockito.Mockito.lenient().when(profile.getUserId()).thenReturn(USER_ID);
    }

    @Test
    @DisplayName("no creator profile for this user -> 404 CREATOR_PROFILE_NOT_FOUND, never a silent default row")
    void getOrCreatePreferencesNoProfileThrows404() {
        when(creatorProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
        ApiException ex = assertThrows(ApiException.class, () -> service.getOrCreatePreferences(USER_ID));
        assertEquals("CREATOR_PROFILE_NOT_FOUND", ex.getCode());
    }

    @Test
    @DisplayName(
            "first GET with no existing row and no completed deal -> floor computed from"
                    + " RateEstimationService's low end and persisted")
    void getOrCreatePreferencesComputesFloorFromRateEstimation() {
        when(preferencesRepository.findByCreatorId(PROFILE_ID)).thenReturn(Optional.empty());
        when(collaborationRepository.findByCreatorId(USER_ID)).thenReturn(List.of());
        when(creatorMetricsRepository.findByCreatorProfileIdOrderByTimeDesc(eq(PROFILE_ID), any()))
                .thenReturn(List.of());
        when(profile.getCategoriesJson()).thenReturn(null);
        when(profile.getLanguagesJson()).thenReturn(null);
        when(rateEstimationService.estimate(any(), any(), any()))
                .thenReturn(new RateEstimation(new BigDecimal("1200"), new BigDecimal("1800"), "INR", BigDecimal.TEN, "MICRO", java.util.Map.of()));
        when(preferencesRepository.save(any(CreatorAgentPreferences.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        PreferencesResponse response = service.getOrCreatePreferences(USER_ID);

        assertEquals(new BigDecimal("1200"), response.reelFloor());
        assertEquals(new BigDecimal("1200"), response.storySetFloor());
        assertEquals(new BigDecimal("1200"), response.postFloor());
        assertEquals("hi-IN", response.creatorLanguage());
        assertEquals(CreatorAgentPreferences.TONE_FRIENDLY, response.brandTone());
        assertEquals(0, response.approvalLevel());
        assertFalse(response.consentAccepted());
    }

    @Test
    @DisplayName(
            "first GET falls all the way through to the FALLBACK_REEL_FLOOR (500) constant when"
                    + " RateEstimationService also resolves to zero (an unconnected creator with no"
                    + " metrics)")
    void getOrCreatePreferencesFallsBackToPlatformMinimum() {
        when(preferencesRepository.findByCreatorId(PROFILE_ID)).thenReturn(Optional.empty());
        when(collaborationRepository.findByCreatorId(USER_ID)).thenReturn(List.of());
        when(creatorMetricsRepository.findByCreatorProfileIdOrderByTimeDesc(eq(PROFILE_ID), any()))
                .thenReturn(List.of());
        when(profile.getCategoriesJson()).thenReturn(null);
        when(profile.getLanguagesJson()).thenReturn(null);
        when(rateEstimationService.estimate(any(), any(), any()))
                .thenReturn(new RateEstimation(BigDecimal.ZERO, BigDecimal.ZERO, "INR", BigDecimal.ZERO, "UNKNOWN", java.util.Map.of()));
        when(preferencesRepository.save(any(CreatorAgentPreferences.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        PreferencesResponse response = service.getOrCreatePreferences(USER_ID);

        assertEquals(new BigDecimal("500"), response.reelFloor());
    }

    @Test
    @DisplayName("existing row -> returned as-is, no recompute, no save")
    void getOrCreatePreferencesReturnsExistingRowUnmodified() {
        CreatorAgentPreferences existing =
                CreatorAgentPreferences.newWithDefaults(
                        "prefs-1", PROFILE_ID, new BigDecimal("999"), new BigDecimal("999"), new BigDecimal("999"), "en-IN");
        when(preferencesRepository.findByCreatorId(PROFILE_ID)).thenReturn(Optional.of(existing));

        PreferencesResponse response = service.getOrCreatePreferences(USER_ID);

        assertEquals(new BigDecimal("999"), response.reelFloor());
        verify(preferencesRepository, org.mockito.Mockito.never()).save(any());
        org.mockito.Mockito.verifyNoInteractions(rateEstimationService);
    }

    @Test
    @DisplayName("PUT round-trips every field")
    void updatePreferencesRoundTripsEveryField() {
        CreatorAgentPreferences existing =
                CreatorAgentPreferences.newWithDefaults(
                        "prefs-1", PROFILE_ID, new BigDecimal("500"), new BigDecimal("500"), new BigDecimal("500"), "hi-IN");
        when(preferencesRepository.findByCreatorId(PROFILE_ID)).thenReturn(Optional.of(existing));

        UpdatePreferencesRequest req =
                new UpdatePreferencesRequest(
                        new BigDecimal("2000"),
                        new BigDecimal("1500"),
                        new BigDecimal("2500"),
                        "USD",
                        List.of("Alcohol"),
                        List.of("RivalCo"),
                        1,
                        "en-IN",
                        CreatorAgentPreferences.TONE_FORMAL,
                        9,
                        18,
                        "America/New_York",
                        List.of(1, 2, 3),
                        5,
                        false,
                        null);

        PreferencesResponse response = service.updatePreferences(USER_ID, req);

        assertEquals(new BigDecimal("2000"), response.reelFloor());
        assertEquals(new BigDecimal("1500"), response.storySetFloor());
        assertEquals(new BigDecimal("2500"), response.postFloor());
        assertEquals("USD", response.floorCurrency());
        assertEquals(List.of("Alcohol"), response.excludedCategories());
        assertEquals(List.of("RivalCo"), response.blockedBrands());
        assertEquals(1, response.approvalLevel());
        assertEquals("en-IN", response.creatorLanguage());
        assertEquals(CreatorAgentPreferences.TONE_FORMAL, response.brandTone());
        assertEquals(9, response.workingHoursStart());
        assertEquals(18, response.workingHoursEnd());
        assertEquals("America/New_York", response.workingHoursTimezone());
        assertEquals(List.of(1, 2, 3), response.workingDays());
        assertEquals(5, response.weeklySponsoredLimit());
        verify(preferencesRepository).save(existing);
    }

    @Test
    @DisplayName(
            "Gate fix round 2, item 3 (Priya Q8): PUT rejects an invalid ISO 4217 currency code")
    void updatePreferencesInvalidCurrencyRejected() {
        UpdatePreferencesRequest req =
                new UpdatePreferencesRequest(
                        null, null, null, "NOTACODE", List.of(), List.of(), 0, "hi-IN", null, null, null, null,
                        List.of(), null, false, null);
        ApiException ex = assertThrows(ApiException.class, () -> service.updatePreferences(USER_ID, req));
        assertEquals("INVALID_CURRENCY", ex.getCode());
    }

    @Test
    @DisplayName(
            "Gate fix round 2, item 3 (Priya Q8): PUT rejects an invalid IANA timezone id")
    void updatePreferencesInvalidTimezoneRejected() {
        UpdatePreferencesRequest req =
                new UpdatePreferencesRequest(
                        null, null, null, null, List.of(), List.of(), 0, "hi-IN", null, null, null,
                        "Not/A_Zone", List.of(), null, false, null);
        ApiException ex = assertThrows(ApiException.class, () -> service.updatePreferences(USER_ID, req));
        assertEquals("INVALID_TIMEZONE", ex.getCode());
    }

    @Test
    @DisplayName("PUT with represented=true and a blank agency_name -> 400 AGENCY_NAME_REQUIRED, nothing saved")
    void updatePreferencesRepresentedWithoutAgencyNameRejected() {
        UpdatePreferencesRequest req =
                new UpdatePreferencesRequest(
                        null, null, null, null, List.of(), List.of(), 0, "hi-IN", null, null, null, null,
                        List.of(), null, true, "  ");

        ApiException ex = assertThrows(ApiException.class, () -> service.updatePreferences(USER_ID, req));
        assertEquals("AGENCY_NAME_REQUIRED", ex.getCode());
        verify(preferencesRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    @DisplayName("PUT with represented=true and a real agency_name -> accepted, agency name persisted")
    void updatePreferencesRepresentedWithAgencyNameAccepted() {
        CreatorAgentPreferences existing =
                CreatorAgentPreferences.newWithDefaults(
                        "prefs-1", PROFILE_ID, new BigDecimal("500"), new BigDecimal("500"), new BigDecimal("500"), "hi-IN");
        when(preferencesRepository.findByCreatorId(PROFILE_ID)).thenReturn(Optional.of(existing));

        UpdatePreferencesRequest req =
                new UpdatePreferencesRequest(
                        null, null, null, null, List.of(), List.of(), 0, "hi-IN", null, null, null, null,
                        List.of(), null, true, "Agency Co");

        PreferencesResponse response = service.updatePreferences(USER_ID, req);
        assertEquals("Agency Co", response.agencyName());
        assertTrue(response.represented());
    }

    @Test
    @DisplayName("PUT with approval_level out of range -> 400 INVALID_APPROVAL_LEVEL")
    void updatePreferencesInvalidApprovalLevelRejected() {
        UpdatePreferencesRequest req =
                new UpdatePreferencesRequest(
                        null, null, null, null, List.of(), List.of(), 5, "hi-IN", null, null, null, null,
                        List.of(), null, false, null);
        ApiException ex = assertThrows(ApiException.class, () -> service.updatePreferences(USER_ID, req));
        assertEquals("INVALID_APPROVAL_LEVEL", ex.getCode());
    }

    @Test
    @DisplayName("PUT with a negative floor -> 400 INVALID_FLOOR")
    void updatePreferencesNegativeFloorRejected() {
        UpdatePreferencesRequest req =
                new UpdatePreferencesRequest(
                        new BigDecimal("-1"), null, null, null, List.of(), List.of(), 0, "hi-IN", null, null, null,
                        null, List.of(), null, false, null);
        ApiException ex = assertThrows(ApiException.class, () -> service.updatePreferences(USER_ID, req));
        assertEquals("INVALID_FLOOR", ex.getCode());
    }

    @Test
    @DisplayName("recordConsent sets consentAcceptedAt+consentVersion on a fresh row and is idempotent on a second call")
    void recordConsentSetsTimestampAndIsIdempotent() {
        CreatorAgentPreferences existing =
                CreatorAgentPreferences.newWithDefaults(
                        "prefs-1", PROFILE_ID, new BigDecimal("500"), new BigDecimal("500"), new BigDecimal("500"), "hi-IN");
        when(preferencesRepository.findByCreatorId(PROFILE_ID)).thenReturn(Optional.of(existing));

        var first = service.recordConsent(USER_ID);
        assertNotNull(first.consentAcceptedAt());
        assertEquals(CreatorAgentPreferences.CURRENT_CONSENT_VERSION, first.consentVersion());

        var second = service.recordConsent(USER_ID);
        assertEquals(first.consentAcceptedAt(), second.consentAcceptedAt());
        assertEquals(CreatorAgentPreferences.CURRENT_CONSENT_VERSION, second.consentVersion());
    }

    /**
     * Gate fix round 2, item 1 (Priya Q3) — a creator whose stored consentVersion is stale (an
     * older DPDP notice, simulated here via reflection since the entity deliberately exposes no
     * setter for it -- only {@code recordConsent()} ever writes it) is NOT considered consented,
     * and {@code recordConsent()} re-stamps both the timestamp and version rather than treating
     * the stale row as already-accepted (the old no-op-on-second-call idempotency only holds when
     * the version is unchanged).
     */
    @Test
    @DisplayName("a creator consented under a stale version reads as not-accepted, and recordConsent re-stamps it")
    void staleConsentVersionIsNotAcceptedAndRecordConsentRestamps() throws Exception {
        CreatorAgentPreferences existing =
                CreatorAgentPreferences.newWithDefaults(
                        "prefs-1", PROFILE_ID, new BigDecimal("500"), new BigDecimal("500"), new BigDecimal("500"), "hi-IN");
        existing.recordConsent();
        java.time.Instant staleAcceptedAt = existing.getConsentAcceptedAt();

        java.lang.reflect.Field versionField = CreatorAgentPreferences.class.getDeclaredField("consentVersion");
        versionField.setAccessible(true);
        versionField.set(existing, "v0");

        assertFalse(existing.isConsentAccepted());

        when(preferencesRepository.findByCreatorId(PROFILE_ID)).thenReturn(Optional.of(existing));
        assertFalse(service.isConsentAccepted(USER_ID));

        var reconsent = service.recordConsent(USER_ID);
        assertEquals(CreatorAgentPreferences.CURRENT_CONSENT_VERSION, reconsent.consentVersion());
        assertTrue(reconsent.consentAcceptedAt().isAfter(staleAcceptedAt) || reconsent.consentAcceptedAt().equals(staleAcceptedAt));
        assertTrue(service.isConsentAccepted(USER_ID));
    }

    @Test
    @DisplayName("withdrawConsent on a creator with no preferences row at all is a no-op, not a 404")
    void withdrawConsentWithNoRowIsNoOp() {
        when(preferencesRepository.findByCreatorId(PROFILE_ID)).thenReturn(Optional.empty());
        service.withdrawConsent(USER_ID);
        verify(preferencesRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    @DisplayName(
            "Gate fix round 1 (Priya Q7): adminSetMonthlyCapOverride sets ai_monthly_cap_usd on"
                    + " an existing row and returns the stored value")
    void adminSetMonthlyCapOverrideSetsValueOnExistingRow() {
        CreatorAgentPreferences existing =
                CreatorAgentPreferences.newWithDefaults(
                        "prefs-1", PROFILE_ID, new BigDecimal("500"), new BigDecimal("500"), new BigDecimal("500"), "hi-IN");
        when(preferencesRepository.findByCreatorId(PROFILE_ID)).thenReturn(Optional.of(existing));

        BigDecimal result = service.adminSetMonthlyCapOverride(PROFILE_ID, new BigDecimal("5.00"));

        assertEquals(new BigDecimal("5.00"), result);
        assertEquals(new BigDecimal("5.00"), existing.getAiMonthlyCapUsd());
        verify(preferencesRepository).save(existing);
    }

    @Test
    @DisplayName(
            "Gate fix round 1 (Priya Q7): adminSetMonthlyCapOverride creates the row with computed"
                    + " defaults first when the creator has never touched Meera, same as recordConsent")
    void adminSetMonthlyCapOverrideCreatesRowWhenAbsent() {
        when(preferencesRepository.findByCreatorId(PROFILE_ID)).thenReturn(Optional.empty());
        when(collaborationRepository.findByCreatorId(USER_ID)).thenReturn(List.of());
        when(creatorMetricsRepository.findByCreatorProfileIdOrderByTimeDesc(eq(PROFILE_ID), any()))
                .thenReturn(List.of());
        when(profile.getCategoriesJson()).thenReturn(null);
        when(profile.getLanguagesJson()).thenReturn(null);
        when(rateEstimationService.estimate(any(), any(), any()))
                .thenReturn(new RateEstimation(BigDecimal.ZERO, BigDecimal.ZERO, "INR", BigDecimal.ZERO, "UNKNOWN", java.util.Map.of()));
        when(preferencesRepository.save(any(CreatorAgentPreferences.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        BigDecimal result = service.adminSetMonthlyCapOverride(PROFILE_ID, new BigDecimal("2.50"));

        assertEquals(new BigDecimal("2.50"), result);
        verify(preferencesRepository, org.mockito.Mockito.times(2)).save(any(CreatorAgentPreferences.class));
    }

    @Test
    @DisplayName("Gate fix round 1 (Priya Q7): adminSetMonthlyCapOverride(null) clears an existing override")
    void adminSetMonthlyCapOverrideNullClearsOverride() {
        CreatorAgentPreferences existing =
                CreatorAgentPreferences.newWithDefaults(
                        "prefs-1", PROFILE_ID, new BigDecimal("500"), new BigDecimal("500"), new BigDecimal("500"), "hi-IN");
        existing.setAiMonthlyCapUsdOverride(new BigDecimal("5.00"));
        when(preferencesRepository.findByCreatorId(PROFILE_ID)).thenReturn(Optional.of(existing));

        BigDecimal result = service.adminSetMonthlyCapOverride(PROFILE_ID, null);

        assertEquals(null, result);
        assertNull(existing.getAiMonthlyCapUsd());
    }

    @Test
    @DisplayName("Gate fix round 1 (Priya Q7): adminSetMonthlyCapOverride rejects a negative cap")
    void adminSetMonthlyCapOverrideRejectsNegative() {
        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.adminSetMonthlyCapOverride(PROFILE_ID, new BigDecimal("-1")));
        assertEquals("INVALID_CAP", ex.getCode());
        verify(preferencesRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    @DisplayName("Gate fix round 1 (Priya Q7): adminSetMonthlyCapOverride on an unknown creator profile id -> 404")
    void adminSetMonthlyCapOverrideUnknownProfile404() {
        when(creatorProfileRepository.findById("nope")).thenReturn(Optional.empty());
        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.adminSetMonthlyCapOverride("nope", new BigDecimal("1")));
        assertEquals("CREATOR_PROFILE_NOT_FOUND", ex.getCode());
    }

    @Test
    @DisplayName("withdrawConsent nulls consentAcceptedAt on a consented row")
    void withdrawConsentNullsTimestamp() {
        CreatorAgentPreferences existing =
                CreatorAgentPreferences.newWithDefaults(
                        "prefs-1", PROFILE_ID, new BigDecimal("500"), new BigDecimal("500"), new BigDecimal("500"), "hi-IN");
        existing.recordConsent();
        when(preferencesRepository.findByCreatorId(PROFILE_ID)).thenReturn(Optional.of(existing));

        service.withdrawConsent(USER_ID);

        assertNull(existing.getConsentAcceptedAt());
        verify(preferencesRepository).save(existing);
    }
}
