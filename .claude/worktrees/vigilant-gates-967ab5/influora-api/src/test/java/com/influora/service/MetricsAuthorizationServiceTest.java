package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.MetaOAuthToken;
import com.influora.repository.MetaOAuthTokenRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link MetricsAuthorizationService} — closes Kabir's workspace-isolation finding
 * (SHARED_CONTEXT.md "KABIR -> ARJUN | Workspace-isolation review:
 * CreatorMetricsRepository/MediaMetricsRepository (Phase 2)"): {@code CreatorMetricsRepository} /
 * {@code MediaMetricsRepository} had a javadoc-only "callers must verify" contract with zero
 * enforcement code. This test proves the enforcement actually exists.
 */
@ExtendWith(MockitoExtension.class)
class MetricsAuthorizationServiceTest {

    private static final String WORKSPACE_ID = "01HWORKSPACE12345678A";
    private static final String OTHER_WORKSPACE_ID = "01HWORKSPACE99999999Z";
    private static final String CREATOR_PROFILE_ID = "01HCREATORPROFILE1234";

    @Mock private MetaOAuthTokenRepository metaOAuthTokenRepository;

    private MetricsAuthorizationService service;

    @Test
    @DisplayName(
            "resolveAuthorizedCreatorProfileId: rejects a workspace/creator pair with no linking token row")
    void testRejectsUnlinkedWorkspaceAndCreator() {
        service = new MetricsAuthorizationService(metaOAuthTokenRepository);
        when(metaOAuthTokenRepository.findByWorkspaceIdAndCreatorProfileIdAndRevokedFalse(
                        OTHER_WORKSPACE_ID, CREATOR_PROFILE_ID))
                .thenReturn(Optional.empty());

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
        service = new MetricsAuthorizationService(metaOAuthTokenRepository);
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
    }
}
