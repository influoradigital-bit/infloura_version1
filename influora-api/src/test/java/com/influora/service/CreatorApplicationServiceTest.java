package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.ApplicationHistoryEvent;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.enums.ApplicationHistoryActorType;
import com.influora.domain.enums.ApplicationHistoryEventType;
import com.influora.domain.enums.CollaborationStatus;
import com.influora.repository.ApplicationHistoryEventRepository;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.creatorcampaign.CreatorApplicationDtos.ApplicationHistoryEventItem;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * {@code GET /creator/applications/{dealId}/history} — ownership (Kabir R1: never a client-
 * supplied creator id, resolved through {@code CreatorContextService} only) and chronological
 * ordering pass-through from the repository.
 */
@ExtendWith(MockitoExtension.class)
class CreatorApplicationServiceTest {

    private static final String CAMPAIGN_ID = "01HCAMPAIGN1234567890";
    private static final String DEAL_ID = "01HDEAL00000000000001";
    private static final String CREATOR_USER_ID = "01HCREATORUSER1234567";

    @Mock private CollaborationRepository collaborationRepository;
    @Mock private CampaignRepository campaignRepository;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private CreatorContextService creatorContext;
    @Mock private ApplicationHistoryEventRepository applicationHistoryEventRepository;
    @Mock private AuthPrincipal principal;

    private CreatorApplicationService service;

    @BeforeEach
    void setUp() {
        service =
                new CreatorApplicationService(
                        collaborationRepository,
                        campaignRepository,
                        workspaceRepository,
                        creatorContext,
                        applicationHistoryEventRepository);
        CreatorProfile creator = CreatorProfile.newForUser("profile1", CREATOR_USER_ID, "Test Creator");
        when(creatorContext.requireCreatorProfile(principal)).thenReturn(creator);
    }

    private static ApplicationHistoryEvent event(ApplicationHistoryEventType type) {
        return ApplicationHistoryEvent.create(
                "hist_" + type,
                CAMPAIGN_ID,
                DEAL_ID,
                null,
                type,
                CollaborationStatus.APPLIED,
                ApplicationHistoryActorType.CREATOR,
                CREATOR_USER_ID,
                type.name(),
                null,
                null,
                null);
    }

    /** The exit-test requirement: the history endpoint returns events in chronological order. */
    @Test
    @DisplayName("history: returns events in the order the repository provides (chronological)")
    void testHistoryReturnsEventsInChronologicalOrder() {
        when(collaborationRepository.findByIdAndCreatorId(DEAL_ID, CREATOR_USER_ID))
                .thenReturn(
                        Optional.of(Collaboration.apply(DEAL_ID, CAMPAIGN_ID, CREATOR_USER_ID, null, "INR")));
        ApplicationHistoryEvent applied = event(ApplicationHistoryEventType.CAMPAIGN_APPLIED);
        ApplicationHistoryEvent viewed = event(ApplicationHistoryEventType.APPLICATION_VIEWED);
        ApplicationHistoryEvent accepted = event(ApplicationHistoryEventType.APPLICATION_ACCEPTED);
        // The repository method is itself named/ordered ...OrderByCreatedAtAscSequenceNoAsc — this
        // test pins that the service passes that order straight through rather than re-sorting
        // it. Real tiebreak correctness on a created_at collision is proven separately, against a
        // genuine H2-backed repository, by ApplicationHistoryEventOrderingTest.
        when(applicationHistoryEventRepository.findByApplicationIdOrderByCreatedAtAscSequenceNoAsc(DEAL_ID))
                .thenReturn(List.of(applied, viewed, accepted));

        List<ApplicationHistoryEventItem> items = service.history(principal, DEAL_ID);

        assertEquals(3, items.size());
        assertEquals("CAMPAIGN_APPLIED", items.get(0).eventType());
        assertEquals("APPLICATION_VIEWED", items.get(1).eventType());
        assertEquals("APPLICATION_ACCEPTED", items.get(2).eventType());
    }

    @Test
    @DisplayName("history: a dealId owned by a different creator 404s — never leaks existence")
    void testHistoryRejectsForeignDealAsNotFound() {
        when(collaborationRepository.findByIdAndCreatorId(DEAL_ID, CREATOR_USER_ID))
                .thenReturn(Optional.empty());

        ApiException ex =
                assertThrows(ApiException.class, () -> service.history(principal, DEAL_ID));

        assertEquals("DEAL_NOT_FOUND", ex.getCode());
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }
}
