package com.influora.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiResponse;
import com.influora.domain.entity.EmailPreference;
import com.influora.domain.entity.Notification;
import com.influora.domain.enums.UserType;
import com.influora.repository.EmailPreferenceRepository;
import com.influora.repository.NotificationRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.notification.UnsubscribeTokenService;
import com.influora.web.dto.notification.NotificationDtos.NotificationListResponse;
import com.influora.web.dto.notification.NotificationDtos.PreferencesResponse;
import com.influora.web.dto.notification.NotificationDtos.SetPreferenceRequest;
import com.influora.web.dto.notification.NotificationDtos.SetPreferenceResponse;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;

/**
 * Tests for NotificationController's /preferences endpoints (GET/POST), added to close the
 * FE<->BE contract gap where src/lib/api.ts notifications.getPreferences/setPreference called a
 * route that didn't exist. Priority: auth-scoping — userId must come only from AuthPrincipal,
 * never from the request body, so one user can never read or write another user's preferences.
 */
@ExtendWith(MockitoExtension.class)
class NotificationControllerTest {

    private static final String USER_A = "01HWXYZUSERAAAAAAAAAA";
    private static final String USER_B = "01HWXYZUSERBBBBBBBBBB";

    @Mock private NotificationRepository notificationRepository;
    @Mock private EmailPreferenceRepository emailPreferenceRepository;
    @Mock private UnsubscribeTokenService unsubscribeTokenService;

    private NotificationController controller;

    private static AuthPrincipal principal(String userId) {
        return new AuthPrincipal(userId, userId + "@example.com", UserType.CREATOR, null);
    }

    @BeforeEach
    void setUp() {
        controller =
                new NotificationController(
                        notificationRepository, emailPreferenceRepository, unsubscribeTokenService);
    }

    @Test
    @DisplayName("list: wraps NotificationListResponse in the standard {success,data} envelope")
    void listWrapsResponseInEnvelope() {
        Notification n =
                Notification.builder()
                        .id("01NOTIF000000000000001")
                        .userId(USER_A)
                        .eventType("escrow.funded")
                        .title("Escrow funded")
                        .body("Your funds are secured")
                        .build();
        when(notificationRepository.findByUserIdOrdered(eq(USER_A), any()))
                .thenReturn(new PageImpl<>(List.of(n), PageRequest.of(0, 20), 1));
        when(notificationRepository.countByUserIdAndIsReadFalse(USER_A)).thenReturn(1L);

        ResponseEntity<ApiResponse<NotificationListResponse>> response =
                controller.list(principal(USER_A), 0, 20);

        assertThat(response.getBody()).isNotNull();
        // [BUG FIX, feature-audit-brand-creator-2026-07-23.md P2] Previously returned the bare
        // NotificationListResponse with no envelope at all -- src/lib/api.ts's http.request throws
        // an ApiError on any 200 response missing success:true.
        assertThat(response.getBody().success()).isTrue();
        assertThat(response.getBody().data().notifications()).hasSize(1);
        assertThat(response.getBody().data().notifications().get(0).eventType()).isEqualTo("escrow.funded");
        assertThat(response.getBody().data().unreadCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("getPreferences: scopes the repository lookup to the authenticated user only")
    void getPreferencesScopedToCallingUser() {
        EmailPreference ownRow =
                EmailPreference.builder()
                        .id("01PREF0000000000000001")
                        .userId(USER_A)
                        .eventType("proposal.accepted")
                        .unsubscribed(true)
                        .build();
        when(emailPreferenceRepository.findByUserId(USER_A)).thenReturn(List.of(ownRow));

        ResponseEntity<ApiResponse<PreferencesResponse>> response =
                controller.getPreferences(principal(USER_A));

        // Only USER_A's id is ever passed to the repository -- USER_B's data is never queried.
        verify(emailPreferenceRepository, times(1)).findByUserId(USER_A);
        verify(emailPreferenceRepository, never()).findByUserId(USER_B);

        assertThat(response.getBody()).isNotNull();
        // [BUG FIX, feature-audit-brand-creator-2026-07-23.md P2] getPreferences now wraps its
        // response in the standard {success,data,error} envelope like every other controller --
        // this proves the wrap didn't change the data shape inside, just added the envelope.
        assertThat(response.getBody().success()).isTrue();
        assertThat(response.getBody().data().preferences()).hasSize(1);
        assertThat(response.getBody().data().preferences().get(0).eventType()).isEqualTo("proposal.accepted");
        assertThat(response.getBody().data().preferences().get(0).unsubscribed()).isTrue();
    }

    @Test
    @DisplayName("getPreferences: two different callers never see each other's rows")
    void getPreferencesDoesNotLeakAcrossUsers() {
        EmailPreference rowA =
                EmailPreference.builder()
                        .id("01PREF0000000000000001")
                        .userId(USER_A)
                        .eventType("payout.released")
                        .unsubscribed(false)
                        .build();
        EmailPreference rowB =
                EmailPreference.builder()
                        .id("01PREF0000000000000002")
                        .userId(USER_B)
                        .eventType("payout.released")
                        .unsubscribed(true)
                        .build();
        when(emailPreferenceRepository.findByUserId(USER_A)).thenReturn(List.of(rowA));
        when(emailPreferenceRepository.findByUserId(USER_B)).thenReturn(List.of(rowB));

        PreferencesResponse asA = controller.getPreferences(principal(USER_A)).getBody().data();
        PreferencesResponse asB = controller.getPreferences(principal(USER_B)).getBody().data();

        assertThat(asA.preferences().get(0).unsubscribed()).isFalse();
        assertThat(asB.preferences().get(0).unsubscribed()).isTrue();
    }

    @Test
    @DisplayName("setPreference: upserts using the authenticated user's id, not any request field")
    void setPreferenceUsesPrincipalUserIdNotRequestBody() {
        when(emailPreferenceRepository.findByUserIdAndEventType(USER_A, "marketing"))
                .thenReturn(Optional.empty());

        SetPreferenceRequest request = new SetPreferenceRequest("marketing", false);
        ResponseEntity<SetPreferenceResponse> response =
                controller.setPreference(principal(USER_A), request);

        ArgumentCaptor<EmailPreference> saved = ArgumentCaptor.forClass(EmailPreference.class);
        verify(emailPreferenceRepository).save(saved.capture());

        // SetPreferenceRequest carries no userId field at all -- the saved row's userId can only
        // have come from the AuthPrincipal, which is exactly what prevents IDOR here.
        assertThat(saved.getValue().getUserId()).isEqualTo(USER_A);
        assertThat(saved.getValue().getEventType()).isEqualTo("marketing");
        assertThat(saved.getValue().isUnsubscribed()).isTrue(); // subscribed=false -> unsubscribed=true

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isTrue();
        assertThat(response.getBody().unsubscribed()).isTrue();

        verify(emailPreferenceRepository, never()).findByUserIdAndEventType(eq(USER_B), any());
    }

    @Test
    @DisplayName("setPreference: updates the caller's existing row in place rather than creating a new one")
    void setPreferenceUpdatesExistingRowForCaller() {
        EmailPreference existing =
                EmailPreference.builder()
                        .id("01PREF0000000000000003")
                        .userId(USER_A)
                        .eventType("*")
                        .unsubscribed(false)
                        .build();
        when(emailPreferenceRepository.findByUserIdAndEventType(USER_A, "*"))
                .thenReturn(Optional.of(existing));

        controller.setPreference(principal(USER_A), new SetPreferenceRequest("*", false));

        ArgumentCaptor<EmailPreference> saved = ArgumentCaptor.forClass(EmailPreference.class);
        verify(emailPreferenceRepository).save(saved.capture());

        assertThat(saved.getValue().getId()).isEqualTo("01PREF0000000000000003");
        assertThat(saved.getValue().getUserId()).isEqualTo(USER_A);
        assertThat(saved.getValue().isUnsubscribed()).isTrue();
    }
}
