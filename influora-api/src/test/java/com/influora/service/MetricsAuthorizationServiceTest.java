package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.MetaOAuthToken;
import com.influora.domain.enums.CollaborationSource;
import com.influora.domain.enums.CollaborationStatus;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.MetaOAuthTokenRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link MetricsAuthorizationService} — closes Kabir's workspace-isolation finding
 * (SHARED_CONTEXT.md "KABIR -> ARJUN | Workspace-isolation review:
 * CreatorMetricsRepository/MediaMetricsRepository (Phase 2)"): {@code CreatorMetricsRepository} /
 * {@code MediaMetricsRepository} had a javadoc-only "callers must verify" contract with zero
 * enforcement code. This test proves the enforcement actually exists.
 *
 * <p>F-0901: the Meta pairing alone never opened for a marketplace creator (their own connection is
 * stored with a NULL workspace), so a collaboration the CREATOR agreed to is the second grant. An
 * invitation or offer the creator never agreed to must still be refused.
 */
@ExtendWith(MockitoExtension.class)
class MetricsAuthorizationServiceTest {

    private static final String WORKSPACE_ID = "01HWORKSPACE12345678A";
    private static final String OTHER_WORKSPACE_ID = "01HWORKSPACE99999999Z";
    private static final String CREATOR_PROFILE_ID = "01HCREATORPROFILE1234";
    private static final String CREATOR_USER_ID = "01HCREATORUSER0000001";

    @Mock private MetaOAuthTokenRepository metaOAuthTokenRepository;
    @Mock private CreatorProfileRepository creatorProfileRepository;
    @Mock private CollaborationRepository collaborationRepository;

    private MetricsAuthorizationService service;

    @BeforeEach
    void setUp() {
        service =
                new MetricsAuthorizationService(
                        metaOAuthTokenRepository, creatorProfileRepository, collaborationRepository);
    }

    private static Collaboration collaboration(CollaborationSource source, CollaborationStatus status) {
        Collaboration c = mock(Collaboration.class);
        lenient().when(c.getSource()).thenReturn(source);
        lenient().when(c.getStatus()).thenReturn(status);
        return c;
    }

    private void noMetaPairing(String workspaceId) {
        when(metaOAuthTokenRepository.findByWorkspaceIdAndCreatorProfileIdAndRevokedFalse(
                        workspaceId, CREATOR_PROFILE_ID))
                .thenReturn(Optional.empty());
    }

    private void workspaceHas(String workspaceId, Collaboration... collaborations) {
        CreatorProfile profile = mock(CreatorProfile.class);
        when(profile.getUserId()).thenReturn(CREATOR_USER_ID);
        when(creatorProfileRepository.findById(CREATOR_PROFILE_ID)).thenReturn(Optional.of(profile));
        when(collaborationRepository.findByWorkspaceIdAndCreatorId(workspaceId, CREATOR_USER_ID))
                .thenReturn(List.of(collaborations));
    }

    @Test
    @DisplayName(
            "resolveAuthorizedCreatorProfileId: rejects a workspace/creator pair with no linking token row")
    void testRejectsUnlinkedWorkspaceAndCreator() {
        noMetaPairing(OTHER_WORKSPACE_ID);
        workspaceHas(OTHER_WORKSPACE_ID);

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.resolveAuthorizedCreatorProfileId(
                                        OTHER_WORKSPACE_ID, CREATOR_PROFILE_ID));

        assertEquals("FORBIDDEN", ex.getCode());
    }

    @Test
    @DisplayName(
            "resolveAuthorizedCreatorProfileId: accepts a workspace/creator pair with a valid, non-revoked link")
    void testAcceptsValidNonRevokedLink() {
        MetaOAuthToken token =
                MetaOAuthToken.builder()
                        .id("01HTOKEN1234567890AB")
                        .workspaceId(WORKSPACE_ID)
                        .creatorProfileId(CREATOR_PROFILE_ID)
                        .encryptedAccessToken("ciphertext")
                        .expiresAt(Instant.now().plusSeconds(3600))
                        .build();
        when(metaOAuthTokenRepository.findByWorkspaceIdAndCreatorProfileIdAndRevokedFalse(
                        WORKSPACE_ID, CREATOR_PROFILE_ID))
                .thenReturn(Optional.of(token));

        String resolved =
                service.resolveAuthorizedCreatorProfileId(WORKSPACE_ID, CREATOR_PROFILE_ID);

        assertEquals(CREATOR_PROFILE_ID, resolved);
        // The pairing is sufficient on its own; the collaboration path is never consulted.
        verify(collaborationRepository, never()).findByWorkspaceIdAndCreatorId(anyString(), anyString());
    }

    // ── F-0901: a collaboration the creator agreed to ──────────────────────────────────────

    @ParameterizedTest(name = "INVITATION in {0} -> allowed (creator agreed the terms)")
    @EnumSource(
            value = CollaborationStatus.class,
            names = {
                "TERMS_AGREED",
                "CONTRACT_PENDING",
                "CONTRACTED",
                "IN_PROGRESS",
                "REVIEW_PENDING",
                "REVISION_REQUESTED",
                "COMPLETED",
                "DISPUTED"
            })
    void invitationTheCreatorAgreedToIsAllowed(CollaborationStatus status) {
        noMetaPairing(WORKSPACE_ID);
        workspaceHas(WORKSPACE_ID, collaboration(CollaborationSource.INVITATION, status));

        assertEquals(
                CREATOR_PROFILE_ID,
                service.resolveAuthorizedCreatorProfileId(WORKSPACE_ID, CREATOR_PROFILE_ID));
    }

    @ParameterizedTest(name = "INVITATION in {0} -> refused (creator never agreed)")
    @EnumSource(
            value = CollaborationStatus.class,
            names = {"INVITED", "APPLIED", "SHORTLISTED", "IN_NEGOTIATION", "CANCELLED"})
    void invitationTheCreatorNeverAgreedToIsRefused(CollaborationStatus status) {
        noMetaPairing(WORKSPACE_ID);
        workspaceHas(WORKSPACE_ID, collaboration(CollaborationSource.INVITATION, status));

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> service.resolveAuthorizedCreatorProfileId(WORKSPACE_ID, CREATOR_PROFILE_ID));
        assertEquals("FORBIDDEN", ex.getCode());
    }

    @ParameterizedTest(name = "APPLICATION in {0} -> allowed (the creator applied)")
    @EnumSource(value = CollaborationStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "CANCELLED")
    void applicationIsAllowedInEveryLiveState(CollaborationStatus status) {
        noMetaPairing(WORKSPACE_ID);
        workspaceHas(WORKSPACE_ID, collaboration(CollaborationSource.APPLICATION, status));

        assertEquals(
                CREATOR_PROFILE_ID,
                service.resolveAuthorizedCreatorProfileId(WORKSPACE_ID, CREATOR_PROFILE_ID));
    }

    @Test
    @DisplayName("APPLICATION that was CANCELLED -> refused")
    void cancelledApplicationIsRefused() {
        noMetaPairing(WORKSPACE_ID);
        workspaceHas(
                WORKSPACE_ID, collaboration(CollaborationSource.APPLICATION, CollaborationStatus.CANCELLED));

        assertThrows(
                ApiException.class,
                () -> service.resolveAuthorizedCreatorProfileId(WORKSPACE_ID, CREATOR_PROFILE_ID));
    }

    @Test
    @DisplayName("an unanswered brand offer beside a cancelled one is still refused; any ONE agreed row is enough")
    void onlyAnAgreedRowGrants() {
        noMetaPairing(WORKSPACE_ID);
        workspaceHas(
                WORKSPACE_ID,
                collaboration(CollaborationSource.INVITATION, CollaborationStatus.IN_NEGOTIATION),
                collaboration(CollaborationSource.INVITATION, CollaborationStatus.CANCELLED));
        assertThrows(
                ApiException.class,
                () -> service.resolveAuthorizedCreatorProfileId(WORKSPACE_ID, CREATOR_PROFILE_ID));
    }

    @Test
    @DisplayName("one agreed collaboration among refused ones grants access")
    void oneAgreedRowAmongOthersGrants() {
        noMetaPairing(WORKSPACE_ID);
        workspaceHas(
                WORKSPACE_ID,
                collaboration(CollaborationSource.INVITATION, CollaborationStatus.INVITED),
                collaboration(CollaborationSource.INVITATION, CollaborationStatus.CONTRACTED));

        assertEquals(
                CREATOR_PROFILE_ID,
                service.resolveAuthorizedCreatorProfileId(WORKSPACE_ID, CREATOR_PROFILE_ID));
    }

    @Test
    @DisplayName("the collaboration lookup is scoped to the ASKING workspace — another brand's deal grants nothing")
    void collaborationLookupIsWorkspaceScoped() {
        noMetaPairing(OTHER_WORKSPACE_ID);
        // The creator has an agreed deal, but only with WORKSPACE_ID, not the asking workspace.
        CreatorProfile profile = mock(CreatorProfile.class);
        when(profile.getUserId()).thenReturn(CREATOR_USER_ID);
        when(creatorProfileRepository.findById(CREATOR_PROFILE_ID)).thenReturn(Optional.of(profile));
        Collaboration otherBrandsDeal =
                collaboration(CollaborationSource.INVITATION, CollaborationStatus.CONTRACTED);
        lenient()
                .when(collaborationRepository.findByWorkspaceIdAndCreatorId(WORKSPACE_ID, CREATOR_USER_ID))
                .thenReturn(List.of(otherBrandsDeal));
        when(collaborationRepository.findByWorkspaceIdAndCreatorId(OTHER_WORKSPACE_ID, CREATOR_USER_ID))
                .thenReturn(List.of());

        assertThrows(
                ApiException.class,
                () -> service.resolveAuthorizedCreatorProfileId(OTHER_WORKSPACE_ID, CREATOR_PROFILE_ID));
    }

    @Test
    @DisplayName("an unknown creator profile id is refused without a collaboration query")
    void unknownProfileIsRefused() {
        noMetaPairing(WORKSPACE_ID);
        when(creatorProfileRepository.findById(CREATOR_PROFILE_ID)).thenReturn(Optional.empty());

        assertThrows(
                ApiException.class,
                () -> service.resolveAuthorizedCreatorProfileId(WORKSPACE_ID, CREATOR_PROFILE_ID));
        verify(collaborationRepository, never()).findByWorkspaceIdAndCreatorId(any(), any());
    }

    @Test
    @DisplayName("the agreed-status set is exactly TERMS_AGREED onward plus DISPUTED, never CANCELLED")
    void agreedStatusSetIsExact() {
        for (CollaborationStatus status : CollaborationStatus.values()) {
            boolean agreed =
                    MetricsAuthorizationService.creatorAgreed(
                            collaboration(CollaborationSource.INVITATION, status));
            switch (status) {
                case TERMS_AGREED, CONTRACT_PENDING, CONTRACTED, IN_PROGRESS, REVIEW_PENDING,
                        REVISION_REQUESTED, COMPLETED, DISPUTED -> assertTrue(agreed, status.name());
                default -> assertFalse(agreed, status.name());
            }
        }
    }
}
