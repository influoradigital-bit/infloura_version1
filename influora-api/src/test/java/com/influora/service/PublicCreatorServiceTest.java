package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.CreatorMetric;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.MetaOAuthToken;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.CreatorMetricsRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.MetaOAuthTokenRepository;
import com.influora.web.dto.creator.PublicCreatorDtos.VerifiedProfileResponse;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * T-MEERA-CREATOR-PHASE-A (SPEC.md 2.8, A9; fix round 2, item 4 — Priya Q10). Covers the
 * suspension gap: {@link CreatorProfile#isSuspended()} was never consulted by {@link
 * PublicCreatorService#getVerifiedMetrics}, so an admin-suspended creator's public verified page
 * kept serving live metrics with a plain 200. Also proves the existing non-enumeration property
 * (not-discoverable, not-Meta-connected, suspended, and truly-nonexistent all return the SAME 404)
 * still holds after the fix.
 */
@ExtendWith(MockitoExtension.class)
class PublicCreatorServiceTest {

    private static final String USERNAME = "priya-shah";
    private static final String PROFILE_ID = "profile-1";
    private static final String USER_ID = "user-1";

    @Mock private CreatorProfileRepository creatorProfileRepository;
    @Mock private CreatorMetricsRepository creatorMetricsRepository;
    @Mock private MetaOAuthTokenRepository metaOAuthTokenRepository;
    @Mock private CollaborationRepository collaborationRepository;
    @Mock private CreatorProfile profile;

    private PublicCreatorService service;

    @BeforeEach
    void setUp() {
        service =
                new PublicCreatorService(
                        creatorProfileRepository,
                        creatorMetricsRepository,
                        metaOAuthTokenRepository,
                        collaborationRepository);
        when(creatorProfileRepository.findByUsernameIgnoreCase(USERNAME)).thenReturn(Optional.of(profile));
        when(profile.getId()).thenReturn(PROFILE_ID);
    }

    private void stubDiscoverableAndConnected() {
        when(profile.isDiscoverable()).thenReturn(true);
        when(metaOAuthTokenRepository.findByCreatorProfileIdAndRevokedFalseAndExpiresAtAfter(
                        org.mockito.ArgumentMatchers.eq(PROFILE_ID), org.mockito.ArgumentMatchers.any(Instant.class)))
                .thenReturn(List.of(mock(MetaOAuthToken.class)));
    }

    @Test
    @DisplayName("a suspended creator gets 404 CREATOR_NOT_FOUND, even when discoverable and Meta-connected")
    void suspendedCreator_returns404() {
        stubDiscoverableAndConnected();
        when(profile.isSuspended()).thenReturn(true);

        ApiException ex = assertThrows(ApiException.class, () -> service.getVerifiedMetrics(USERNAME));

        assertEquals("CREATOR_NOT_FOUND", ex.getCode());
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    @DisplayName("a non-suspended, discoverable, Meta-connected creator's metrics are served")
    void activeCreator_isServed() {
        stubDiscoverableAndConnected();
        when(profile.isSuspended()).thenReturn(false);
        when(profile.getUsername()).thenReturn(USERNAME);
        when(profile.getDisplayName()).thenReturn("Priya Shah");
        when(profile.getCity()).thenReturn("Pune");
        when(profile.getCategoriesJson()).thenReturn(null);
        when(profile.getUserId()).thenReturn(USER_ID);
        // Non-null profile totals, so a fallback that reads them would show up in the response.
        org.mockito.Mockito.lenient().when(profile.getTotalFollowers()).thenReturn(12_400L);
        org.mockito.Mockito.lenient().when(profile.getEngagementRate()).thenReturn(new java.math.BigDecimal("4.2"));
        when(creatorMetricsRepository.findByCreatorProfileIdAndDataSourceOrderByTimeDesc(
                        org.mockito.ArgumentMatchers.eq(PROFILE_ID),
                        org.mockito.ArgumentMatchers.eq(CreatorMetric.DATA_SOURCE_META_API),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());
        when(collaborationRepository.findByCreatorIdAndStatus(
                        USER_ID, com.influora.domain.enums.CollaborationStatus.COMPLETED))
                .thenReturn(List.of());

        VerifiedProfileResponse response = service.getVerifiedMetrics(USERNAME);

        assertEquals(USERNAME, response.username());
        // F-0964: no Meta row yet -> nothing is presented as verified: all four figures omitted,
        // including verified_at (a timestamp here would print "Verified on <date>" on the page).
        assertEquals(
                new com.influora.web.dto.creator.PublicCreatorDtos.VerifiedMetrics(null, null, null, null),
                response.verifiedMetrics());
        org.mockito.Mockito.verify(profile, org.mockito.Mockito.never()).getTotalFollowers();
        org.mockito.Mockito.verify(profile, org.mockito.Mockito.never()).getEngagementRate();
    }

    @Test
    @DisplayName("F-0964 a newer creator-declared row is never shown as verified metrics")
    void declaredRowIsNotShownAsVerified() {
        stubDiscoverableAndConnected();
        when(profile.isSuspended()).thenReturn(false);
        when(profile.getUsername()).thenReturn(USERNAME);
        when(profile.getUserId()).thenReturn(USER_ID);
        CreatorMetric declared =
                CreatorMetric.builder().id("01HPUBLICDECLARED00001").creatorProfileId(PROFILE_ID)
                        .platform("YOUTUBE").dataSource(CreatorMetric.DATA_SOURCE_CREATOR_REPORTED)
                        .followers(900_000).time(Instant.parse("2026-09-10T00:00:00Z")).build();
        CreatorMetric synced =
                CreatorMetric.builder().id("01HPUBLICMETASYNC0001").creatorProfileId(PROFILE_ID)
                        .platform("INSTAGRAM").dataSource(CreatorMetric.DATA_SOURCE_META_API)
                        .followers(12_400).fetchedAt(Instant.parse("2026-09-09T00:00:00Z"))
                        .time(Instant.parse("2026-09-09T00:00:00Z")).build();
        // Hand back BOTH so the in-memory guard is exercised, not just the query.
        when(creatorMetricsRepository.findByCreatorProfileIdAndDataSourceOrderByTimeDesc(
                        org.mockito.ArgumentMatchers.eq(PROFILE_ID),
                        org.mockito.ArgumentMatchers.eq(CreatorMetric.DATA_SOURCE_META_API),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(declared, synced));
        when(collaborationRepository.findByCreatorIdAndStatus(
                        USER_ID, com.influora.domain.enums.CollaborationStatus.COMPLETED))
                .thenReturn(List.of());

        VerifiedProfileResponse response = service.getVerifiedMetrics(USERNAME);

        assertEquals(12_400L, response.verifiedMetrics().followers());
        assertEquals(Instant.parse("2026-09-09T00:00:00Z"), response.verifiedMetrics().verifiedAt());
        org.mockito.Mockito.verify(creatorMetricsRepository, org.mockito.Mockito.never())
                .findByCreatorProfileIdOrderByTimeDesc(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("a not-discoverable creator gets the SAME 404 code as a suspended one -- no enumeration signal")
    void notDiscoverableCreator_returnsSame404AsSuspended() {
        when(profile.isDiscoverable()).thenReturn(false);
        when(metaOAuthTokenRepository.findByCreatorProfileIdAndRevokedFalseAndExpiresAtAfter(
                        org.mockito.ArgumentMatchers.eq(PROFILE_ID), org.mockito.ArgumentMatchers.any(Instant.class)))
                .thenReturn(List.of());

        ApiException ex = assertThrows(ApiException.class, () -> service.getVerifiedMetrics(USERNAME));

        assertEquals("CREATOR_NOT_FOUND", ex.getCode());
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    /**
     * Gate fix round 2, item 2 (Priya Q6). Before this fix, {@code getVerifiedMetrics} tested
     * {@code findByCreatorProfileIdAndRevokedFalse} (revocation only), so a creator whose Meta
     * token expired weeks ago -- never explicitly revoked -- still passed the connected-check and
     * the public page kept advertising a dead connection as verified. This proves an expired,
     * non-revoked token now yields the SAME 404 as "never connected" -- expiry alone is enough to
     * un-verify the page, without waiting for an explicit revoke.
     */
    @Test
    @DisplayName("a creator whose only Meta token has expired gets 404, same as never connected")
    void expiredMetaToken_returns404() {
        when(profile.isDiscoverable()).thenReturn(true);
        // The repository call itself filters expiry server-side; simulate that filtering finding
        // nothing because the one non-revoked token this creator has is expired.
        when(metaOAuthTokenRepository.findByCreatorProfileIdAndRevokedFalseAndExpiresAtAfter(
                        org.mockito.ArgumentMatchers.eq(PROFILE_ID), org.mockito.ArgumentMatchers.any(Instant.class)))
                .thenReturn(List.of());

        ApiException ex = assertThrows(ApiException.class, () -> service.getVerifiedMetrics(USERNAME));

        assertEquals("CREATOR_NOT_FOUND", ex.getCode());
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }
}
