package com.influora.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.config.CreatorCopilotProperties;
import com.influora.domain.entity.MetaOAuthToken;
import com.influora.integration.meta.client.InstagramInsightsClient;
import com.influora.integration.meta.dto.InstagramMediaResponse;
import com.influora.integration.meta.dto.InstagramMediaResponse.MediaItem;
import com.influora.integration.meta.exception.MetaApiException;
import com.influora.integration.meta.oauth.MetaTokenStorage;
import com.influora.repository.CreatorCaptionCacheRepository;
import com.influora.repository.MetaOAuthTokenRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * CR-113 — {@code CreatorCaptionSyncJob} had no test file at all, despite being one of the two
 * jobs {@code CreatorThemeTaggingJob}'s own javadoc depends on to have populated {@code
 * creator_captions} before it runs. Covers the resilience discipline the class javadoc claims
 * (per-creator AND per-item try/catch; a no-linked-account token is a skip, not a failure) and the
 * dedup/blank-caption skip in {@code persistIfNew}, mirroring the Mockito-only, no-real-DB
 * convention {@code PlatformStatsAggregationJobTest}/{@code MetricsPollingJobTest} already use.
 */
@ExtendWith(MockitoExtension.class)
class CreatorCaptionSyncJobTest {

    private static final String CREATOR_ID_1 = "01HCREATOR0000000001AB";
    private static final String CREATOR_ID_2 = "01HCREATOR0000000002AB";
    private static final String IG_ID_1 = "17841400000000001";
    private static final String IG_ID_2 = "17841400000000002";
    private static final String ACCESS_TOKEN = "decrypted-access-token";

    @Mock private MetaOAuthTokenRepository tokenRepository;
    @Mock private MetaTokenStorage tokenStorage;
    @Mock private InstagramInsightsClient instagramClient;
    @Mock private CreatorCaptionCacheRepository captionRepository;

    private CreatorCopilotProperties props;
    private CreatorCaptionSyncJob job;

    @BeforeEach
    void setUp() {
        props = new CreatorCopilotProperties();
        props.setEnabled(true);
        job = new CreatorCaptionSyncJob(tokenRepository, tokenStorage, instagramClient, captionRepository, props);
    }

    private static MetaOAuthToken tokenFor(String creatorProfileId, String igBusinessAccountId) {
        return MetaOAuthToken.builder()
                .creatorProfileId(creatorProfileId)
                .igBusinessAccountId(igBusinessAccountId)
                .build();
    }

    @Test
    @DisplayName("syncCaptions: disabled (influora.creator-copilot.enabled=false) does nothing")
    void syncCaptions_disabled_doesNothing() {
        props.setEnabled(false);

        job.syncCaptions();

        verify(tokenRepository, never()).findByWorkspaceIdIsNullAndRevokedFalseAndExpiresAtAfter(any());
    }

    @Test
    @DisplayName("syncCaptions: a token with no igBusinessAccountId is skipped, never fetched from Meta")
    void syncCaptions_noLinkedAccount_skipsWithoutFetching() {
        when(tokenRepository.findByWorkspaceIdIsNullAndRevokedFalseAndExpiresAtAfter(any()))
                .thenReturn(List.of(tokenFor(CREATOR_ID_1, null)));

        job.syncCaptions();

        verify(tokenStorage, never()).getValidCreatorToken(anyString());
        verify(instagramClient, never()).getMedia(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("syncCaptions: a new, captioned media item is inserted into the cache")
    void syncCaptions_newCaptionedItem_isInserted() {
        when(tokenRepository.findByWorkspaceIdIsNullAndRevokedFalseAndExpiresAtAfter(any()))
                .thenReturn(List.of(tokenFor(CREATOR_ID_1, IG_ID_1)));
        when(tokenStorage.getValidCreatorToken(CREATOR_ID_1)).thenReturn(Optional.of(ACCESS_TOKEN));
        MediaItem item = new MediaItem("media_1", "A real caption", "IMAGE", null, null,
                "2026-08-01T12:00:00+0000", 10L, 2L);
        when(instagramClient.getMedia(IG_ID_1, ACCESS_TOKEN, props.getCaptionSyncMediaLimit()))
                .thenReturn(new InstagramMediaResponse(List.of(item), null));
        when(captionRepository.findByCreatorProfileIdAndIgMediaId(CREATOR_ID_1, "media_1"))
                .thenReturn(Optional.empty());

        job.syncCaptions();

        verify(captionRepository).save(any());
    }

    @Test
    @DisplayName("syncCaptions: a blank caption is never persisted")
    void syncCaptions_blankCaption_isSkipped() {
        when(tokenRepository.findByWorkspaceIdIsNullAndRevokedFalseAndExpiresAtAfter(any()))
                .thenReturn(List.of(tokenFor(CREATOR_ID_1, IG_ID_1)));
        when(tokenStorage.getValidCreatorToken(CREATOR_ID_1)).thenReturn(Optional.of(ACCESS_TOKEN));
        MediaItem blankItem = new MediaItem("media_1", "  ", "IMAGE", null, null, null, null, null);
        when(instagramClient.getMedia(eq(IG_ID_1), eq(ACCESS_TOKEN), anyInt()))
                .thenReturn(new InstagramMediaResponse(List.of(blankItem), null));

        job.syncCaptions();

        verify(captionRepository, never()).save(any());
        verify(captionRepository, never()).findByCreatorProfileIdAndIgMediaId(anyString(), anyString());
    }

    @Test
    @DisplayName("syncCaptions: an already-cached media item is never re-inserted (dedup)")
    void syncCaptions_alreadyCached_isSkipped() {
        when(tokenRepository.findByWorkspaceIdIsNullAndRevokedFalseAndExpiresAtAfter(any()))
                .thenReturn(List.of(tokenFor(CREATOR_ID_1, IG_ID_1)));
        when(tokenStorage.getValidCreatorToken(CREATOR_ID_1)).thenReturn(Optional.of(ACCESS_TOKEN));
        MediaItem item = new MediaItem("media_1", "Already cached", "IMAGE", null, null, null, null, null);
        when(instagramClient.getMedia(eq(IG_ID_1), eq(ACCESS_TOKEN), anyInt()))
                .thenReturn(new InstagramMediaResponse(List.of(item), null));
        when(captionRepository.findByCreatorProfileIdAndIgMediaId(CREATOR_ID_1, "media_1"))
                .thenReturn(Optional.of(com.influora.domain.entity.CreatorCaptionCache.builder()
                        .id("existing")
                        .creatorProfileId(CREATOR_ID_1)
                        .igMediaId("media_1")
                        .captionText("Already cached")
                        .build()));

        job.syncCaptions();

        verify(captionRepository, never()).save(any());
    }

    @Test
    @DisplayName(
            "syncCaptions: a Meta API failure for one creator does not abort the batch — the next"
                    + " creator is still processed")
    void syncCaptions_oneCreatorMetaFailure_doesNotAbortBatch() {
        when(tokenRepository.findByWorkspaceIdIsNullAndRevokedFalseAndExpiresAtAfter(any()))
                .thenReturn(List.of(tokenFor(CREATOR_ID_1, IG_ID_1), tokenFor(CREATOR_ID_2, IG_ID_2)));
        when(tokenStorage.getValidCreatorToken(CREATOR_ID_1)).thenReturn(Optional.of(ACCESS_TOKEN));
        when(tokenStorage.getValidCreatorToken(CREATOR_ID_2)).thenReturn(Optional.of(ACCESS_TOKEN));
        when(instagramClient.getMedia(eq(IG_ID_1), eq(ACCESS_TOKEN), anyInt()))
                .thenThrow(new MetaApiException("rate limited"));
        MediaItem item = new MediaItem("media_2", "Creator 2's caption", "IMAGE", null, null, null, null, null);
        when(instagramClient.getMedia(eq(IG_ID_2), eq(ACCESS_TOKEN), anyInt()))
                .thenReturn(new InstagramMediaResponse(List.of(item), null));
        when(captionRepository.findByCreatorProfileIdAndIgMediaId(CREATOR_ID_2, "media_2"))
                .thenReturn(Optional.empty());

        job.syncCaptions();

        // Creator 1's failure did not prevent creator 2's item from being saved.
        ArgumentCaptor<com.influora.domain.entity.CreatorCaptionCache> saved =
                ArgumentCaptor.forClass(com.influora.domain.entity.CreatorCaptionCache.class);
        verify(captionRepository, times(1)).save(saved.capture());
        assertEquals(CREATOR_ID_2, saved.getValue().getCreatorProfileId());
    }

    @Test
    @DisplayName(
            "syncCaptions: a failure persisting one media item does not prevent a later item for the"
                    + " same creator from being saved")
    void syncCaptions_oneItemPersistFailure_doesNotAbortRemainingItems() {
        when(tokenRepository.findByWorkspaceIdIsNullAndRevokedFalseAndExpiresAtAfter(any()))
                .thenReturn(List.of(tokenFor(CREATOR_ID_1, IG_ID_1)));
        when(tokenStorage.getValidCreatorToken(CREATOR_ID_1)).thenReturn(Optional.of(ACCESS_TOKEN));
        MediaItem bad = new MediaItem("media_bad", "Bad item", "IMAGE", null, null, null, null, null);
        MediaItem good = new MediaItem("media_good", "Good item", "IMAGE", null, null, null, null, null);
        when(instagramClient.getMedia(eq(IG_ID_1), eq(ACCESS_TOKEN), anyInt()))
                .thenReturn(new InstagramMediaResponse(List.of(bad, good), null));
        when(captionRepository.findByCreatorProfileIdAndIgMediaId(CREATOR_ID_1, "media_bad"))
                .thenThrow(new RuntimeException("db blip"));
        when(captionRepository.findByCreatorProfileIdAndIgMediaId(CREATOR_ID_1, "media_good"))
                .thenReturn(Optional.empty());

        job.syncCaptions();

        verify(captionRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("syncCaptions: an unresolvable/expired token (empty Optional) is a skip, never a fetch")
    void syncCaptions_noValidToken_skipsWithoutFetching() {
        when(tokenRepository.findByWorkspaceIdIsNullAndRevokedFalseAndExpiresAtAfter(any()))
                .thenReturn(List.of(tokenFor(CREATOR_ID_1, IG_ID_1)));
        when(tokenStorage.getValidCreatorToken(CREATOR_ID_1)).thenReturn(Optional.empty());

        job.syncCaptions();

        verify(instagramClient, never()).getMedia(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("syncCaptions: captionSyncMaxCreatorsPerRun caps the batch to that many creators")
    void syncCaptions_maxCreatorsPerRun_capsBatch() {
        props.setCaptionSyncMaxCreatorsPerRun(1);
        when(tokenRepository.findByWorkspaceIdIsNullAndRevokedFalseAndExpiresAtAfter(any()))
                .thenReturn(List.of(tokenFor(CREATOR_ID_1, IG_ID_1), tokenFor(CREATOR_ID_2, IG_ID_2)));
        when(tokenStorage.getValidCreatorToken(CREATOR_ID_1)).thenReturn(Optional.of(ACCESS_TOKEN));
        when(instagramClient.getMedia(eq(IG_ID_1), eq(ACCESS_TOKEN), anyInt()))
                .thenReturn(new InstagramMediaResponse(List.of(), null));

        job.syncCaptions();

        verify(tokenStorage, never()).getValidCreatorToken(CREATOR_ID_2);
        verify(instagramClient, never()).getMedia(eq(IG_ID_2), anyString(), anyInt());
    }
}
