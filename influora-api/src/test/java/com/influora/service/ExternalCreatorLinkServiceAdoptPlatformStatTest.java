package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.ExternalCreator;
import com.influora.domain.entity.PlatformStat;
import com.influora.domain.enums.ExternalCreatorSource;
import com.influora.repository.CreatorConnectionRequestRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.ExternalCreatorRepository;
import com.influora.repository.PlatformStatRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * F-0701 (joined-creator-not-discoverable) — the creator a brand personally recruited must actually
 * turn up when that brand searches for them.
 *
 * <p>The flow: an admin imports an Instagram handle, a brand hits Connect, the admin invites, and
 * the creator registers through the signed invite token. {@code finishLinking} then marked the
 * {@code external_creators} row JOINED and emailed the brand "they joined" — and wrote no {@code
 * platform_stats} row. The brand's Discover filter for {@code platforms=INSTAGRAM} is an EXISTS
 * subquery over exactly that table ({@code CreatorProfileSpecifications#hasPlatforms}), so the
 * creator they had just recruited was not in the results. The follower count and engagement rate
 * needed were already on the {@link ExternalCreator} row, fetched from Business Discovery, and were
 * dropped on the floor.
 *
 * <p>What this class pins, and why each assertion is load-bearing:
 *
 * <ul>
 *   <li><b>The row is written at all</b>, carrying the handle — without it nothing else matters.
 *   <li><b>{@code verified} is false.</b> The invite proves the creator controls the email an admin
 *       associated with that handle. It does not prove they own the Instagram account. The numbers
 *       are real Meta data; the identity binding is an admin's assertion, and {@code
 *       PlatformStat.verified} is read by brands as platform-confirmed ownership. Fail closed.
 *   <li><b>An existing row is never overwritten.</b> A creator who connected Meta before accepting
 *       the invite already has a better, genuinely verified row; adopting over it would downgrade
 *       real data to an admin's copy of it.
 *   <li><b>{@code totalFollowers} is rolled up.</b> {@code followersBetween} filters on {@code
 *       CreatorProfile.totalFollowers}, not on the platform row, so a brand narrowing by follower
 *       range would still lose them without this — the exact half-fix that would look correct in a
 *       platform-chip-only test.
 *   <li><b>A bare stub does not fabricate.</b> An admin import that never enriched has a null
 *       follower count; that must become a findable row with 0, never an invented number.
 *   <li><b>A failure here never blocks the join.</b> Discoverability is best-effort; the creator
 *       joining is not.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ExternalCreatorLinkServiceAdoptPlatformStatTest {

    private static final String EXTERNAL_CREATOR_ID = "01HEXTERNALCREATOR001";
    private static final String PROFILE_ID = "01HCREATORPROFILE1234";

    @Mock private ExternalCreatorRepository externalCreatorRepository;
    @Mock private CreatorConnectionRequestRepository connectionRequestRepository;
    @Mock private CreatorProfileRepository creatorProfileRepository;
    @Mock private PlatformStatRepository platformStatRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private ExternalCreatorLinkService service;

    @BeforeEach
    void setUp() {
        service =
                new ExternalCreatorLinkService(
                        externalCreatorRepository,
                        connectionRequestRepository,
                        creatorProfileRepository,
                        platformStatRepository,
                        eventPublisher);
    }

    private ExternalCreator enrichedExternalCreator() {
        return ExternalCreator.builder()
                .id(EXTERNAL_CREATOR_ID)
                .source(ExternalCreatorSource.BUSINESS_DISCOVERY)
                .igUsername("foodie.mumbai")
                .followers(184_000L)
                .engagementRate(new BigDecimal("4.60"))
                .build();
    }

    /** No open connection requests: finishLinking returns early once they are handled, so the
     *  platform row must be written BEFORE that point or an invited creator with no pending
     *  request silently gets nothing. */
    private void noOpenRequests() {
        when(connectionRequestRepository.findByExternalCreatorIdAndStatusIn(any(), any()))
                .thenReturn(List.of());
    }

    @Test
    @DisplayName(
            "linkViaVerifiedInvite: writes the INSTAGRAM platform_stats row the brand's Discover"
                    + " filter needs, carrying the handle and the Business Discovery follower count")
    void linkViaVerifiedInvite_writesThePlatformRow() {
        ExternalCreator external = enrichedExternalCreator();
        when(externalCreatorRepository.findById(EXTERNAL_CREATOR_ID)).thenReturn(Optional.of(external));
        when(platformStatRepository.findByCreatorProfileIdAndPlatform(PROFILE_ID, "INSTAGRAM"))
                .thenReturn(Optional.empty());
        when(creatorProfileRepository.findById(PROFILE_ID)).thenReturn(Optional.empty());
        noOpenRequests();

        service.linkViaVerifiedInvite(EXTERNAL_CREATOR_ID, PROFILE_ID);

        ArgumentCaptor<PlatformStat> saved = ArgumentCaptor.forClass(PlatformStat.class);
        verify(platformStatRepository).save(saved.capture());
        assertEquals("INSTAGRAM", saved.getValue().getPlatform());
        assertEquals("foodie.mumbai", saved.getValue().getHandle());
        assertEquals(184_000L, saved.getValue().getFollowers());
        assertEquals(new BigDecimal("4.60"), saved.getValue().getEngagementRate());
    }

    @Test
    @DisplayName(
            "linkViaVerifiedInvite: the adopted row is NOT verified — an invite proves email"
                    + " control, never Instagram account ownership")
    void linkViaVerifiedInvite_adoptedRowIsNeverVerified() {
        ExternalCreator external = enrichedExternalCreator();
        when(externalCreatorRepository.findById(EXTERNAL_CREATOR_ID)).thenReturn(Optional.of(external));
        when(platformStatRepository.findByCreatorProfileIdAndPlatform(PROFILE_ID, "INSTAGRAM"))
                .thenReturn(Optional.empty());
        when(creatorProfileRepository.findById(PROFILE_ID)).thenReturn(Optional.empty());
        noOpenRequests();

        service.linkViaVerifiedInvite(EXTERNAL_CREATOR_ID, PROFILE_ID);

        ArgumentCaptor<PlatformStat> saved = ArgumentCaptor.forClass(PlatformStat.class);
        verify(platformStatRepository).save(saved.capture());
        assertFalse(
                saved.getValue().isVerified(),
                "only a Meta sync the creator themselves authorised may set this");
    }

    @Test
    @DisplayName(
            "linkViaVerifiedInvite: a creator who already connected Meta keeps their real row —"
                    + " an admin's copy never overwrites platform-verified data")
    void linkViaVerifiedInvite_neverOverwritesAnExistingRow() {
        ExternalCreator external = enrichedExternalCreator();
        when(externalCreatorRepository.findById(EXTERNAL_CREATOR_ID)).thenReturn(Optional.of(external));
        PlatformStat existing =
                PlatformStat.builder()
                        .id("01HPLATFORMSTAT000001")
                        .creatorProfileId(PROFILE_ID)
                        .platform("INSTAGRAM")
                        .handle("foodie.mumbai")
                        .followers(190_500L)
                        .verified(true)
                        .build();
        when(platformStatRepository.findByCreatorProfileIdAndPlatform(PROFILE_ID, "INSTAGRAM"))
                .thenReturn(Optional.of(existing));
        noOpenRequests();

        service.linkViaVerifiedInvite(EXTERNAL_CREATOR_ID, PROFILE_ID);

        verify(platformStatRepository, never()).save(any());
    }

    @Test
    @DisplayName(
            "linkViaVerifiedInvite: rolls totalFollowers up onto the profile, so the brand's"
                    + " follower-range filter finds them too — not just the platform chip")
    void linkViaVerifiedInvite_rollsUpTotalFollowers() {
        ExternalCreator external = enrichedExternalCreator();
        when(externalCreatorRepository.findById(EXTERNAL_CREATOR_ID)).thenReturn(Optional.of(external));
        when(platformStatRepository.findByCreatorProfileIdAndPlatform(PROFILE_ID, "INSTAGRAM"))
                .thenReturn(Optional.empty());

        CreatorProfile profile = CreatorProfile.newForUser(PROFILE_ID, "01HCREATORUSER1234567", "Foodie Mumbai");
        when(creatorProfileRepository.findById(PROFILE_ID)).thenReturn(Optional.of(profile));
        PlatformStat written =
                PlatformStat.builder()
                        .id("01HPLATFORMSTAT000002")
                        .creatorProfileId(PROFILE_ID)
                        .platform("INSTAGRAM")
                        .handle("foodie.mumbai")
                        .followers(184_000L)
                        .verified(false)
                        .build();
        when(platformStatRepository.findByCreatorProfileId(PROFILE_ID)).thenReturn(List.of(written));
        noOpenRequests();

        service.linkViaVerifiedInvite(EXTERNAL_CREATOR_ID, PROFILE_ID);

        verify(creatorProfileRepository).save(profile);
        assertEquals(
                184_000L,
                profile.getTotalFollowers(),
                "CreatorProfileSpecifications#followersBetween reads totalFollowers, not the"
                        + " platform row — without this the creator is filtered out by any"
                        + " follower range a brand sets");
    }

    @Test
    @DisplayName(
            "linkViaVerifiedInvite: an unenriched ADMIN_IMPORT stub becomes a findable row with 0"
                    + " followers, never an invented number")
    void linkViaVerifiedInvite_bareStubDoesNotFabricateFollowers() {
        ExternalCreator stub =
                ExternalCreator.builder()
                        .id(EXTERNAL_CREATOR_ID)
                        .source(ExternalCreatorSource.ADMIN_IMPORT)
                        .igUsername("newcreator")
                        .build();
        when(externalCreatorRepository.findById(EXTERNAL_CREATOR_ID)).thenReturn(Optional.of(stub));
        when(platformStatRepository.findByCreatorProfileIdAndPlatform(PROFILE_ID, "INSTAGRAM"))
                .thenReturn(Optional.empty());
        when(creatorProfileRepository.findById(PROFILE_ID)).thenReturn(Optional.empty());
        noOpenRequests();

        service.linkViaVerifiedInvite(EXTERNAL_CREATOR_ID, PROFILE_ID);

        ArgumentCaptor<PlatformStat> saved = ArgumentCaptor.forClass(PlatformStat.class);
        verify(platformStatRepository).save(saved.capture());
        assertEquals("newcreator", saved.getValue().getHandle());
        assertEquals(0L, saved.getValue().getFollowers(), "unknown is 0, never a guess");
    }

    @Test
    @DisplayName(
            "linkViaVerifiedInvite: a platform_stats write failure never stops the creator from"
                    + " joining — discoverability is best-effort, joining is not")
    void linkViaVerifiedInvite_platformStatFailureDoesNotBlockTheJoin() {
        ExternalCreator external = enrichedExternalCreator();
        when(externalCreatorRepository.findById(EXTERNAL_CREATOR_ID)).thenReturn(Optional.of(external));
        when(platformStatRepository.findByCreatorProfileIdAndPlatform(PROFILE_ID, "INSTAGRAM"))
                .thenThrow(new RuntimeException("platform_stats unavailable"));
        noOpenRequests();

        service.linkViaVerifiedInvite(EXTERNAL_CREATOR_ID, PROFILE_ID);

        // The JOINED flip is the thing the brand was promised; it must survive.
        verify(externalCreatorRepository).save(external);
        assertEquals(PROFILE_ID, external.getLinkedCreatorProfileId());
    }
}
