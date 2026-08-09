package com.influora.service.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.Deliverable;
import com.influora.domain.entity.DeliverableMetric;
import com.influora.domain.entity.MetaOAuthToken;
import com.influora.domain.enums.DeliverableStatus;
import com.influora.domain.enums.DeliverableType;
import com.influora.integration.meta.client.InstagramInsightsClient;
import com.influora.integration.meta.dto.InstagramInsightsResponse;
import com.influora.integration.meta.dto.InstagramMediaResponse;
import com.influora.integration.meta.exception.MetaRateLimitException;
import com.influora.integration.meta.oauth.MetaTokenStorage;
import com.influora.integration.meta.service.MetaRateLimitTracker;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.DeliverableMetricRepository;
import com.influora.repository.DeliverableRepository;
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
 * DPF-6 unit tests — {@link DeliverableVerificationService} against a mocked platform client
 * (this build cannot hit live Meta/YouTube APIs — see task's live-test caveat). Covers the 5
 * required scenarios: verified-happy-path, private-post fallback, token-missing fallback,
 * rate-limit fallback, encoded-bracket skip.
 */
@ExtendWith(MockitoExtension.class)
class DeliverableVerificationServiceTest {

    private static final String DELIVERABLE_ID = "01HDELIVERABLE1234567";
    private static final String COLLAB_ID = "01HCOLLAB12345678901";
    private static final String CAMPAIGN_ID = "01HCAMPAIGN123456789";
    private static final String MILESTONE_ID = "01HMILESTONE1234567890";
    private static final String CREATOR_PROFILE_ID = "01HCREATORPROFILE1234";
    private static final String CREATOR_USER_ID = "01HCREATORUSER1234567";
    private static final String WORKSPACE_ID = "01HWORKSPACE123456789";
    private static final String ACCESS_TOKEN = "decrypted-meta-token";
    private static final String IG_SHORTCODE = "ABC123xyz";
    private static final String POST_URL = "https://www.instagram.com/p/" + IG_SHORTCODE + "/";
    // CR-99/F-0113 regression coverage: Meta's Graph API takes this numeric IG Business Account
    // ID in the request path, never the internal ULID CREATOR_PROFILE_ID above.
    private static final String IG_BUSINESS_ACCOUNT_ID = "17841400000000031";

    @Mock private DeliverableRepository deliverableRepository;
    @Mock private DeliverableMetricRepository deliverableMetricRepository;
    @Mock private CollaborationRepository collaborationRepository;
    @Mock private MetaOAuthTokenRepository metaOAuthTokenRepository;
    @Mock private MetaTokenStorage metaTokenStorage;
    @Mock private InstagramInsightsClient instagramInsightsClient;
    @Mock private MetaRateLimitTracker rateLimitTracker;

    private DeliverableVerificationService service;

    @BeforeEach
    void setUp() {
        service =
                new DeliverableVerificationService(
                        deliverableRepository,
                        deliverableMetricRepository,
                        collaborationRepository,
                        metaOAuthTokenRepository,
                        metaTokenStorage,
                        instagramInsightsClient,
                        rateLimitTracker);
    }

    private static Deliverable postedDeliverable(String postUrl) {
        Deliverable d =
                Deliverable.builder()
                        .id(DELIVERABLE_ID)
                        .collaborationId(COLLAB_ID)
                        .creatorProfileId(CREATOR_PROFILE_ID)
                        .milestoneId(MILESTONE_ID)
                        .slotIndex(1)
                        .type(DeliverableType.INSTAGRAM_REEL)
                        .title("Workout Reel 1")
                        .status(DeliverableStatus.APPROVED)
                        .build();
        d.applyMarkPosted(postUrl);
        return d;
    }

    private static MetaOAuthToken activeTokenRow() {
        return activeTokenRow(IG_BUSINESS_ACCOUNT_ID);
    }

    private static MetaOAuthToken activeTokenRow(String igBusinessAccountId) {
        return MetaOAuthToken.builder()
                .id("01HTOKEN1234567890AB")
                .workspaceId(WORKSPACE_ID)
                .creatorProfileId(CREATOR_PROFILE_ID)
                .igBusinessAccountId(igBusinessAccountId)
                .encryptedAccessToken("ignored-ciphertext")
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }

    private void stubHappyPathTokenAndRateLimit() {
        when(metaOAuthTokenRepository.findFirstByCreatorProfileIdAndRevokedFalseOrderByCreatedAtAsc(
                        CREATOR_PROFILE_ID))
                .thenReturn(Optional.of(activeTokenRow()));
        when(metaTokenStorage.getValidToken(WORKSPACE_ID, CREATOR_PROFILE_ID))
                .thenReturn(Optional.of(ACCESS_TOKEN));
        lenient().when(rateLimitTracker.getCurrentUsage(CREATOR_PROFILE_ID)).thenReturn(0);
    }

    private static InstagramMediaResponse mediaListWithMatch() {
        InstagramMediaResponse.MediaItem item =
                new InstagramMediaResponse.MediaItem(
                        "17900000000000001",
                        "caption",
                        "IMAGE",
                        "https://cdn.example/media.jpg",
                        "https://www.instagram.com/p/" + IG_SHORTCODE + "/",
                        "2026-07-10T00:00:00+0000",
                        10L,
                        2L);
        return new InstagramMediaResponse(List.of(item), null);
    }

    private static InstagramInsightsResponse insightsWith(long reach, long impressions, long engagement) {
        return new InstagramInsightsResponse(
                List.of(
                        metric("reach", reach),
                        metric("impressions", impressions),
                        metric("engagement", engagement)));
    }

    private static InstagramInsightsResponse.InsightMetric metric(String name, long value) {
        return new InstagramInsightsResponse.InsightMetric(
                name,
                "lifetime",
                name,
                name,
                List.of(new InstagramInsightsResponse.InsightValue(value, null)));
    }

    // ------------------------------------------------------------------------------------------
    // 1. Verified happy path
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("verified happy path: fetches media + insights, persists PLATFORM_VERIFIED, promotes deliverable to VERIFIED")
    void verifiedHappyPath() {
        Deliverable deliverable = postedDeliverable(POST_URL);
        stubHappyPathTokenAndRateLimit();
        when(instagramInsightsClient.getMedia(IG_BUSINESS_ACCOUNT_ID, ACCESS_TOKEN, 50))
                .thenReturn(mediaListWithMatch());
        when(instagramInsightsClient.getMediaInsights("17900000000000001", ACCESS_TOKEN, CREATOR_PROFILE_ID))
                .thenReturn(insightsWith(5000, 8000, 300));
        when(deliverableMetricRepository.findByMilestoneId(MILESTONE_ID)).thenReturn(Optional.empty());
        when(collaborationRepository.findById(COLLAB_ID))
                .thenReturn(Optional.of(Collaboration.invite(COLLAB_ID, CAMPAIGN_ID, CREATOR_USER_ID, null, "INR")));

        DeliverableVerificationService.Outcome outcome = service.verify(deliverable);

        assertEquals(DeliverableVerificationService.Outcome.VERIFIED, outcome);
        assertEquals(DeliverableStatus.VERIFIED, deliverable.getStatus());

        ArgumentCaptor<DeliverableMetric> metricCaptor = ArgumentCaptor.forClass(DeliverableMetric.class);
        verify(deliverableMetricRepository).save(metricCaptor.capture());
        DeliverableMetric saved = metricCaptor.getValue();
        assertEquals(DeliverableMetric.SOURCE_PLATFORM_VERIFIED, saved.getSource());
        assertEquals(5000L, saved.getReach());
        assertEquals(8000L, saved.getImpressions());
        assertEquals(300L, saved.getEngagements());
        assertEquals("17900000000000001", saved.getPlatformMediaId());

        verify(deliverableRepository).save(deliverable);
    }

    @Test
    @DisplayName("re-verifying an already-verified deliverable refreshes the same row (idempotent, no double count)")
    void reVerificationOverwritesSameRowRatherThanAppending() {
        Deliverable deliverable = postedDeliverable(POST_URL);
        stubHappyPathTokenAndRateLimit();
        when(instagramInsightsClient.getMedia(IG_BUSINESS_ACCOUNT_ID, ACCESS_TOKEN, 50))
                .thenReturn(mediaListWithMatch());
        when(instagramInsightsClient.getMediaInsights("17900000000000001", ACCESS_TOKEN, CREATOR_PROFILE_ID))
                .thenReturn(insightsWith(9000, 12000, 500));

        DeliverableMetric existingVerifiedRow =
                DeliverableMetric.builder()
                        .id("01HMETRIC1234567890AB")
                        .milestoneId(MILESTONE_ID)
                        .collaborationId(COLLAB_ID)
                        .reportedByCreatorId(CREATOR_USER_ID)
                        .build();
        existingVerifiedRow.applyVerifiedReport(5000L, 8000L, 300L, "17900000000000001");
        when(deliverableMetricRepository.findByMilestoneId(MILESTONE_ID))
                .thenReturn(Optional.of(existingVerifiedRow));

        DeliverableVerificationService.Outcome outcome = service.verify(deliverable);

        assertEquals(DeliverableVerificationService.Outcome.VERIFIED, outcome);
        verify(deliverableMetricRepository, times(1)).save(existingVerifiedRow);
        assertEquals(9000L, existingVerifiedRow.getReach());
        assertEquals(12000L, existingVerifiedRow.getImpressions());
        assertEquals(500L, existingVerifiedRow.getEngagements());
        // collaborationRepository is only consulted to attribute a brand-new row — refreshing an
        // existing one must not need it.
        verify(collaborationRepository, never()).findById(any());
    }

    // ------------------------------------------------------------------------------------------
    // 2. Private-post fallback (post not found in recent media page)
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("private-post fallback: shortcode not found in creator's recent media -> FALLBACK_NOT_FOUND, nothing persisted")
    void privatePostFallsBackWithoutCrashing() {
        Deliverable deliverable = postedDeliverable(POST_URL);
        stubHappyPathTokenAndRateLimit();
        when(instagramInsightsClient.getMedia(IG_BUSINESS_ACCOUNT_ID, ACCESS_TOKEN, 50))
                .thenReturn(new InstagramMediaResponse(List.of(), null));

        DeliverableVerificationService.Outcome outcome = service.verify(deliverable);

        assertEquals(DeliverableVerificationService.Outcome.FALLBACK_NOT_FOUND, outcome);
        assertEquals(DeliverableStatus.POSTED, deliverable.getStatus());
        verify(deliverableMetricRepository, never()).save(any());
        verify(deliverableRepository, never()).save(any());
        verify(instagramInsightsClient, never()).getMediaInsights(any(), any(), any());
    }

    // ------------------------------------------------------------------------------------------
    // 3. Token-missing fallback
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("token-missing fallback: no active Meta connection -> FALLBACK_NO_TOKEN, no API calls attempted")
    void noActiveConnectionFallsBack() {
        Deliverable deliverable = postedDeliverable(POST_URL);
        when(metaOAuthTokenRepository.findFirstByCreatorProfileIdAndRevokedFalseOrderByCreatedAtAsc(
                        CREATOR_PROFILE_ID))
                .thenReturn(Optional.empty());

        DeliverableVerificationService.Outcome outcome = service.verify(deliverable);

        assertEquals(DeliverableVerificationService.Outcome.FALLBACK_NO_TOKEN, outcome);
        verify(instagramInsightsClient, never()).getMedia(any(), any(), anyInt());
        verify(deliverableMetricRepository, never()).save(any());
    }

    @Test
    @DisplayName(
            "CR-99/F-0113 regression: verifyInstagram fetches media with igBusinessAccountId, never the"
                    + " internal creatorProfileId ULID")
    void usesIgBusinessAccountIdNotUlidForMediaFetch() {
        Deliverable deliverable = postedDeliverable(POST_URL);
        stubHappyPathTokenAndRateLimit();
        when(instagramInsightsClient.getMedia(IG_BUSINESS_ACCOUNT_ID, ACCESS_TOKEN, 50))
                .thenReturn(mediaListWithMatch());
        when(instagramInsightsClient.getMediaInsights("17900000000000001", ACCESS_TOKEN, CREATOR_PROFILE_ID))
                .thenReturn(insightsWith(5000, 8000, 300));
        when(deliverableMetricRepository.findByMilestoneId(MILESTONE_ID)).thenReturn(Optional.empty());
        when(collaborationRepository.findById(COLLAB_ID))
                .thenReturn(Optional.of(Collaboration.invite(COLLAB_ID, CAMPAIGN_ID, CREATOR_USER_ID, null, "INR")));

        DeliverableVerificationService.Outcome outcome = service.verify(deliverable);

        assertEquals(DeliverableVerificationService.Outcome.VERIFIED, outcome);
        verify(instagramInsightsClient, never()).getMedia(eq(CREATOR_PROFILE_ID), any(), anyInt());
        verify(instagramInsightsClient).getMedia(eq(IG_BUSINESS_ACCOUNT_ID), eq(ACCESS_TOKEN), eq(50));
    }

    @Test
    @DisplayName(
            "CR-99/F-0113 regression: a token row with no igBusinessAccountId on file falls back to"
                    + " FALLBACK_DATA_INTEGRITY rather than calling Meta with a null/ULID path segment")
    void noIgBusinessAccountIdOnFileFallsBackToDataIntegrity() {
        Deliverable deliverable = postedDeliverable(POST_URL);
        when(metaOAuthTokenRepository.findFirstByCreatorProfileIdAndRevokedFalseOrderByCreatedAtAsc(
                        CREATOR_PROFILE_ID))
                .thenReturn(Optional.of(activeTokenRow(null)));
        when(metaTokenStorage.getValidToken(WORKSPACE_ID, CREATOR_PROFILE_ID))
                .thenReturn(Optional.of(ACCESS_TOKEN));

        DeliverableVerificationService.Outcome outcome = service.verify(deliverable);

        assertEquals(DeliverableVerificationService.Outcome.FALLBACK_DATA_INTEGRITY, outcome);
        verify(instagramInsightsClient, never()).getMedia(any(), any(), anyInt());
        verify(deliverableMetricRepository, never()).save(any());
    }

    @Test
    @DisplayName("token-missing fallback: token row exists but decrypted token is expired/revoked -> FALLBACK_NO_TOKEN")
    void expiredOrRevokedTokenFallsBack() {
        Deliverable deliverable = postedDeliverable(POST_URL);
        when(metaOAuthTokenRepository.findFirstByCreatorProfileIdAndRevokedFalseOrderByCreatedAtAsc(
                        CREATOR_PROFILE_ID))
                .thenReturn(Optional.of(activeTokenRow()));
        when(metaTokenStorage.getValidToken(WORKSPACE_ID, CREATOR_PROFILE_ID)).thenReturn(Optional.empty());

        DeliverableVerificationService.Outcome outcome = service.verify(deliverable);

        assertEquals(DeliverableVerificationService.Outcome.FALLBACK_NO_TOKEN, outcome);
        verify(instagramInsightsClient, never()).getMedia(any(), any(), anyInt());
    }

    // ------------------------------------------------------------------------------------------
    // 4. Rate-limit fallback
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("rate-limit fallback: pre-flight usage at threshold -> FALLBACK_RATE_LIMITED, no API call spent")
    void preflightRateLimitFallsBackWithoutSpendingBudget() {
        Deliverable deliverable = postedDeliverable(POST_URL);
        when(metaOAuthTokenRepository.findFirstByCreatorProfileIdAndRevokedFalseOrderByCreatedAtAsc(
                        CREATOR_PROFILE_ID))
                .thenReturn(Optional.of(activeTokenRow()));
        when(metaTokenStorage.getValidToken(WORKSPACE_ID, CREATOR_PROFILE_ID))
                .thenReturn(Optional.of(ACCESS_TOKEN));
        when(rateLimitTracker.getCurrentUsage(CREATOR_PROFILE_ID)).thenReturn(95);

        DeliverableVerificationService.Outcome outcome = service.verify(deliverable);

        assertEquals(DeliverableVerificationService.Outcome.FALLBACK_RATE_LIMITED, outcome);
        verify(instagramInsightsClient, never()).getMedia(any(), any(), anyInt());
    }

    @Test
    @DisplayName("rate-limit fallback: Meta returns 429 mid-call -> FALLBACK_RATE_LIMITED, tracker marked limited, no crash")
    void metaRateLimitExceptionDuringMediaFetchFallsBack() {
        Deliverable deliverable = postedDeliverable(POST_URL);
        stubHappyPathTokenAndRateLimit();
        when(instagramInsightsClient.getMedia(IG_BUSINESS_ACCOUNT_ID, ACCESS_TOKEN, 50))
                .thenThrow(new MetaRateLimitException("429 from Meta"));

        DeliverableVerificationService.Outcome outcome = service.verify(deliverable);

        assertEquals(DeliverableVerificationService.Outcome.FALLBACK_RATE_LIMITED, outcome);
        verify(rateLimitTracker).markLimited(CREATOR_PROFILE_ID);
        verify(deliverableMetricRepository, never()).save(any());
    }

    // ------------------------------------------------------------------------------------------
    // 5. Encoded-bracket skip (Kabir/Priya carry-forward — defense-in-depth)
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("encoded-bracket skip: postUrl with %3C is rejected before any token/client lookup, never decoded")
    void encodedBracketInPostUrlIsSkippedEntirely() {
        String unsafeUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ&x=%3Cscript%3E";
        Deliverable deliverable = postedDeliverable(unsafeUrl);

        DeliverableVerificationService.Outcome outcome = service.verify(deliverable);

        assertEquals(DeliverableVerificationService.Outcome.FALLBACK_UNRECOGNIZED_URL, outcome);
        verify(metaOAuthTokenRepository, never())
                .findFirstByCreatorProfileIdAndRevokedFalseOrderByCreatedAtAsc(any());
        verify(instagramInsightsClient, never()).getMedia(any(), any(), anyInt());
        verify(deliverableMetricRepository, never()).save(any());
        assertEquals(DeliverableStatus.POSTED, deliverable.getStatus());
    }

    // ------------------------------------------------------------------------------------------
    // Additional guardrails
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("YouTube postUrl falls back as unsupported (no YouTube client exists) rather than crashing")
    void youtubeUrlFallsBackAsUnsupported() {
        Deliverable deliverable = postedDeliverable("https://youtu.be/dQw4w9WgXcQ");

        DeliverableVerificationService.Outcome outcome = service.verify(deliverable);

        assertEquals(DeliverableVerificationService.Outcome.FALLBACK_YOUTUBE_UNSUPPORTED, outcome);
        verify(deliverableMetricRepository, never()).save(any());
    }

    @Test
    @DisplayName("no postUrl yet -> FALLBACK_NO_POST_URL, never attempted")
    void noPostUrlFallsBack() {
        Deliverable deliverable =
                Deliverable.builder()
                        .id(DELIVERABLE_ID)
                        .collaborationId(COLLAB_ID)
                        .creatorProfileId(CREATOR_PROFILE_ID)
                        .milestoneId(MILESTONE_ID)
                        .slotIndex(1)
                        .type(DeliverableType.INSTAGRAM_REEL)
                        .title("Workout Reel 1")
                        .status(DeliverableStatus.APPROVED)
                        .build();

        DeliverableVerificationService.Outcome outcome = service.verify(deliverable);

        assertEquals(DeliverableVerificationService.Outcome.FALLBACK_NO_POST_URL, outcome);
    }

    @Test
    @DisplayName("no milestone linked -> FALLBACK_NO_MILESTONE (cannot persist a metric row without one)")
    void noMilestoneLinkedFallsBack() {
        Deliverable deliverable =
                Deliverable.builder()
                        .id(DELIVERABLE_ID)
                        .collaborationId(COLLAB_ID)
                        .creatorProfileId(CREATOR_PROFILE_ID)
                        .slotIndex(1)
                        .type(DeliverableType.INSTAGRAM_REEL)
                        .title("Workout Reel 1")
                        .status(DeliverableStatus.APPROVED)
                        .build();
        deliverable.applyMarkPosted(POST_URL);

        DeliverableVerificationService.Outcome outcome = service.verify(deliverable);

        assertEquals(DeliverableVerificationService.Outcome.FALLBACK_NO_MILESTONE, outcome);
        verify(metaOAuthTokenRepository, never())
                .findFirstByCreatorProfileIdAndRevokedFalseOrderByCreatedAtAsc(any());
    }

    @Test
    @DisplayName("a creator self-report submitted after verification never overwrites the verified numbers")
    void verifiedRowResistsLaterSelfReportDowngrade() {
        DeliverableMetric metric =
                DeliverableMetric.builder()
                        .id("01HMETRIC1234567890AB")
                        .milestoneId(MILESTONE_ID)
                        .collaborationId(COLLAB_ID)
                        .reportedByCreatorId(CREATOR_USER_ID)
                        .build();
        metric.applyVerifiedReport(5000L, 8000L, 300L, "17900000000000001");

        metric.applyReport(1L, 1L, 1L, "https://fake", null, CREATOR_USER_ID);

        assertEquals(DeliverableMetric.SOURCE_PLATFORM_VERIFIED, metric.getSource());
        assertEquals(5000L, metric.getReach());
        assertEquals(8000L, metric.getImpressions());
        assertEquals(300L, metric.getEngagements());
    }
}
