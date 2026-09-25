package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.domain.entity.CreatorChallenge;
import com.influora.domain.entity.CreatorChallengeDay;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.CreatorRecommendation;
import com.influora.domain.enums.ChallengeDayType;
import com.influora.domain.enums.CreatorRecommendationSource;
import com.influora.domain.enums.CreatorRecommendationStatus;
import com.influora.repository.CreatorChallengeDayRepository;
import com.influora.repository.CreatorChallengeRepository;
import com.influora.repository.CreatorRecommendationRepository;
import com.influora.repository.MediaMetricsRepository;
import com.influora.service.creatorcopilot.CreatorPostingPatternService;
import com.influora.service.creatorcopilot.CreatorPostingPatternService.PostingPattern;
import com.influora.service.creatorcopilot.CreatorRecommendationService;
import com.influora.service.creatorcopilot.CreatorRecommendationWriter;
import com.influora.web.dto.meta.MetaDtos.MetaConnectionStatusResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.annotation.Transactional;

/**
 * Meera intelligence v1, slice 2 (spec 8.3) -- {@code CreatorChallengeService.start} records one
 * {@code creator_recommendations} row per non-REST day, in the same call (and so the same
 * transaction) as the days, through the REAL {@link CreatorRecommendationService}; a replay
 * records nothing.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CreatorChallengeRecommendationRecordingTest {

    private static final String USER_ID = "01USERCHALLENGE0000001";
    private static final String PROFILE_ID = "01PROFILECHALLENGE0001";

    @Mock private CreatorChallengeRepository challengeRepository;
    @Mock private CreatorChallengeDayRepository dayRepository;
    @Mock private MediaMetricsRepository mediaMetricsRepository;
    @Mock private CreatorPostingPatternService postingPatternService;
    @Mock private MetaConnectionService metaConnectionService;
    @Mock private CreatorRecommendationRepository recommendationRepository;

    private CreatorRecommendationService recommendationService;
    private CreatorChallengeService service;
    private CreatorProfile profile;
    private String startedChallengeId;

    @BeforeEach
    void setUp() {
        recommendationService =
                new CreatorRecommendationService(
                        recommendationRepository, mock(CreatorRecommendationWriter.class), new ObjectMapper());
        service =
                new CreatorChallengeService(
                        challengeRepository,
                        dayRepository,
                        mediaMetricsRepository,
                        postingPatternService,
                        metaConnectionService,
                        recommendationService);
        profile = CreatorProfile.newForUser(PROFILE_ID, USER_ID, "Test Creator");
        when(mediaMetricsRepository.findNewestSnapshotPerPostSince(anyString(), any())).thenReturn(List.of());
    }

    private static LocalDate mondayStart() {
        return LocalDate.of(2026, 9, 21).with(TemporalAdjusters.nextOrSame(java.time.DayOfWeek.MONDAY));
    }

    @SuppressWarnings("unchecked")
    private List<CreatorRecommendation> startAndCaptureRecommendations(LocalDate startedOn, Instant now) {
        when(challengeRepository.findByActiveKey(USER_ID)).thenReturn(Optional.empty());
        when(metaConnectionService.getStatus(profile))
                .thenReturn(new MetaConnectionStatusResponse(true, "@x", 100L, now, List.of(), null, null, null));
        when(postingPatternService.analyse(eq(USER_ID), eq(startedOn)))
                .thenReturn(new PostingPattern(false, 2, null, List.of(), "not enough"));
        CreatorChallenge saved = CreatorChallenge.start("01NEWCHALLENGE00000000001", USER_ID, PROFILE_ID, startedOn);
        when(challengeRepository.saveAndFlush(any(CreatorChallenge.class)))
                .thenAnswer(
                        inv -> {
                            startedChallengeId = ((CreatorChallenge) inv.getArgument(0)).getId();
                            return inv.getArgument(0);
                        });
        when(challengeRepository.findByActiveKey(USER_ID)).thenReturn(Optional.empty()).thenReturn(Optional.of(saved));
        ArgumentCaptor<List<CreatorChallengeDay>> daysCaptor = ArgumentCaptor.forClass(List.class);
        when(dayRepository.saveAll(daysCaptor.capture())).thenReturn(List.of());
        when(dayRepository.findByIdChallengeIdOrderByIdDayIndexAsc(anyString()))
                .thenAnswer(inv -> new ArrayList<>(daysCaptor.getValue()));
        when(recommendationRepository.findExistingSourceRefs(any(), any(), any())).thenReturn(List.of());
        ArgumentCaptor<List<CreatorRecommendation>> recsCaptor = ArgumentCaptor.forClass(List.class);
        when(recommendationRepository.saveAll(recsCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        service.start(profile, startedOn, now);

        InOrder order = inOrder(dayRepository, recommendationRepository);
        order.verify(dayRepository).saveAll(any());
        order.verify(recommendationRepository).saveAll(any());
        return recsCaptor.getValue();
    }

    @Test
    @DisplayName(
            "challenge start records exactly one OPEN CHALLENGE row per non-REST day: source_ref"
                    + " challengeId:dayIndex, type and window copied from the day, match_until = day + 1")
    void startRecordsOneRowPerNonRestDay() {
        LocalDate startedOn = mondayStart();
        Instant now = startedOn.atTime(9, 0).atZone(java.time.ZoneId.of("Asia/Kolkata")).toInstant();

        List<CreatorRecommendation> recs = startAndCaptureRecommendations(startedOn, now);

        assertEquals(6, recs.size(), "7 days, one REST -> 6 rows");
        List<String> refs = recs.stream().map(CreatorRecommendation::getSourceRef).toList();
        String id = startedChallengeId;
        assertEquals(
                List.of(id + ":0", id + ":1", id + ":2", id + ":3", id + ":4", id + ":5"),
                refs,
                "Sunday (index 6) is the REST day and is not recorded");
        for (int i = 0; i < recs.size(); i++) {
            CreatorRecommendation r = recs.get(i);
            assertEquals(CreatorRecommendationSource.CHALLENGE, r.getSource());
            assertEquals(CreatorRecommendationStatus.OPEN, r.getStatus());
            assertEquals(USER_ID, r.getCreatorUserId());
            assertEquals(PROFILE_ID, r.getCreatorProfileId());
            assertNull(r.getConversationId(), "challenge rows go with the profile, not a conversation");
            assertEquals(startedOn.plusDays(i), r.getRecommendedFor());
            assertEquals(startedOn.plusDays(i + 1), r.getMatchUntil());
            assertEquals("evening", r.getWindowLabel());
            assertEquals(LocalTime.of(17, 0), r.getWindowFrom());
            assertEquals(LocalTime.of(22, 0), r.getWindowTo());
            assertEquals(now, r.getCreatedAt());
            assertTrue(r.getPostType() != ChallengeDayType.REST);
        }
        assertEquals(
                List.of(
                        ChallengeDayType.REEL,
                        ChallengeDayType.CAROUSEL,
                        ChallengeDayType.POST,
                        ChallengeDayType.REEL,
                        ChallengeDayType.CAROUSEL,
                        ChallengeDayType.REEL),
                recs.stream().map(CreatorRecommendation::getPostType).toList());
    }

    @Test
    @DisplayName("replaying the challenge recording is a no-op: every source_ref already stored -> nothing saved")
    @SuppressWarnings("unchecked")
    void replayIsANoOp() {
        LocalDate startedOn = mondayStart();
        Instant now = Instant.parse("2026-09-21T03:30:00Z");
        List<CreatorRecommendation> first = startAndCaptureRecommendations(startedOn, now);
        List<CreatorChallengeDay> days = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            days.add(
                    CreatorChallengeDay.plan(
                            "01NEWCHALLENGE00000000001",
                            i,
                            startedOn.plusDays(i),
                            i == 6 ? ChallengeDayType.REST : ChallengeDayType.REEL,
                            i == 6 ? null : "evening",
                            null,
                            null,
                            null));
        }
        CreatorChallenge challenge = CreatorChallenge.start("01NEWCHALLENGE00000000001", USER_ID, PROFILE_ID, startedOn);
        when(recommendationRepository.findExistingSourceRefs(eq(PROFILE_ID), eq(CreatorRecommendationSource.CHALLENGE), any()))
                .thenAnswer(inv -> List.copyOf((Collection<String>) inv.getArgument(2)));

        org.mockito.Mockito.clearInvocations(recommendationRepository);
        recommendationService.recordChallengePlan(challenge, days, now);

        verify(recommendationRepository, never()).saveAll(any());
        assertEquals(6, first.size());
    }

    @Test
    @DisplayName(
            "same transaction: recordChallengePlan carries no @Transactional of its own, so it joins"
                    + " start()'s transaction (a REQUIRES_NEW here would commit rows for a rolled-back challenge)")
    void recordingJoinsTheStartTransaction() throws Exception {
        assertTrue(
                CreatorChallengeService.class
                        .getMethod("start", CreatorProfile.class, LocalDate.class, Instant.class)
                        .isAnnotationPresent(Transactional.class));
        assertFalse(
                CreatorRecommendationService.class
                        .getMethod("recordChallengePlan", CreatorChallenge.class, List.class, Instant.class)
                        .isAnnotationPresent(Transactional.class));
        assertFalse(CreatorRecommendationService.class.isAnnotationPresent(Transactional.class));
    }
}
