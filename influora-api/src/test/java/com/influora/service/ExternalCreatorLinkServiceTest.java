package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.CreatorConnectionRequest;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.ExternalCreator;
import com.influora.domain.enums.ConnectionRequestStatus;
import com.influora.domain.enums.ExternalCreatorSource;
import com.influora.repository.CreatorConnectionRequestRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.ExternalCreatorRepository;
import com.influora.repository.PlatformStatRepository;
import com.influora.service.notification.event.ConnectedCreatorJoinedEvent;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * T-CREATORCONNECT-0902 — the JOINED hook. Plain Mockito, no {@code @SpringBootTest}.
 */
@ExtendWith(MockitoExtension.class)
class ExternalCreatorLinkServiceTest {

    private static final String CREATOR_PROFILE_ID = "01HCREATORPROFILE00001";
    private static final String EXTERNAL_CREATOR_ID = "01HEXTCREATOR000000001";

    @Mock private ExternalCreatorRepository externalCreatorRepository;
    @Mock private CreatorConnectionRequestRepository connectionRequestRepository;
    @Mock private CreatorProfileRepository creatorProfileRepository;
    @Mock private PlatformStatRepository platformStatRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private CreatorProfile creatorProfile;

    @Captor private ArgumentCaptor<ConnectedCreatorJoinedEvent> eventCaptor;

    private ExternalCreatorLinkService service;

    @BeforeEach
    void setUp() {
        service =
                new ExternalCreatorLinkService(
                        externalCreatorRepository,
                        connectionRequestRepository,
                        creatorProfileRepository,
                        platformStatRepository,
                        eventPublisher);
    }

    private ExternalCreator externalCreator() {
        return ExternalCreator.builder()
                .id(EXTERNAL_CREATOR_ID)
                .source(ExternalCreatorSource.BUSINESS_DISCOVERY)
                .igUsername("foodie.mumbai")
                .build();
    }

    private CreatorConnectionRequest request(String id, String workspaceId, ConnectionRequestStatus status) {
        CreatorConnectionRequest r =
                CreatorConnectionRequest.builder()
                        .id(id)
                        .workspaceId(workspaceId)
                        .requestedByUserId("01HUSER0000000000000" + id.charAt(id.length() - 1))
                        .externalCreatorId(EXTERNAL_CREATOR_ID)
                        .build();
        if (status == ConnectionRequestStatus.CONTACTED) {
            r.markContacted("01HADMIN0000000000001", null);
        }
        return r;
    }

    @Test
    @DisplayName("onCreatorIdentified: matches by ig_account_id, flips PENDING+CONTACTED requests to JOINED, publishes one event per request")
    void onCreatorIdentified_flipsAllOpenRequestsAndPublishesOnePerRequest() {
        ExternalCreator external = externalCreator();
        when(externalCreatorRepository.findByIgAccountId("17841400000000001")).thenReturn(Optional.of(external));

        CreatorConnectionRequest pending = request("01HREQUEST0000000001", "01HWORKSPACE0000000001", ConnectionRequestStatus.PENDING);
        CreatorConnectionRequest contacted = request("01HREQUEST0000000002", "01HWORKSPACE0000000002", ConnectionRequestStatus.CONTACTED);
        when(connectionRequestRepository.findByExternalCreatorIdAndStatusIn(
                        EXTERNAL_CREATOR_ID, List.of(ConnectionRequestStatus.PENDING, ConnectionRequestStatus.CONTACTED)))
                .thenReturn(List.of(pending, contacted));

        when(creatorProfile.getDisplayName()).thenReturn("Foodie Mumbai");
        when(creatorProfileRepository.findById(CREATOR_PROFILE_ID)).thenReturn(Optional.of(creatorProfile));

        service.onCreatorIdentified(CREATOR_PROFILE_ID, "foodie.mumbai", "17841400000000001");

        assertEquals(ConnectionRequestStatus.JOINED, pending.getStatus());
        assertEquals(ConnectionRequestStatus.JOINED, contacted.getStatus());
        verify(connectionRequestRepository, times(2)).save(any(CreatorConnectionRequest.class));

        verify(eventPublisher, times(2)).publishEvent(eventCaptor.capture());
        List<ConnectedCreatorJoinedEvent> events = eventCaptor.getAllValues();
        assertEquals(2, events.size());
        assertEquals("01HREQUEST0000000001", events.get(0).entityId());
        assertEquals("01HREQUEST0000000002", events.get(1).entityId());
        assertEquals(CREATOR_PROFILE_ID, events.get(0).creatorProfileId());
        assertEquals("foodie.mumbai", events.get(0).igUsername());
    }

    @Test
    @DisplayName(
            "Q5.4 (Critical): a username-only match on a row with NO existing link is refused — "
                    + "ig_account_id is required to establish the FIRST link")
    void onCreatorIdentified_usernameOnlyMatch_onUnlinkedRow_isRefused() {
        ExternalCreator external = externalCreator();
        when(externalCreatorRepository.findByIgUsernameIgnoreCase("foodie.mumbai")).thenReturn(Optional.of(external));

        // No igAccountId supplied — exactly the shape CreatorProfileService#applyUsername used to
        // call this with before Q5.4 removed that call site entirely. Even if some future caller
        // still reaches this method with a username-only match, the row must NOT be linked.
        service.onCreatorIdentified(CREATOR_PROFILE_ID, "@Foodie.Mumbai", null);

        verify(externalCreatorRepository, never()).findByIgAccountId(any());
        assertEquals(null, external.getLinkedCreatorProfileId());
        assertEquals(com.influora.domain.enums.ExternalCreatorStatus.UNVERIFIED, external.getStatus());
        verify(externalCreatorRepository, never()).save(any());
        verify(connectionRequestRepository, never()).findByExternalCreatorIdAndStatusIn(any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName(
            "onCreatorIdentified: a username match on a row ALREADY linked to this same creatorProfileId"
                    + " re-confirms (not a first link, so the Q5.4 guard does not apply)")
    void onCreatorIdentified_usernameMatch_onAlreadyLinkedRow_reconfirms() {
        ExternalCreator external = externalCreator();
        external.markJoined(CREATOR_PROFILE_ID); // already linked to this exact profile
        when(externalCreatorRepository.findByIgUsernameIgnoreCase("foodie.mumbai")).thenReturn(Optional.of(external));
        when(connectionRequestRepository.findByExternalCreatorIdAndStatusIn(any(), any())).thenReturn(List.of());

        service.onCreatorIdentified(CREATOR_PROFILE_ID, "@Foodie.Mumbai", null);

        verify(externalCreatorRepository, never()).findByIgAccountId(any());
        assertEquals(CREATOR_PROFILE_ID, external.getLinkedCreatorProfileId());
    }

    @Test
    @DisplayName("onCreatorIdentified: no match found is a silent no-op, never throws")
    void onCreatorIdentified_noMatch_isNoOp() {
        when(externalCreatorRepository.findByIgUsernameIgnoreCase("unknownhandle")).thenReturn(Optional.empty());

        service.onCreatorIdentified(CREATOR_PROFILE_ID, "unknownhandle", null);

        verify(externalCreatorRepository, never()).save(any());
        verify(connectionRequestRepository, never()).findByExternalCreatorIdAndStatusIn(any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("onCreatorIdentified: both id and username blank is a no-op, no repository calls")
    void onCreatorIdentified_bothBlank_isNoOp() {
        service.onCreatorIdentified(CREATOR_PROFILE_ID, null, null);

        verify(externalCreatorRepository, never()).findByIgAccountId(any());
        verify(externalCreatorRepository, never()).findByIgUsernameIgnoreCase(any());
    }
}
