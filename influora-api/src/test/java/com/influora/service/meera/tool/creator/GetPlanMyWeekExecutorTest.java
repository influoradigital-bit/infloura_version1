package com.influora.service.meera.tool.creator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.service.creatorcopilot.ContentTopicService;
import com.influora.service.creatorcopilot.CreatorPostingPatternService;
import com.influora.service.creatorcopilot.CreatorPostingPatternService.PatternWindow;
import com.influora.service.creatorcopilot.CreatorPostingPatternService.PostingPattern;
import com.influora.web.dto.meera.CreatorToolDtos.PlanDay;
import com.influora.web.dto.meera.CreatorToolDtos.PlanMyWeekResult;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * T-PLAN-MY-WEEK -- {@link GetPlanMyWeekExecutor}: proves {@code today}/{@code days} are computed
 * in IST by the executor itself (never read from {@code input}), that the 7-day span always starts
 * today, and that both collaborators are handed the SAME IST date the response reports.
 */
@ExtendWith(MockitoExtension.class)
class GetPlanMyWeekExecutorTest {

    private static final String CREATOR_USER_ID = "01HCREATORUSER1234567A";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Mock private ContentTopicService contentTopicService;
    @Mock private CreatorPostingPatternService creatorPostingPatternService;

    private static PostingPattern emptyPattern() {
        return new PostingPattern(false, 0, null, List.of(), "not enough data yet");
    }

    @Test
    @DisplayName("days: exactly 7 entries, the first is today, and every weekday name matches its date")
    void sevenDaysStartingToday() {
        GetPlanMyWeekExecutor executor =
                new GetPlanMyWeekExecutor(contentTopicService, creatorPostingPatternService);
        when(contentTopicService.topicsFor(eq(CREATOR_USER_ID), any())).thenReturn(List.of());
        when(creatorPostingPatternService.analyse(eq(CREATOR_USER_ID), any()))
                .thenReturn(emptyPattern());

        PlanMyWeekResult result = executor.execute(CREATOR_USER_ID, Map.of());

        LocalDate expectedToday = LocalDate.now(IST);
        assertEquals(expectedToday.toString(), result.today());
        assertEquals(7, result.days().size());
        assertEquals(expectedToday.toString(), result.days().get(0).date());

        for (int i = 0; i < 7; i++) {
            LocalDate expectedDate = expectedToday.plusDays(i);
            PlanDay day = result.days().get(i);
            assertEquals(expectedDate.toString(), day.date());
            assertEquals(
                    expectedDate.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH),
                    day.weekday());
        }
    }

    @Test
    @DisplayName(
            "the executor computes its OWN IST `today` and passes that exact value to both"
                    + " ContentTopicService and CreatorPostingPatternService -- never a value the"
                    + " request could influence")
    void passesOwnIstDateToBothServices() {
        GetPlanMyWeekExecutor executor =
                new GetPlanMyWeekExecutor(contentTopicService, creatorPostingPatternService);
        when(contentTopicService.topicsFor(eq(CREATOR_USER_ID), any())).thenReturn(List.of());
        when(creatorPostingPatternService.analyse(eq(CREATOR_USER_ID), any()))
                .thenReturn(emptyPattern());

        executor.execute(CREATOR_USER_ID, Map.of());

        LocalDate expectedToday = LocalDate.now(IST);

        ArgumentCaptor<LocalDate> topicsToday = ArgumentCaptor.forClass(LocalDate.class);
        verify(contentTopicService).topicsFor(eq(CREATOR_USER_ID), topicsToday.capture());
        assertEquals(expectedToday, topicsToday.getValue());

        ArgumentCaptor<LocalDate> patternToday = ArgumentCaptor.forClass(LocalDate.class);
        verify(creatorPostingPatternService).analyse(eq(CREATOR_USER_ID), patternToday.capture());
        assertEquals(expectedToday, patternToday.getValue());
    }

    @Test
    @DisplayName("PostingPattern maps field-for-field onto PatternResult")
    void mapsPatternToPatternResult() {
        GetPlanMyWeekExecutor executor =
                new GetPlanMyWeekExecutor(contentTopicService, creatorPostingPatternService);
        when(contentTopicService.topicsFor(eq(CREATOR_USER_ID), any())).thenReturn(List.of());
        PostingPattern pattern =
                new PostingPattern(
                        true, 12, "REEL", List.of(new PatternWindow("weekday evening", 5, "4.8%")), null);
        when(creatorPostingPatternService.analyse(eq(CREATOR_USER_ID), any())).thenReturn(pattern);

        PlanMyWeekResult result = executor.execute(CREATOR_USER_ID, Map.of());

        assertEquals(true, result.pattern().enoughData());
        assertEquals(12, result.pattern().postsCounted());
        assertEquals("REEL", result.pattern().bestPostType());
        assertEquals(1, result.pattern().windows().size());
        assertEquals("weekday evening", result.pattern().windows().get(0).label());
        assertEquals(5, result.pattern().windows().get(0).posts());
        assertEquals("4.8%", result.pattern().windows().get(0).engagementRate());
    }
}
