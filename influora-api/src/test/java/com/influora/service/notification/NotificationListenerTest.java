package com.influora.service.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.domain.entity.CreatorConnectionRequest;
import com.influora.domain.entity.User;
import com.influora.domain.entity.WorkspaceMember;
import com.influora.domain.enums.ErrorLogSeverity;
import com.influora.domain.enums.MemberRole;
import com.influora.integration.msg91.Msg91EmailClient;
import com.influora.repository.CreatorConnectionRequestRepository;
import com.influora.repository.UserRepository;
import com.influora.repository.WorkspaceMemberRepository;
import com.influora.service.ErrorLogService;
import com.influora.service.notification.event.ConnectedCreatorJoinedEvent;
import com.influora.service.notification.event.CreatorConnectionRequestedEvent;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * T-CREATORCONNECT-0902 — Q3.2 (stale-requester fallback) and Q3.3 (admin email retry). Plain
 * Mockito; {@code NotificationService} is mocked so these tests focus on recipient resolution and
 * retry behavior, not the outbox pipeline itself.
 */
@ExtendWith(MockitoExtension.class)
class NotificationListenerTest {

    private static final String WORKSPACE_ID = "01HWORKSPACE0000000001";
    private static final String REQUEST_ID = "01HREQUEST0000000001";
    private static final String REMOVED_USER_ID = "01HUSERREMOVED0000001";
    private static final String OWNER_USER_ID = "01HUSEROWNER000000001";

    @Mock private NotificationService notificationService;
    @Mock private UserRepository userRepository;
    @Mock private Msg91EmailClient msg91EmailClient;
    @Mock private CreatorConnectionRequestRepository connectionRequestRepository;
    @Mock private WorkspaceMemberRepository workspaceMemberRepository;
    @Mock private ErrorLogService errorLogService;
    @Mock private User ownerUser;

    private NotificationListener listener;

    @BeforeEach
    void setUp() {
        listener =
                new NotificationListener(
                        notificationService,
                        userRepository,
                        msg91EmailClient,
                        connectionRequestRepository,
                        workspaceMemberRepository,
                        errorLogService,
                        "admin@example.com",
                        "https://app.example.com");
    }

    private CreatorConnectionRequest requestFor(String requestedByUserId) {
        return CreatorConnectionRequest.builder()
                .id(REQUEST_ID)
                .workspaceId(WORKSPACE_ID)
                .requestedByUserId(requestedByUserId)
                .externalCreatorId("01HEXTCREATOR000000001")
                .build();
    }

    private ConnectedCreatorJoinedEvent joinedEvent(String userId) {
        return new ConnectedCreatorJoinedEvent(
                userId, WORKSPACE_ID, REQUEST_ID, "Foodie Mumbai", "foodie.mumbai", "01HCREATORPROFILE001");
    }

    @Test
    @DisplayName(
            "Q3.2: original requester still resolves — no fallback lookup, joined_notified_at stamped")
    void connectedCreatorJoined_originalRequesterResolves_noFallback() {
        when(userRepository.findById(REMOVED_USER_ID)).thenReturn(Optional.of(ownerUser));
        when(ownerUser.getEmail()).thenReturn("brand-user@example.com");
        when(connectionRequestRepository.findById(REQUEST_ID))
                .thenReturn(Optional.of(requestFor(REMOVED_USER_ID)));

        listener.on(joinedEvent(REMOVED_USER_ID));

        verify(workspaceMemberRepository, never())
                .findFirstByWorkspaceIdAndRoleAndActiveTrue(anyString(), any(MemberRole.class));
        verify(notificationService)
                .notify(
                        any(),
                        anyString(),
                        anyString(),
                        anyString(),
                        eq("brand-user@example.com"),
                        anyString(),
                        any());
        verify(connectionRequestRepository).save(any(CreatorConnectionRequest.class));
    }

    @Test
    @DisplayName(
            "Q3.2: original requester removed (emailOf null) — falls back to the workspace owner and"
                    + " still stamps joined_notified_at")
    void connectedCreatorJoined_requesterRemoved_fallsBackToOwner() {
        when(userRepository.findById(REMOVED_USER_ID)).thenReturn(Optional.empty());
        WorkspaceMember owner = WorkspaceMember.owner("01HMEMBER0000000001", WORKSPACE_ID, OWNER_USER_ID);
        when(workspaceMemberRepository.findFirstByWorkspaceIdAndRoleAndActiveTrue(
                        WORKSPACE_ID, MemberRole.OWNER))
                .thenReturn(Optional.of(owner));
        when(userRepository.findById(OWNER_USER_ID)).thenReturn(Optional.of(ownerUser));
        when(ownerUser.getEmail()).thenReturn("owner@example.com");
        CreatorConnectionRequest request = requestFor(REMOVED_USER_ID);
        when(connectionRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request));

        listener.on(joinedEvent(REMOVED_USER_ID));

        verify(notificationService)
                .notify(
                        any(), anyString(), anyString(), anyString(), eq("owner@example.com"), anyString(), any());
        assertEquals(REQUEST_ID, request.getId());
        verify(connectionRequestRepository).save(request);
    }

    @Test
    @DisplayName(
            "Q3.2: requester removed AND no active workspace owner/admin — email queued with null"
                    + " recipient, joined_notified_at is NOT stamped (the miss stays visible)")
    void connectedCreatorJoined_noResolvableRecipient_doesNotStampNotified() {
        when(userRepository.findById(REMOVED_USER_ID)).thenReturn(Optional.empty());
        when(workspaceMemberRepository.findFirstByWorkspaceIdAndRoleAndActiveTrue(
                        WORKSPACE_ID, MemberRole.OWNER))
                .thenReturn(Optional.empty());
        when(workspaceMemberRepository.findFirstByWorkspaceIdAndRoleAndActiveTrue(
                        WORKSPACE_ID, MemberRole.ADMIN))
                .thenReturn(Optional.empty());
        CreatorConnectionRequest request = requestFor(REMOVED_USER_ID);
        when(connectionRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request));

        listener.on(joinedEvent(REMOVED_USER_ID));

        verify(notificationService)
                .notify(any(), anyString(), anyString(), anyString(), isNull(), anyString(), any());
        verify(connectionRequestRepository, never()).save(any());
        assertNull(request.getJoinedNotifiedAt());
    }

    @Test
    @DisplayName(
            "Q6.5: joined-email deep link and campaign_url carry the URL-encoded IG handle, not just"
                    + " creatorId — campaign-form.tsx must not fall back to the divergent Influora"
                    + " username for dotted handles")
    @SuppressWarnings("unchecked")
    void connectedCreatorJoined_linksCarryEncodedIgHandle() {
        when(userRepository.findById(REMOVED_USER_ID)).thenReturn(Optional.of(ownerUser));
        when(ownerUser.getEmail()).thenReturn("brand-user@example.com");
        when(connectionRequestRepository.findById(REQUEST_ID))
                .thenReturn(Optional.of(requestFor(REMOVED_USER_ID)));

        listener.on(joinedEvent(REMOVED_USER_ID));

        String expectedIgParam = "&ig=" + URLEncoder.encode("foodie.mumbai", StandardCharsets.UTF_8);

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> mapCaptor = ArgumentCaptor.forClass(Map.class);
        verify(notificationService)
                .notify(
                        any(),
                        anyString(),
                        anyString(),
                        pathCaptor.capture(),
                        eq("brand-user@example.com"),
                        anyString(),
                        mapCaptor.capture());

        assertEquals(
                "/brand/campaigns/new?creatorId=01HCREATORPROFILE001" + expectedIgParam,
                pathCaptor.getValue());
        assertEquals(
                "https://app.example.com/brand/campaigns/new?creatorId=01HCREATORPROFILE001"
                        + expectedIgParam,
                mapCaptor.getValue().get("campaign_url"));
    }

    @Test
    @DisplayName(
            "Q6.5: null igUsername (event contract allows it) omits &ig= rather than emitting a"
                    + " literal 'null' into the brand-facing link")
    void connectedCreatorJoined_nullIgUsername_omitsIgParam() {
        when(userRepository.findById(REMOVED_USER_ID)).thenReturn(Optional.of(ownerUser));
        when(ownerUser.getEmail()).thenReturn("brand-user@example.com");
        when(connectionRequestRepository.findById(REQUEST_ID))
                .thenReturn(Optional.of(requestFor(REMOVED_USER_ID)));

        ConnectedCreatorJoinedEvent eventWithNoHandle =
                new ConnectedCreatorJoinedEvent(
                        REMOVED_USER_ID,
                        WORKSPACE_ID,
                        REQUEST_ID,
                        "Foodie Mumbai",
                        null,
                        "01HCREATORPROFILE001");

        listener.on(eventWithNoHandle);

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationService)
                .notify(
                        any(),
                        anyString(),
                        anyString(),
                        pathCaptor.capture(),
                        eq("brand-user@example.com"),
                        anyString(),
                        any());

        assertEquals("/brand/campaigns/new?creatorId=01HCREATORPROFILE001", pathCaptor.getValue());
    }

    private CreatorConnectionRequestedEvent adminEvent() {
        return new CreatorConnectionRequestedEvent(
                "01HUSERBRAND00000001", WORKSPACE_ID, REQUEST_ID, "Acme Co", "foodie.mumbai", 1000L, "please connect");
    }

    @Test
    @DisplayName("Q3.3: transient SMTP failure on the first attempt succeeds on retry, no error-log row")
    void adminEmail_retriesAndSucceeds() {
        when(msg91EmailClient.sendTemplateEmail(eq("admin@example.com"), anyString(), anyString()))
                .thenReturn(false)
                .thenReturn(true);

        listener.on(adminEvent());

        verify(msg91EmailClient, times(2))
                .sendTemplateEmail(eq("admin@example.com"), anyString(), anyString());
        verify(errorLogService, never())
                .record(any(), any(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("Q3.3: a persistent SMTP failure retries up to the bounded attempt count, then gives up")
    void adminEmail_persistentFailure_retriesBoundedNumberOfTimes() {
        when(msg91EmailClient.sendTemplateEmail(eq("admin@example.com"), anyString(), anyString()))
                .thenReturn(false);

        listener.on(adminEvent());

        verify(msg91EmailClient, times(3))
                .sendTemplateEmail(eq("admin@example.com"), anyString(), anyString());
    }

    @Test
    @DisplayName(
            "Q3.3: persistent SMTP failure writes an error_log row so a SUPER_ADMIN has something to"
                    + " look at — not just a log line nothing surfaces")
    void adminEmail_persistentFailure_recordsErrorLogRow() {
        when(msg91EmailClient.sendTemplateEmail(eq("admin@example.com"), anyString(), anyString()))
                .thenReturn(false);

        listener.on(adminEvent());

        verify(errorLogService)
                .record(
                        eq(ErrorLogSeverity.ERROR),
                        any(Throwable.class),
                        eq("admin.creator_connection_requested"),
                        anyString(),
                        isNull(),
                        eq("01HUSERBRAND00000001"));
    }
}
