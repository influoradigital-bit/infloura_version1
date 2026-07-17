package com.influora.service.scoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.config.BrandSafetyAiProperties;
import com.influora.domain.entity.MediaMetric;
import com.influora.domain.entity.MetaOAuthToken;
import com.influora.integration.ai.BrandSafetyAiClient;
import com.influora.integration.ai.BrandSafetyAiException;
import com.influora.integration.ai.dto.BrandSafetyDtos.ClassifiedItem;
import com.influora.integration.ai.dto.BrandSafetyDtos.ContentItem;
import com.influora.integration.ai.dto.BrandSafetyDtos.GarmFlag;
import com.influora.repository.MetaOAuthTokenRepository;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
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
 * Unit tests for BrandSafetyScoreService (Wave C task C3) — mapping correctness and the
 * graceful-degradation contract (never throws, {@link Optional#empty()} on any failure mode).
 */
@ExtendWith(MockitoExtension.class)
class BrandSafetyScoreServiceTest {

    private static final String CREATOR_ID = "01HWXYZCREATOR123456789";
    private static final String WORKSPACE_ID = "01HWXYZWORKSPACE123456789";

    @Mock private MetaOAuthTokenRepository metaOAuthTokenRepository;
    @Mock private BrandSafetyAiClient brandSafetyAiClient;

    private BrandSafetyScoreService service;

    @BeforeEach
    void setUp() {
        service = new BrandSafetyScoreService(metaOAuthTokenRepository, brandSafetyAiClient);
    }

    @Test
    @DisplayName("scoreCreator: empty recentMedia returns empty without touching repository/client")
    void testScoreCreatorEmptyMediaReturnsEmpty() {
        Optional<BrandSafetyScoreService.BrandSafetyResult> result =
                service.scoreCreator(CREATOR_ID, List.of());

        assertTrue(result.isEmpty());
        verify(metaOAuthTokenRepository, never()).findFirstByCreatorProfileIdAndRevokedFalseOrderByCreatedAtAsc(anyString());
        verify(brandSafetyAiClient, never()).classify(anyString(), anyList());
    }

    @Test
    @DisplayName("scoreCreator: null recentMedia returns empty without touching repository/client")
    void testScoreCreatorNullMediaReturnsEmpty() {
        Optional<BrandSafetyScoreService.BrandSafetyResult> result =
                service.scoreCreator(CREATOR_ID, null);

        assertTrue(result.isEmpty());
        verify(metaOAuthTokenRepository, never()).findFirstByCreatorProfileIdAndRevokedFalseOrderByCreatedAtAsc(anyString());
    }

    @Test
    @DisplayName("scoreCreator: no active workspace connection returns empty without calling the AI client")
    void testScoreCreatorNoWorkspaceConnectionReturnsEmpty() {
        when(metaOAuthTokenRepository.findFirstByCreatorProfileIdAndRevokedFalseOrderByCreatedAtAsc(CREATOR_ID))
                .thenReturn(Optional.empty());

        List<MediaMetric> media = List.of(mediaWithCaption("m1", "hello"));
        Optional<BrandSafetyScoreService.BrandSafetyResult> result =
                service.scoreCreator(CREATOR_ID, media);

        assertTrue(result.isEmpty());
        verify(brandSafetyAiClient, never()).classify(anyString(), anyList());
    }

    @Test
    @DisplayName("scoreCreator: BrandSafetyAiException from the client is caught, returns empty (graceful degradation)")
    void testScoreCreatorAiClientExceptionReturnsEmpty() {
        when(metaOAuthTokenRepository.findFirstByCreatorProfileIdAndRevokedFalseOrderByCreatedAtAsc(CREATOR_ID))
                .thenReturn(Optional.of(tokenRow()));
        when(brandSafetyAiClient.classify(eq(WORKSPACE_ID), anyList()))
                .thenThrow(new BrandSafetyAiException("influora-ai unreachable"));

        List<MediaMetric> media = List.of(mediaWithCaption("m1", "hello"));
        Optional<BrandSafetyScoreService.BrandSafetyResult> result =
                service.scoreCreator(CREATOR_ID, media);

        assertTrue(result.isEmpty(), "must degrade gracefully, never throw or propagate");
    }

    @Test
    @DisplayName("scoreCreator: unexpected RuntimeException from the client is also caught, returns empty")
    void testScoreCreatorUnexpectedExceptionReturnsEmpty() {
        when(metaOAuthTokenRepository.findFirstByCreatorProfileIdAndRevokedFalseOrderByCreatedAtAsc(CREATOR_ID))
                .thenReturn(Optional.of(tokenRow()));
        when(brandSafetyAiClient.classify(eq(WORKSPACE_ID), anyList()))
                .thenThrow(new NullPointerException("unexpected bug"));

        List<MediaMetric> media = List.of(mediaWithCaption("m1", "hello"));
        Optional<BrandSafetyScoreService.BrandSafetyResult> result =
                service.scoreCreator(CREATOR_ID, media);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("scoreCreator: happy path maps the worst (lowest-score) item as the creator-level score")
    void testScoreCreatorMapsWorstItemAsCreatorLevelScore() {
        when(metaOAuthTokenRepository.findFirstByCreatorProfileIdAndRevokedFalseOrderByCreatedAtAsc(CREATOR_ID))
                .thenReturn(Optional.of(tokenRow()));

        ClassifiedItem safeItem =
                new ClassifiedItem(
                        "m1",
                        List.of(new GarmFlag("spam_or_harmful_content", "floor", "clean")),
                        "positive",
                        0.9,
                        98.0,
                        "no concerns");
        ClassifiedItem riskyItem =
                new ClassifiedItem(
                        "m2",
                        List.of(new GarmFlag("hate_speech_acts_of_aggression", "high", "flagged")),
                        "negative",
                        -0.6,
                        20.0,
                        "hate speech detected");
        when(brandSafetyAiClient.classify(eq(WORKSPACE_ID), anyList()))
                .thenReturn(List.of(safeItem, riskyItem));

        List<MediaMetric> media =
                List.of(mediaWithCaption("m1", "safe caption"), mediaWithCaption("m2", "risky caption"));
        Optional<BrandSafetyScoreService.BrandSafetyResult> result =
                service.scoreCreator(CREATOR_ID, media);

        assertTrue(result.isPresent());
        BrandSafetyScoreService.BrandSafetyResult value = result.get();
        // The worst (lowest brand_safety_score) item drives the creator-level score, never an average.
        assertEquals(0, BigDecimal.valueOf(20.00).compareTo(value.brandSafetyScore()));
        assertEquals(0, BigDecimal.valueOf(-0.60).compareTo(value.contentSentiment()));
        // garm_flags is written as a flat List<String> of above-floor category names (never content
        // IDs or nested {category,risk,rationale} objects) — this is the exact shape
        // JsonLists.stringListFromJson expects on the read side (AnalyticsService.getCreatorScores).
        // A prior version of this assertion checked for "m1"/"m2" content-id substrings, which
        // only passed because the old (buggy) writer serialized the full ClassifiedItem list;
        // that shape doesn't round-trip through the List<String> reader — see
        // BrandSafetyScoreServiceRoundTripTest for the regression guard on the full write/read path.
        assertFalse(value.garmFlagsJson().contains("m1"), "flags must not embed content ids");
        assertFalse(value.garmFlagsJson().contains("m2"), "flags must not embed content ids");
        assertTrue(value.garmFlagsJson().contains("hate_speech_acts_of_aggression"));
        // The floor-risk category from the safe item must be excluded (no concern, not a "flag").
        assertFalse(value.garmFlagsJson().contains("spam_or_harmful_content"));
    }

    @Test
    @DisplayName("scoreCreator: sends content_id/caption/media_type/posted_at from MediaMetric rows")
    void testScoreCreatorBuildsCorrectContentItems() {
        when(metaOAuthTokenRepository.findFirstByCreatorProfileIdAndRevokedFalseOrderByCreatedAtAsc(CREATOR_ID))
                .thenReturn(Optional.of(tokenRow()));

        ClassifiedItem item =
                new ClassifiedItem(
                        "media-1",
                        List.of(new GarmFlag("spam_or_harmful_content", "floor", "clean")),
                        "neutral",
                        0.0,
                        100.0,
                        "");
        ArgumentCaptor<List<ContentItem>> itemsCaptor = ArgumentCaptor.forClass(List.class);
        when(brandSafetyAiClient.classify(eq(WORKSPACE_ID), itemsCaptor.capture()))
                .thenReturn(List.of(item));

        MediaMetric media =
                MediaMetric.builder()
                        .id("id1")
                        .mediaId("media-1")
                        .creatorProfileId(CREATOR_ID)
                        .platform("INSTAGRAM")
                        .mediaType("IMAGE")
                        .caption("hello world")
                        .postedAt(Instant.parse("2026-01-01T00:00:00Z"))
                        .build();

        service.scoreCreator(CREATOR_ID, List.of(media));

        List<ContentItem> sentItems = itemsCaptor.getValue();
        assertEquals(1, sentItems.size());
        assertEquals("media-1", sentItems.get(0).contentId());
        assertEquals("hello world", sentItems.get(0).caption());
        assertEquals("IMAGE", sentItems.get(0).mediaType());
        assertEquals("2026-01-01T00:00:00Z", sentItems.get(0).postedAt());
    }

    @Test
    @DisplayName("scoreCreator: classified result with no usable brand_safety_score returns empty")
    void testScoreCreatorAllNullScoresReturnsEmpty() {
        when(metaOAuthTokenRepository.findFirstByCreatorProfileIdAndRevokedFalseOrderByCreatedAtAsc(CREATOR_ID))
                .thenReturn(Optional.of(tokenRow()));

        ClassifiedItem itemWithNullScore =
                new ClassifiedItem("m1", List.of(), "neutral", 0.0, null, "");
        when(brandSafetyAiClient.classify(eq(WORKSPACE_ID), anyList()))
                .thenReturn(List.of(itemWithNullScore));

        List<MediaMetric> media = List.of(mediaWithCaption("m1", "hello"));
        Optional<BrandSafetyScoreService.BrandSafetyResult> result =
                service.scoreCreator(CREATOR_ID, media);

        assertFalse(result.isPresent());
    }

    @Test
    @DisplayName("scoreCreator: recentMedia > 25 items is split into 2 classify() calls, results merged")
    void testScoreCreatorChunksAt25Items() {
        when(metaOAuthTokenRepository.findFirstByCreatorProfileIdAndRevokedFalseOrderByCreatedAtAsc(CREATOR_ID))
                .thenReturn(Optional.of(tokenRow()));

        // 30 items (matches ScoreCalculationJob.RECENT_MEDIA_LIMIT) -> chunks of 25 + 5.
        List<MediaMetric> media = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            media.add(mediaWithCaption("m" + i, "caption " + i));
        }

        ArgumentCaptor<List<ContentItem>> chunkCaptor = ArgumentCaptor.forClass(List.class);
        when(brandSafetyAiClient.classify(eq(WORKSPACE_ID), chunkCaptor.capture()))
                .thenAnswer(
                        invocation -> {
                            List<ContentItem> chunk = invocation.getArgument(1);
                            List<ClassifiedItem> result = new ArrayList<>();
                            for (ContentItem item : chunk) {
                                result.add(
                                        new ClassifiedItem(
                                                item.contentId(),
                                                List.of(new GarmFlag("spam_or_harmful_content", "floor", "clean")),
                                                "neutral",
                                                0.0,
                                                95.0,
                                                ""));
                            }
                            return result;
                        });

        Optional<BrandSafetyScoreService.BrandSafetyResult> result =
                service.scoreCreator(CREATOR_ID, media);

        assertTrue(result.isPresent());
        // Exactly 2 chunked calls (25 + 5), never a single 30-item call (client would throw).
        List<List<ContentItem>> chunksSent = chunkCaptor.getAllValues();
        assertEquals(2, chunksSent.size());
        assertEquals(25, chunksSent.get(0).size());
        assertEquals(5, chunksSent.get(1).size());
    }

    @Test
    @DisplayName(
            "scoreCreator: recentMedia.size() == 25 (exact chunk boundary) produces EXACTLY ONE classify() call")
    void testScoreCreatorExactlyAtChunkBoundaryMakesExactlyOneCall() {
        when(metaOAuthTokenRepository.findFirstByCreatorProfileIdAndRevokedFalseOrderByCreatedAtAsc(CREATOR_ID))
                .thenReturn(Optional.of(tokenRow()));

        // Exactly 25 items — must NOT split into [25, 0] or any 2-call shape; the boundary itself
        // is load-bearing (BrandSafetyAiClient#classify throws only when count > maxItemsPerCall).
        List<MediaMetric> media = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            media.add(mediaWithCaption("m" + i, "caption " + i));
        }

        when(brandSafetyAiClient.classify(eq(WORKSPACE_ID), anyList()))
                .thenAnswer(
                        invocation -> {
                            List<ContentItem> chunk = invocation.getArgument(1);
                            List<ClassifiedItem> result = new ArrayList<>();
                            for (ContentItem item : chunk) {
                                result.add(
                                        new ClassifiedItem(
                                                item.contentId(),
                                                List.of(new GarmFlag("spam_or_harmful_content", "floor", "clean")),
                                                "neutral",
                                                0.0,
                                                95.0,
                                                ""));
                            }
                            return result;
                        });

        Optional<BrandSafetyScoreService.BrandSafetyResult> result =
                service.scoreCreator(CREATOR_ID, media);

        assertTrue(result.isPresent());
        verify(brandSafetyAiClient, times(1)).classify(eq(WORKSPACE_ID), anyList());
    }

    @Test
    @DisplayName(
            "CTO ruling (post-QA): ANY chunk failure degrades the ENTIRE creator to Optional.empty(), "
                    + "even when other chunks succeeded — never a partial/best-effort safety score")
    void testScoreCreatorAnyChunkFailureDegradesEntireCreatorToEmpty() {
        when(metaOAuthTokenRepository.findFirstByCreatorProfileIdAndRevokedFalseOrderByCreatedAtAsc(CREATOR_ID))
                .thenReturn(Optional.of(tokenRow()));

        List<MediaMetric> media = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            media.add(mediaWithCaption("m" + i, "caption " + i));
        }

        ClassifiedItem okItem =
                new ClassifiedItem(
                        "m25",
                        List.of(new GarmFlag("hate_speech_acts_of_aggression", "high", "flagged")),
                        "negative",
                        -0.5,
                        30.0,
                        "flagged");

        // Chunk 1 (25 items) fails; chunk 2 (5 items) succeeds. Fail-safe policy: the creator's
        // ENTIRE result must still degrade to empty — a false-negative hazard (the unsafe post
        // could have been in the chunk that failed) outweighs "some data is better than none" for
        // a safety signal.
        when(brandSafetyAiClient.classify(eq(WORKSPACE_ID), argThat(chunk -> chunk.size() == 25)))
                .thenThrow(new BrandSafetyAiException("influora-ai unreachable for chunk 1"));
        when(brandSafetyAiClient.classify(eq(WORKSPACE_ID), argThat(chunk -> chunk.size() == 5)))
                .thenReturn(List.of(okItem));

        Optional<BrandSafetyScoreService.BrandSafetyResult> result =
                service.scoreCreator(CREATOR_ID, media);

        assertTrue(
                result.isEmpty(),
                "one chunk failing must discard the whole creator's result this run, even though "
                        + "the other chunk succeeded — never a partial safety score");
    }

    @Test
    @DisplayName("scoreCreator: all chunks failing returns Optional.empty(), never a partial/best-effort result")
    void testScoreCreatorAllChunksFailReturnsEmpty() {
        when(metaOAuthTokenRepository.findFirstByCreatorProfileIdAndRevokedFalseOrderByCreatedAtAsc(CREATOR_ID))
                .thenReturn(Optional.of(tokenRow()));

        List<MediaMetric> media = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            media.add(mediaWithCaption("m" + i, "caption " + i));
        }

        when(brandSafetyAiClient.classify(eq(WORKSPACE_ID), anyList()))
                .thenThrow(new BrandSafetyAiException("influora-ai unreachable"));

        Optional<BrandSafetyScoreService.BrandSafetyResult> result =
                service.scoreCreator(CREATOR_ID, media);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName(
            "config-drift guard: BrandSafetyScoreService's local CHUNK_SIZE must equal "
                    + "BrandSafetyAiProperties.getMaxItemsPerCall()'s default (25), or production would throw")
    void testChunkSizeMatchesBrandSafetyAiPropertiesDefault() throws Exception {
        Field chunkSizeField = BrandSafetyScoreService.class.getDeclaredField("CHUNK_SIZE");
        chunkSizeField.setAccessible(true);
        int chunkSize = chunkSizeField.getInt(null);

        int configuredMax = new BrandSafetyAiProperties().getMaxItemsPerCall();

        assertEquals(
                configuredMax,
                chunkSize,
                "BrandSafetyScoreService.CHUNK_SIZE ("
                        + chunkSize
                        + ") drifted from BrandSafetyAiProperties' default max-items-per-call ("
                        + configuredMax
                        + "). BrandSafetyAiClient#classify throws if a single chunk exceeds its "
                        + "configured max — keep these in sync or every job run will hit that "
                        + "exception in production.");
    }

    private MetaOAuthToken tokenRow() {
        return MetaOAuthToken.builder()
                .id("token1")
                .workspaceId(WORKSPACE_ID)
                .creatorProfileId(CREATOR_ID)
                .encryptedAccessToken("enc")
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }

    private MediaMetric mediaWithCaption(String mediaId, String caption) {
        return MediaMetric.builder()
                .id("id-" + mediaId)
                .mediaId(mediaId)
                .creatorProfileId(CREATOR_ID)
                .platform("INSTAGRAM")
                .mediaType("IMAGE")
                .caption(caption)
                .build();
    }
}
