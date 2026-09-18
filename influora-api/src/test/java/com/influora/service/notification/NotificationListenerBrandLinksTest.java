package com.influora.service.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

import com.influora.integration.msg91.Msg91EmailClient;
import com.influora.repository.CreatorConnectionRequestRepository;
import com.influora.repository.UserRepository;
import com.influora.repository.WorkspaceMemberRepository;
import com.influora.service.ErrorLogService;
import com.influora.service.notification.event.ApplicationCreatedEvent;
import com.influora.service.notification.event.BidCounteredEvent;
import com.influora.service.notification.event.ContractReadyForEscrowEvent;
import com.influora.service.notification.event.ContractSignedEvent;
import com.influora.service.notification.event.CreatorFirstMessageEvent;
import com.influora.service.notification.event.CreditsExhaustedEvent;
import com.influora.service.notification.event.CreditsResetEvent;
import com.influora.service.notification.event.DeliverableSubmittedEvent;
import com.influora.service.notification.event.ProposalAcceptedEvent;
import com.influora.service.notification.event.ShipmentReceivedEvent;
import com.influora.service.notification.event.SubscriptionHaltedEvent;
import com.influora.service.notification.event.SubscriptionPaymentFailedEvent;
import com.influora.service.notification.event.WalletLowBalanceEvent;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The SPA navigates to a notification's {@code link} verbatim, so every brand link has to be a
 * route that exists. These used to point at per-entity pages the brand app never had
 * (/brand/proposals/{id}, /brand/collaborations/{id}, /brand/deliverables/{id}, ...), so every
 * deal-lifecycle notification opened the 404 page. Each case pins the exact link AND which id it
 * is built from — two of these events carry a contract or deliverable id, not a collaboration id.
 *
 * <p>The route table itself is checked from the frontend side, against src/App.tsx, by
 * src/__tests__/notification-links-resolve.test.ts.
 */
@ExtendWith(MockitoExtension.class)
class NotificationListenerBrandLinksTest {

    private static final String BRAND_USER = "01HBRANDUSER0000000001";
    private static final String WORKSPACE = "01HWORKSPACE0000000001";
    private static final String COLLAB = "01HCOLLAB000000000001";
    private static final String CONTRACT = "01HCONTRACT0000000001";
    private static final String DELIVERABLE = "01HDELIVERABLE00000001";

    @Mock private NotificationService notificationService;
    @Mock private UserRepository userRepository;
    @Mock private Msg91EmailClient msg91EmailClient;
    @Mock private CreatorConnectionRequestRepository connectionRequestRepository;
    @Mock private WorkspaceMemberRepository workspaceMemberRepository;
    @Mock private ErrorLogService errorLogService;

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
        // Recipient resolution is not under test; an unresolvable user just yields a null email.
        lenient().when(userRepository.findById(anyString())).thenReturn(Optional.empty());
    }

    private String notifiedLink() {
        ArgumentCaptor<String> link = ArgumentCaptor.forClass(String.class);
        verify(notificationService)
                .notify(any(), anyString(), anyString(), link.capture(), any(), anyString(), any());
        return link.getValue();
    }

    private String inAppLink() {
        ArgumentCaptor<String> link = ArgumentCaptor.forClass(String.class);
        verify(notificationService).notifyInApp(any(), anyString(), anyString(), link.capture());
        return link.getValue();
    }

    @Test
    void newApplication_opensTheDealRoom_notACampaignSubpage() {
        // entityId is the COLLABORATION id; the old link spliced it into /brand/campaigns/{id}/...
        listener.on(new ApplicationCreatedEvent(BRAND_USER, WORKSPACE, COLLAB, "Asha", "Summer"));
        assertEquals("/brand/chat?deal=" + COLLAB, notifiedLink());
    }

    @Test
    void counterBid_opensTheDealRoom() {
        listener.on(new BidCounteredEvent(BRAND_USER, WORKSPACE, COLLAB, "Asha", "Summer", "5000 INR"));
        assertEquals("/brand/chat?deal=" + COLLAB, notifiedLink());
    }

    @Test
    void proposalAccepted_opensTheDealRoomContractPanel() {
        listener.on(new ProposalAcceptedEvent(BRAND_USER, WORKSPACE, COLLAB, "Asha", "Summer"));
        assertEquals("/brand/chat?deal=" + COLLAB + "&tab=contract", notifiedLink());
    }

    @Test
    void contractSigned_opensThatContractOnTheContractsPage() {
        listener.on(
                new ContractSignedEvent(
                        BRAND_USER, WORKSPACE, CONTRACT, "brand@example.com", "Asha", "Summer", "https://dl"));
        assertEquals("/brand/contracts?contract=" + CONTRACT, notifiedLink());
    }

    @Test
    void contractReadyForFunding_opensThatContractOnTheContractsPage() {
        listener.on(new ContractReadyForEscrowEvent(BRAND_USER, WORKSPACE, CONTRACT, "Summer"));
        assertEquals("/brand/contracts?contract=" + CONTRACT, notifiedLink());
    }

    @Test
    void deliverableSubmitted_usesTheCollaborationId_notTheDeliverableId() {
        listener.on(
                new DeliverableSubmittedEvent(
                        BRAND_USER, WORKSPACE, DELIVERABLE, COLLAB, "Asha", "Summer", "INSTAGRAM_REEL"));
        assertEquals("/brand/chat?deal=" + COLLAB + "&tab=deliverables", notifiedLink());
    }

    @Test
    void productReceived_opensTheDealRoom() {
        listener.on(new ShipmentReceivedEvent(BRAND_USER, WORKSPACE, COLLAB, "Asha", "Serum"));
        assertEquals("/brand/chat?deal=" + COLLAB, notifiedLink());
    }

    @Test
    void firstMessageFromCreator_opensTheDealRoom() {
        listener.on(new CreatorFirstMessageEvent(BRAND_USER, WORKSPACE, COLLAB, "Asha"));
        assertEquals("/brand/chat?deal=" + COLLAB, notifiedLink());
    }

    @Test
    void lowBalance_opensTheWalletPage() {
        listener.on(new WalletLowBalanceEvent(BRAND_USER, WORKSPACE, "01HWALLET00000000000001", "INR 120"));
        assertEquals("/brand/wallet", notifiedLink());
    }

    @Test
    void subscriptionHalted_opensBillingSettings() {
        listener.on(new SubscriptionHaltedEvent(BRAND_USER, WORKSPACE, "01HSUB0000000000000001", "b@example.com"));
        assertEquals("/brand/settings/billing", notifiedLink());
    }

    @Test
    void subscriptionPaymentFailed_opensBillingSettings() {
        listener.on(
                new SubscriptionPaymentFailedEvent(
                        BRAND_USER, WORKSPACE, "01HSUB0000000000000001", "b@example.com"));
        assertEquals("/brand/settings/billing", notifiedLink());
    }

    @Test
    void creditsExhausted_opensBillingSettings_whereCreditsAreShown() {
        listener.on(new CreditsExhaustedEvent(BRAND_USER, WORKSPACE, "01HCREDITS000000000001"));
        assertEquals("/brand/settings/billing", notifiedLink());
    }

    @Test
    void creditsReset_opensBillingSettings_whereCreditsAreShown() {
        listener.on(new CreditsResetEvent(BRAND_USER, WORKSPACE, "01HCREDITS000000000001", 150));
        assertEquals("/brand/settings/billing", inAppLink());
    }
}
