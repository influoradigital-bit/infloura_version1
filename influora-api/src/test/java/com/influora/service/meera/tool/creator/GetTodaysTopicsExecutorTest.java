package com.influora.service.meera.tool.creator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.service.creatorcopilot.ContentTopicService;
import com.influora.service.creatorcopilot.ContentTopicService.ServableTopic;
import com.influora.web.dto.meera.CreatorToolDtos.GetTodaysTopicsResult;
import com.influora.web.dto.meera.CreatorToolDtos.TopicResult;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * T-CONTENT-TOPICS -- {@link GetTodaysTopicsExecutor}: proves {@code today} is computed in IST by
 * the executor itself (never read from {@code input}, never left to the model), and that the
 * {@link ServableTopic} -> {@link TopicResult} mapping is a straight pass-through.
 */
@ExtendWith(MockitoExtension.class)
class GetTodaysTopicsExecutorTest {

    private static final String CREATOR_USER_ID = "01HCREATORUSER1234567A";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Mock private ContentTopicService contentTopicService;

    @Test
    @DisplayName("today/weekday are computed in IST, and are passed to ContentTopicService unchanged")
    void todayIsComputedInIst() {
        GetTodaysTopicsExecutor executor = new GetTodaysTopicsExecutor(contentTopicService);
        when(contentTopicService.topicsFor(eq(CREATOR_USER_ID), any())).thenReturn(List.of());

        GetTodaysTopicsResult result = executor.execute(CREATOR_USER_ID, java.util.Map.of());

        LocalDate expectedToday = LocalDate.now(IST);
        assertEquals(expectedToday.toString(), result.today());
        assertEquals(
                expectedToday.getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH),
                result.weekday());

        ArgumentCaptor<LocalDate> todayCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(contentTopicService).topicsFor(eq(CREATOR_USER_ID), todayCaptor.capture());
        assertEquals(expectedToday, todayCaptor.getValue());
    }

    @Test
    @DisplayName("ServableTopic maps to TopicResult field-for-field, with live_until rendered as ISO")
    void mapsServableTopicToTopicResult() {
        GetTodaysTopicsExecutor executor = new GetTodaysTopicsExecutor(contentTopicService);
        ServableTopic servable =
                new ServableTopic(
                        42L,
                        "Beauty",
                        "Diwali skincare edit",
                        List.of("Angle one.", "Angle two."),
                        LocalDate.of(2026, 9, 30),
                        "seasonal");
        when(contentTopicService.topicsFor(eq(CREATOR_USER_ID), any())).thenReturn(List.of(servable));

        GetTodaysTopicsResult result = executor.execute(CREATOR_USER_ID, java.util.Map.of());

        assertEquals(1, result.topics().size());
        TopicResult mapped = result.topics().get(0);
        assertEquals(42L, mapped.id());
        assertEquals("Beauty", mapped.category());
        assertEquals("Diwali skincare edit", mapped.title());
        assertEquals(List.of("Angle one.", "Angle two."), mapped.angles());
        assertEquals("2026-09-30", mapped.liveUntil());
        assertEquals("seasonal", mapped.sensitivity());
    }
}
