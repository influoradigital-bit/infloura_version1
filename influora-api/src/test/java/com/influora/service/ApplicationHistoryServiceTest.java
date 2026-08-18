package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.ApplicationHistoryEvent;
import com.influora.domain.enums.ApplicationHistoryActorType;
import com.influora.domain.enums.ApplicationHistoryEventType;
import com.influora.domain.enums.CollaborationStatus;
import com.influora.repository.ApplicationHistoryEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Covers the two rules {@link ApplicationHistoryService} exists to enforce: {@link
 * ApplicationHistoryService#record} always appends, and {@link
 * ApplicationHistoryService#recordViewIfAbsent} only ever writes the FIRST brand view of a given
 * application (requirement #2 — a second brand view must not spam the timeline).
 */
@ExtendWith(MockitoExtension.class)
class ApplicationHistoryServiceTest {

    private static final String CAMPAIGN_ID = "01HCAMPAIGN1234567890";
    private static final String APPLICATION_ID = "01HDEAL00000000000001";
    private static final String BRAND_USER_ID = "01HBRANDUSER123456789";

    @Mock private ApplicationHistoryEventRepository repository;

    private ApplicationHistoryService service;

    @BeforeEach
    void setUp() {
        service = new ApplicationHistoryService(repository);
    }

    @Test
    @DisplayName("record: appends exactly one event, carrying every field through unchanged")
    void testRecordSavesEventWithGivenFields() {
        service.record(
                CAMPAIGN_ID,
                APPLICATION_ID,
                null,
                ApplicationHistoryEventType.CAMPAIGN_APPLIED,
                CollaborationStatus.APPLIED,
                ApplicationHistoryActorType.CREATOR,
                "creator_1",
                "Creator applied to this campaign",
                null,
                "/creator/deals/" + APPLICATION_ID,
                APPLICATION_ID);

        ArgumentCaptor<ApplicationHistoryEvent> captor =
                ArgumentCaptor.forClass(ApplicationHistoryEvent.class);
        verify(repository).save(captor.capture());
        ApplicationHistoryEvent saved = captor.getValue();
        assertEquals(CAMPAIGN_ID, saved.getCampaignId());
        assertEquals(APPLICATION_ID, saved.getApplicationId());
        assertNull(saved.getDealRoomId());
        assertEquals(ApplicationHistoryEventType.CAMPAIGN_APPLIED, saved.getEventType());
        assertEquals(CollaborationStatus.APPLIED, saved.getEventStatus());
        assertEquals(ApplicationHistoryActorType.CREATOR, saved.getActorType());
        assertEquals("creator_1", saved.getActorId());
        assertEquals("Creator applied to this campaign", saved.getDescription());
        assertEquals("/creator/deals/" + APPLICATION_ID, saved.getTargetRoute());
        assertEquals(APPLICATION_ID, saved.getTargetId());
    }

    @Test
    @DisplayName("recordViewIfAbsent: first brand view writes an APPLICATION_VIEWED event")
    void testRecordViewIfAbsentWritesFirstView() {
        when(repository.existsByApplicationIdAndEventType(
                        APPLICATION_ID, ApplicationHistoryEventType.APPLICATION_VIEWED))
                .thenReturn(false);

        service.recordViewIfAbsent(
                CAMPAIGN_ID, APPLICATION_ID, BRAND_USER_ID, "Brand viewed the application",
                CollaborationStatus.APPLIED);

        ArgumentCaptor<ApplicationHistoryEvent> captor =
                ArgumentCaptor.forClass(ApplicationHistoryEvent.class);
        verify(repository, times(1)).save(captor.capture());
        assertEquals(ApplicationHistoryEventType.APPLICATION_VIEWED, captor.getValue().getEventType());
        assertEquals(ApplicationHistoryActorType.BRAND, captor.getValue().getActorType());
        assertEquals(BRAND_USER_ID, captor.getValue().getActorId());
    }

    /** The exit-test requirement: a second brand view must not create a duplicate row. */
    @Test
    @DisplayName("recordViewIfAbsent: a second brand view is a no-op — no duplicate row")
    void testRecordViewIfAbsentIsIdempotentOnSecondView() {
        when(repository.existsByApplicationIdAndEventType(
                        APPLICATION_ID, ApplicationHistoryEventType.APPLICATION_VIEWED))
                .thenReturn(true);

        service.recordViewIfAbsent(
                CAMPAIGN_ID, APPLICATION_ID, BRAND_USER_ID, "Brand viewed the application",
                CollaborationStatus.APPLIED);

        verify(repository, never()).save(any());
    }
}
