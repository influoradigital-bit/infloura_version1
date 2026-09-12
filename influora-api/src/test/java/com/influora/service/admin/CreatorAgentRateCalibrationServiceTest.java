package com.influora.service.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.influora.domain.enums.OfferEvent;
import com.influora.repository.AuditLogEntryRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.CollaborationRepository.RateBandCandidateRow;
import com.influora.repository.DealOfferHistoryRepository;
import com.influora.service.rates.RateQuoteService;
import com.influora.service.rates.RateTierProperties;
import com.influora.web.dto.admin.AdminCreatorAgentDtos.RateCalibrationResponse;
import com.influora.web.dto.admin.AdminCreatorAgentDtos.RateCalibrationTier;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;14.1.g, B0-35) — the rate calibration report.
 *
 * <p>The tests that matter most here are the ones about ABSENCE. This report's whole reason to
 * exist is that nobody has calibrated the pricing constants, so the failure mode it must not have
 * is producing a confident-looking number from a sample too small to mean anything. A median over
 * four deals rendered as fact is worse than no median at all — it is the number that gets copied
 * into {@code application.yml} by B0-36 and then priced against for months.
 *
 * <p>{@code nullMedianBelowTheFloorIsWhatTheFloorIsFor} is the falsification anchor: delete the
 * {@code realisedN >= BAND_MIN_DEALS} branch in
 * {@link CreatorAgentRateCalibrationService} and it goes red (verified 2026-09-10 — it reported
 * {@code expected: <null> but was: <2500>}). Because {@code BAND_MIN_DEALS} is a
 * {@code static final int} and therefore inlined at compile time, that falsification needs a
 * {@code clean compile}, not an incremental one.
 */
@ExtendWith(MockitoExtension.class)
class CreatorAgentRateCalibrationServiceTest {

    @Mock private CollaborationRepository collaborationRepository;
    @Mock private DealOfferHistoryRepository dealOfferHistoryRepository;
    @Mock private AuditLogEntryRepository auditLogEntryRepository;

    private RateTierProperties rateTierProperties;
    private CreatorAgentRateCalibrationService service;

    @BeforeEach
    void setUp() {
        rateTierProperties = noOverrides();
        service =
                new CreatorAgentRateCalibrationService(
                        collaborationRepository,
                        dealOfferHistoryRepository,
                        auditLogEntryRepository,
                        rateTierProperties);
        lenient().when(collaborationRepository.findAllRateBandCandidates()).thenReturn(List.of());
        lenient()
                .when(auditLogEntryRepository.findDetailJsonByEventTypeSince(anyString(), any()))
                .thenReturn(List.of());
        lenient()
                .when(dealOfferHistoryRepository.findDistinctCollaborationIdsByEvent(any(), any()))
                .thenReturn(List.of());
    }

    // =============================================================================================
    // Day one - no deals anywhere
    // =============================================================================================

    @Test
    @DisplayName("Day one, with no completed priced deals at all: every tier reports a NULL median and n=0, never a zero")
    void dayOneShowsEveryTierBelowTheFloor() {
        RateCalibrationResponse response = service.getRateCalibration();

        assertEquals(5, response.tiers().size());
        for (RateCalibrationTier tier : response.tiers()) {
            assertNull(tier.realisedMedian(), tier.tier() + " must have no median with zero deals");
            assertNull(tier.meeraAnchoredShare(), tier.tier() + " must have no share with zero deals");
            assertEquals(0, tier.realisedN());
            assertEquals(0, tier.distinctWorkspaces());
            assertNull(tier.quotedMedian90d(), tier.tier() + " must have no quoted median with zero quotes");
            assertEquals(0, tier.quotedN90d());
            // The benchmark half is a constant, so it is always present - that is the point of the
            // report on day one: the formula is visible even when nothing has been measured.
            assertNotNull(tier.benchmarkMin());
            assertNotNull(tier.benchmarkMax());
            assertNotNull(tier.benchmarkUnit());
            assertEquals("compiled default", tier.benchmarkSource());
        }
    }

    @Test
    @DisplayName("Tiers come back in SIZE order (NANO..MEGA), not alphabetical")
    void tiersAreInSizeOrder() {
        assertEquals(
                List.of("NANO", "MICRO", "MID", "MACRO", "MEGA"),
                service.getRateCalibration().tiers().stream().map(RateCalibrationTier::tier).toList());
        // Alphabetical would be MACRO, MEGA, MICRO, MID, NANO - the exact wrong order the admin
        // page's loyalty-tier TIER_ORDER constant would produce by falling through to localeCompare.
    }

    // =============================================================================================
    // The k-anonymity floor
    // =============================================================================================

    @Test
    @DisplayName("A tier one deal below the floor reports realised_median = null and realised_n = 4 - not a zero, not a partial figure")
    void nullMedianBelowTheFloorIsWhatTheFloorIsFor() {
        givenBand(
                nanoRow("2000", "ws-1", "co-1"),
                nanoRow("2400", "ws-2", "co-2"),
                nanoRow("2600", "ws-3", "co-3"),
                nanoRow("3000", "ws-4", "co-4"));

        RateCalibrationTier nano = tier(service.getRateCalibration(), "NANO");

        assertNull(nano.realisedMedian(), "four deals is below BAND_MIN_DEALS - no median may be emitted");
        assertEquals(4, nano.realisedN(), "the sample size is still reported, so the reader knows why");
        assertEquals(4, nano.distinctWorkspaces());
    }

    @Test
    @DisplayName("The meera_anchored_share is suppressed under the same floor - at n=1 it is exactly 0.0 or 1.0 and states an attribute of one identifiable deal")
    void shareIsSuppressedBelowTheFloorToo() {
        givenBand(nanoRow("2000", "ws-1", "co-1"));
        when(dealOfferHistoryRepository.findDistinctCollaborationIdsByEvent(any(), any()))
                .thenReturn(List.of("co-1"));

        RateCalibrationTier nano = tier(service.getRateCalibration(), "NANO");

        assertNull(nano.meeraAnchoredShare());
        assertEquals(1, nano.realisedN());
    }

    @Test
    @DisplayName("At exactly the floor the median appears - the guard is >=, not >")
    void medianAppearsAtExactlyTheFloor() {
        givenBand(
                nanoRow("2000", "ws-1", "co-1"),
                nanoRow("2400", "ws-2", "co-2"),
                nanoRow("2500", "ws-3", "co-3"),
                nanoRow("2600", "ws-4", "co-4"),
                nanoRow("3000", "ws-5", "co-5"));

        RateCalibrationTier nano = tier(service.getRateCalibration(), "NANO");

        assertEquals(0, new BigDecimal("2500").compareTo(nano.realisedMedian()));
        assertEquals(5, nano.realisedN());
        assertEquals(5, nano.distinctWorkspaces());
        assertEquals(RateQuoteService.BAND_MIN_DEALS, nano.realisedN());
    }

    @Test
    @DisplayName("distinct_workspaces counts workspaces, not deals - five deals from two brands is 5 and 2")
    void distinctWorkspacesIsAWorkspaceCount() {
        givenBand(
                nanoRow("2000", "ws-1", "co-1"),
                nanoRow("2400", "ws-1", "co-2"),
                nanoRow("2500", "ws-1", "co-3"),
                nanoRow("2600", "ws-2", "co-4"),
                nanoRow("3000", "ws-2", "co-5"));

        RateCalibrationTier nano = tier(service.getRateCalibration(), "NANO");

        assertEquals(5, nano.realisedN());
        assertEquals(2, nano.distinctWorkspaces());
        // Note the report does NOT suppress on distinct workspaces the way the QUOTE path does -
        // it reports the count so an admin can see a thin band rather than being shown nothing.
        assertNotNull(nano.realisedMedian());
    }

    // =============================================================================================
    // meera_anchored_share
    // =============================================================================================

    @Test
    @DisplayName("Two MEERA_COUNTER rows on ONE collaboration cannot push the share above 1.0")
    void shareIsDistinctOnBothSides() {
        givenBand(
                nanoRow("2000", "ws-1", "co-1"),
                nanoRow("2400", "ws-2", "co-2"),
                nanoRow("2500", "ws-3", "co-3"),
                nanoRow("2600", "ws-4", "co-4"),
                nanoRow("3000", "ws-5", "co-5"));
        // The repository method is the DISTINCT one by construction; this asserts the consuming
        // side does not re-introduce a row count by, say, counting a returned list with dupes.
        when(dealOfferHistoryRepository.findDistinctCollaborationIdsByEvent(
                        any(), eq(OfferEvent.MEERA_COUNTER)))
                .thenReturn(List.of("co-1", "co-1", "co-2"));

        RateCalibrationTier nano = tier(service.getRateCalibration(), "NANO");

        assertTrue(nano.meeraAnchoredShare() <= 1.0, "share must never exceed 1.0");
        assertEquals(0.4, nano.meeraAnchoredShare(), 0.0001, "2 anchored collaborations of 5");
    }

    // =============================================================================================
    // Window and tier bucketing
    // =============================================================================================

    @Test
    @DisplayName("Deals outside the 90-day window are excluded, same as the quote path's band")
    void oldDealsAreOutsideTheWindow() {
        givenBand(
                nanoRowAt("2000", "ws-1", "co-1", daysAgo(200)),
                nanoRowAt("2400", "ws-2", "co-2", daysAgo(200)),
                nanoRowAt("2500", "ws-3", "co-3", daysAgo(200)),
                nanoRowAt("2600", "ws-4", "co-4", daysAgo(10)),
                nanoRowAt("3000", "ws-5", "co-5", daysAgo(10)));

        RateCalibrationTier nano = tier(service.getRateCalibration(), "NANO");

        assertEquals(2, nano.realisedN());
        assertNull(nano.realisedMedian());
    }

    @Test
    @DisplayName("A row with an unreadable updated_at is dropped rather than silently widening the 90-day claim")
    void nullTimestampRowsAreDropped() {
        givenBand(nanoRowAt("2000", "ws-1", "co-1", null));

        assertEquals(0, tier(service.getRateCalibration(), "NANO").realisedN());
    }

    @Test
    @DisplayName("Rows are bucketed by CreatorTiers.derive, so a 60k-follower deal lands in MID and not in NANO")
    void rowsBucketByFollowerTier() {
        givenBand(
                row("2000", "ws-1", "co-1", 3_000L, daysAgo(5)),
                row("90000", "ws-2", "co-2", 60_000L, daysAgo(5)));

        RateCalibrationResponse response = service.getRateCalibration();

        assertEquals(1, tier(response, "NANO").realisedN());
        assertEquals(1, tier(response, "MID").realisedN());
        assertEquals(0, tier(response, "MICRO").realisedN());
    }

    // =============================================================================================
    // The quoted half - RATE_QUOTE_ISSUED audit rows
    // =============================================================================================

    @Test
    @DisplayName("quoted_median_90d is the median of RATE_QUOTE_ISSUED totals for that tier")
    void quotedMedianComesFromTheAuditRows() {
        when(auditLogEntryRepository.findDetailJsonByEventTypeSince(
                        eq(RateQuoteService.EVENT_RATE_QUOTE_ISSUED), any()))
                .thenReturn(
                        List.of(
                                quoteDetail("NANO", "4000"),
                                quoteDetail("NANO", "5000"),
                                quoteDetail("NANO", "6000"),
                                quoteDetail("MID", "80000")));

        RateCalibrationResponse response = service.getRateCalibration();

        assertEquals(0, new BigDecimal("5000").compareTo(tier(response, "NANO").quotedMedian90d()));
        assertEquals(3, tier(response, "NANO").quotedN90d());
        assertEquals(0, new BigDecimal("80000").compareTo(tier(response, "MID").quotedMedian90d()));
        assertEquals(1, tier(response, "MID").quotedN90d());
    }

    @Test
    @DisplayName("quoted_median_90d is NOT behind the k-anonymity floor - a single quote is our own emission, not a third party's deal")
    void quotedMedianHasNoKAnonymityFloor() {
        when(auditLogEntryRepository.findDetailJsonByEventTypeSince(
                        eq(RateQuoteService.EVENT_RATE_QUOTE_ISSUED), any()))
                .thenReturn(List.of(quoteDetail("NANO", "4000")));

        RateCalibrationTier nano = tier(service.getRateCalibration(), "NANO");

        assertNotNull(nano.quotedMedian90d());
        assertEquals(1, nano.quotedN90d(), "n is sent alongside so the reader can weigh it");
    }

    @Test
    @DisplayName("An UNKNOWN-tier quote (no metric row) is counted nowhere rather than folded into a tier it does not belong to")
    void unknownTierQuotesAreDropped() {
        when(auditLogEntryRepository.findDetailJsonByEventTypeSince(
                        eq(RateQuoteService.EVENT_RATE_QUOTE_ISSUED), any()))
                .thenReturn(List.of(quoteDetail("UNKNOWN", "4000"), quoteDetail("NANO", "4000")));

        RateCalibrationResponse response = service.getRateCalibration();

        assertEquals(1, tier(response, "NANO").quotedN90d());
        assertEquals(
                1,
                response.tiers().stream().mapToInt(RateCalibrationTier::quotedN90d).sum(),
                "the UNKNOWN row must not appear in any tier");
    }

    @Test
    @DisplayName("A malformed or partial audit detail is skipped, never guessed at")
    void malformedAuditRowsAreSkipped() {
        when(auditLogEntryRepository.findDetailJsonByEventTypeSince(
                        eq(RateQuoteService.EVENT_RATE_QUOTE_ISSUED), any()))
                .thenReturn(
                        java.util.Arrays.asList(
                                "not json at all",
                                "{\"tier\":\"NANO\"}",
                                "{\"total\":4000}",
                                null,
                                quoteDetail("NANO", "4000")));

        assertEquals(1, tier(service.getRateCalibration(), "NANO").quotedN90d());
    }

    // =============================================================================================
    // The benchmark half
    // =============================================================================================

    @Test
    @DisplayName("benchmark_unit is the midpoint, computed by RateQuoteService's own formula - NANO 1,000/5,000 -> 3,000")
    void benchmarkUnitIsTheMidpoint() {
        RateCalibrationTier nano = tier(service.getRateCalibration(), "NANO");

        assertEquals(0, new BigDecimal("1000").compareTo(nano.benchmarkMin()));
        assertEquals(0, new BigDecimal("5000").compareTo(nano.benchmarkMax()));
        assertEquals(0, new BigDecimal("3000").compareTo(nano.benchmarkUnit()));
        assertEquals(
                0,
                RateQuoteService.benchmarkUnitFor(nano.benchmarkMin(), nano.benchmarkMax())
                        .compareTo(nano.benchmarkUnit()));
    }

    @Test
    @DisplayName("benchmark_source says 'yml override' when a tier is overridden, so B0-36 can confirm its change took effect")
    void benchmarkSourceNamesTheOverride() {
        service =
                new CreatorAgentRateCalibrationService(
                        collaborationRepository,
                        dealOfferHistoryRepository,
                        auditLogEntryRepository,
                        overrideNano(1200L, 4800L));

        RateCalibrationResponse response = service.getRateCalibration();

        assertEquals("yml override", tier(response, "NANO").benchmarkSource());
        assertEquals(0, new BigDecimal("1200").compareTo(tier(response, "NANO").benchmarkMin()));
        assertEquals(0, new BigDecimal("4800").compareTo(tier(response, "NANO").benchmarkMax()));
        // The overridden band's OWN midpoint, not the compiled one: 1,200 + 0.50 x 3,600 = 3,000.
        // (The compiled NANO band would give the same 3,000 here by coincidence of 1,000/5,000,
        // so the max assertion above is what actually proves the override reached the response.)
        assertEquals(0, new BigDecimal("3000").compareTo(tier(response, "NANO").benchmarkUnit()));

        // An UN-overridden tier keeps its own compiled midpoint. MICRO's band is 5,000-25,000, so
        // that midpoint is 15,000 - NOT NANO's 3,000. Asserting 3,000 here (as this test did until
        // it was first run) passes vacuously against nothing and hides a one-tier override
        // leaking across every other tier's row.
        assertEquals(0, new BigDecimal("15000").compareTo(tier(response, "MICRO").benchmarkUnit()));
        assertEquals(0, new BigDecimal("5000").compareTo(tier(response, "MICRO").benchmarkMin()));
        assertEquals("compiled default", tier(response, "MICRO").benchmarkSource());
    }

    @Test
    @DisplayName("With the yml override block unset - the state B0-36 must leave it in - every tier reports the compiled default")
    void unsetOverridesLeaveTheCompiledConstantsInForce() {
        for (RateCalibrationTier tier : service.getRateCalibration().tiers()) {
            assertEquals("compiled default", tier.benchmarkSource());
            long[] compiled = RateTierProperties.DEFAULT_TIER_BASE_RATES.get(tier.tier());
            assertEquals(0, BigDecimal.valueOf(compiled[0]).compareTo(tier.benchmarkMin()));
            assertEquals(0, BigDecimal.valueOf(compiled[1]).compareTo(tier.benchmarkMax()));
        }
    }

    // =============================================================================================
    // The gate: no identifiers on the payload
    // =============================================================================================

    @Test
    @DisplayName("No candidate row ever escapes aggregation: nothing on a tier row equals a workspace id or a creator id")
    void noIdentifierReachesTheTierRow() {
        givenBand(
                nanoRow("2000", "ws-1", "co-1"),
                nanoRow("2400", "ws-2", "co-2"),
                nanoRow("2500", "ws-3", "co-3"),
                nanoRow("2600", "ws-4", "co-4"),
                nanoRow("3000", "ws-5", "co-5"));

        String rendered = tier(service.getRateCalibration(), "NANO").toString();

        for (String forbidden : List.of("ws-1", "ws-2", "ws-3", "ws-4", "ws-5", "co-1", "creator-")) {
            assertFalse(rendered.contains(forbidden), "tier row leaked " + forbidden + ": " + rendered);
        }
    }

    // =============================================================================================
    // Helpers
    // =============================================================================================

    private static RateCalibrationTier tier(RateCalibrationResponse response, String tier) {
        return response.tiers().stream()
                .filter(t -> t.tier().equals(tier))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + tier + " row in the report"));
    }

    private void givenBand(RateBandCandidateRow... rows) {
        when(collaborationRepository.findAllRateBandCandidates()).thenReturn(List.of(rows));
    }

    private static Instant daysAgo(int days) {
        return Instant.now().minus(days, ChronoUnit.DAYS);
    }

    private static String quoteDetail(String tier, String total) {
        return "{\"tier\":\"" + tier + "\",\"total\":" + total + ",\"currency\":\"INR\"}";
    }

    private static RateBandCandidateRow nanoRow(String rate, String workspaceId, String collaborationId) {
        return row(rate, workspaceId, collaborationId, 3_000L, daysAgo(10));
    }

    private static RateBandCandidateRow nanoRowAt(
            String rate, String workspaceId, String collaborationId, Instant updatedAt) {
        return row(rate, workspaceId, collaborationId, 3_000L, updatedAt);
    }

    private static RateBandCandidateRow row(
            String rate, String workspaceId, String collaborationId, long followers, Instant updatedAt) {
        return new BandRow(new BigDecimal(rate), workspaceId, collaborationId, followers, updatedAt);
    }

    /** A candidate row as the widened projection (B0-32) delivers it. */
    private record BandRow(
            BigDecimal rate, String workspaceId, String collaborationId, long followers, Instant updatedAt)
            implements RateBandCandidateRow {

        @Override
        public BigDecimal getAgreedRate() {
            return rate;
        }

        @Override
        public String getCurrency() {
            return "INR";
        }

        @Override
        public String getWorkspaceId() {
            return workspaceId;
        }

        @Override
        public String getCreatorId() {
            return "creator-" + collaborationId;
        }

        @Override
        public Long getTotalFollowers() {
            return followers;
        }

        @Override
        public Instant getUpdatedAt() {
            return updatedAt;
        }

        @Override
        public String getCollaborationId() {
            return collaborationId;
        }
    }

    /** Every tier on its compiled default - the launch state of {@code influora.rates.tier}. */
    private static RateTierProperties noOverrides() {
        return new RateTierProperties(null, null, null, null, null, null, null, null, null, null);
    }

    private static RateTierProperties overrideNano(Long min, Long max) {
        return new RateTierProperties(min, max, null, null, null, null, null, null, null, null);
    }
}
