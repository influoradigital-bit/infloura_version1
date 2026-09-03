package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.config.R2Properties;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.Contract;
import com.influora.domain.entity.DealMessage;
import com.influora.domain.entity.Deliverable;
import com.influora.domain.entity.EscrowHold;
import com.influora.domain.entity.Workspace;
import com.influora.domain.enums.DealMessageKind;
import com.influora.domain.enums.DealSenderType;
import com.influora.domain.enums.DeliverableStatus;
import com.influora.domain.enums.DeliverableType;
import com.influora.integration.storage.R2StorageService;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.ContractRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.repository.DealMessageRepository;
import com.influora.repository.DeliverableRepository;
import com.influora.repository.EscrowHoldRepository;
import com.influora.repository.ShipmentRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.notification.event.ContractPendingSignatureEvent;
import com.influora.service.notification.event.ContractSignedEvent;
import com.influora.service.notification.event.DeliverableSubmittedEvent;
import com.influora.service.notification.event.EscrowFundedEvent;
import com.influora.service.notification.event.PayoutReleasedEvent;
import com.influora.web.dto.deliverable.BrandDeliverableDtos.ReviseRequest;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * F-0288 (thin-event-log) — "a test asserting each lifecycle transition appends a system message to
 * the deal thread".
 *
 * <p>The DealMessage timeline is the only chronological record either party can read in the deal
 * room. When this record was opened exactly two system rows were ever written to it — {@code
 * doAccept}'s "… accepted the proposal" and {@code doReject}'s "… rejected: …" — so the thread
 * covered 2 of the ~11 lifecycle events the FRD lists, and everything after terms-agreed happened
 * in silence.
 *
 * <p>Two legs, and they are deliberately different in kind:
 *
 * <ul>
 *   <li><b>Leg 1 (handlers)</b> — each {@code @TransactionalEventListener} on {@link DealService}
 *       actually persists a {@code kind=system} {@link DealMessage} on the right collaboration.
 *       This is the direct reading of {@code missed_by} and is regression armour for the handlers
 *       that now exist. It also proves this harness can SEE a trail row, which is what stops leg 2
 *       from being vacuous: if leg 1 passes and leg 2 fails, the failure is a genuine missing row,
 *       not a broken test rig.
 *   <li><b>Leg 2 (coverage)</b> — a lifecycle transition that has no handler at all is invisible to
 *       leg 1, because there is nothing to call. Leg 2 therefore drives the real brand-side review
 *       services and asks whether what they publish is something the deal trail listens to. It is
 *       written against the DealService listener set by reflection rather than against a hardcoded
 *       list of event classes, so adding the missing signal makes it pass without editing it.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("F-0288 — every deal lifecycle transition lands on the deal thread")
class DealTrailCoverageTest {

    private static final String COLLAB_ID = "01HCOLLAB000000000001";
    private static final String CAMPAIGN_ID = "01HCAMPAIGN0000000001";
    private static final String WORKSPACE_ID = "01HWORKSPACE000000001";
    private static final String CREATOR_USER_ID = "01HCREATORUSER0000001";
    private static final String BRAND_USER_ID = "01HBRANDUSER000000001";
    private static final String CONTRACT_ID = "01HCONTRACT00000000001";
    private static final String HOLD_ID = "01HESCROWHOLD00000001";
    private static final String DELIVERABLE_ID = "01HDELIVERABLE0000001";

    // ---- DealService harness (leg 1) -----------------------------------------------------
    @Mock private CollaborationRepository collaborationRepository;
    @Mock private DealMessageRepository dealMessageRepository;
    @Mock private CampaignRepository campaignRepository;
    @Mock private CreatorProfileRepository creatorProfileRepository;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private ContractRepository contractRepository;
    @Mock private EscrowHoldRepository escrowHoldRepository;
    @Mock private DeliverableRepository deliverableRepository;
    @Mock private ShipmentRepository shipmentRepository;
    @Mock private CreatorContextService creatorContext;
    @Mock private BrandContextService brandContext;
    @Mock private IdempotencyService idempotencyService;
    @Mock private ApplicationEventPublisher dealEventPublisher;
    @Mock private DealMessageStreamRegistry messageStreamRegistry;
    @Mock private ApplicationHistoryService applicationHistoryService;

    // ---- BrandDeliverableService harness (leg 2) -----------------------------------------
    @Mock private R2StorageService r2StorageService;
    @Mock private R2Properties r2Properties;
    @Mock private EscrowService escrowService;
    @Mock private CollaborationLifecycleService collaborationLifecycleService;
    @Mock private com.influora.service.meera.MeeraInteractionLogService meeraInteractionLogService;
    @Mock private ApplicationEventPublisher reviewEventPublisher;
    @Mock private AuthPrincipal brandPrincipal;

    private DealService dealService;
    private BrandDeliverableService brandDeliverableService;

    @BeforeEach
    void setUp() {
        dealService =
                new DealService(
                        collaborationRepository,
                        dealMessageRepository,
                        campaignRepository,
                        creatorProfileRepository,
                        workspaceRepository,
                        contractRepository,
                        escrowHoldRepository,
                        deliverableRepository,
                        creatorContext,
                        brandContext,
                        idempotencyService,
                        dealEventPublisher,
                        messageStreamRegistry,
                        new CollaborationReviveService(
                                collaborationRepository,
                                contractRepository,
                                escrowHoldRepository,
                                shipmentRepository),
                        applicationHistoryService);

        brandDeliverableService =
                new BrandDeliverableService(
                        brandContext,
                        deliverableRepository,
                        r2StorageService,
                        r2Properties,
                        escrowService,
                        collaborationLifecycleService,
                        meeraInteractionLogService,
                        collaborationRepository,
                        applicationHistoryService);
        // Mirrors the Spring container: the publisher arrives through
        // ApplicationEventPublisherAware, not the constructor.
        brandDeliverableService.setApplicationEventPublisher(reviewEventPublisher);
    }

    // =======================================================================================
    // Leg 1 — every DealService lifecycle handler writes a system row on the deal thread.
    // =======================================================================================

    @Test
    @DisplayName("leg 1: contract generated / signed, escrow funded, deliverable submitted /"
            + " approved and payment released each append one system row")
    void everyPostAgreementHandlerAppendsASystemRow() {
        stubCollaborationLookups();

        int expected = 0;

        dealService.onContractGenerated(
                new ContractPendingSignatureEvent(
                        CREATOR_USER_ID, WORKSPACE_ID, CONTRACT_ID, "Acme", "Summer"));
        assertRowAppended("contract generated", ++expected);

        dealService.onContractSigned(
                new ContractSignedEvent(
                        CREATOR_USER_ID,
                        WORKSPACE_ID,
                        CONTRACT_ID,
                        "creator@example.com",
                        "Creator",
                        "Summer",
                        "https://example.test/contract.pdf"));
        assertRowAppended("contract signed", ++expected);

        dealService.onEscrowFunded(
                new EscrowFundedEvent(
                        CREATOR_USER_ID, WORKSPACE_ID, HOLD_ID, "Acme", "Summer"));
        assertRowAppended("escrow funded", ++expected);

        dealService.onDeliverableSubmitted(
                new DeliverableSubmittedEvent(
                        BRAND_USER_ID,
                        WORKSPACE_ID,
                        DELIVERABLE_ID,
                        "Creator",
                        "Summer",
                        DeliverableType.INSTAGRAM_REEL.name()));
        assertRowAppended("deliverable submitted", ++expected);

        dealService.onDeliverableApproved(
                new BrandDeliverableService.DeliverableApprovedEvent(DELIVERABLE_ID, COLLAB_ID));
        assertRowAppended("deliverable approved", ++expected);

        dealService.onPayoutReleased(
                new PayoutReleasedEvent(
                        CREATOR_USER_ID, WORKSPACE_ID, HOLD_ID, "Acme", "Summer", "10000 INR"));
        assertRowAppended("payment released", ++expected);
    }

    // =======================================================================================
    // Leg 2 — a lifecycle transition with no handler at all.
    // =======================================================================================

    @Test
    @DisplayName("leg 2 (control): brand approval publishes a signal the deal trail listens to")
    void approvalIsCarriedToTheDealTrail() {
        List<Object> published = runReview(ReviewAction.APPROVE);
        assertTrue(
                anyEventOnTheTrail(published),
                "control leg is itself broken: BrandDeliverableService#approve published "
                        + describe(published)
                        + " and DealService listens for "
                        + trailEventTypeNames()
                        + " — if this fails, leg 2's negative result below proves nothing");
    }

    @Test
    @DisplayName("leg 2: a revision request must also reach the deal thread")
    void revisionRequestIsCarriedToTheDealTrail() {
        List<Object> published = runReview(ReviewAction.REQUEST_REVISION);
        assertTrue(
                anyEventOnTheTrail(published),
                "REVISION_REQUESTED is a first-class CollaborationStatus and a real lifecycle"
                        + " transition (BrandDeliverableService#requestRevision ->"
                        + " CollaborationLifecycleService#onDeliverableReviewed), but it writes no"
                        + " system row to the deal thread: it published "
                        + describe(published)
                        + " and the DealService trail listens only for "
                        + trailEventTypeNames()
                        + ". A creator whose work is sent back sees nothing in the deal room —"
                        + " the same thin-event-log defect F-0288 records, in a transition the"
                        + " already-landed handlers do not cover.");
    }

    @Test
    @DisplayName("leg 2 guard: the reflected trail-listener set is not empty")
    void theTrailListenerSetIsDiscoverable() {
        assertFalse(
                trailEventTypes().isEmpty(),
                "no @TransactionalEventListener methods found on DealService — leg 2 would pass or"
                        + " fail for a reason that has nothing to do with F-0288");
    }

    // =======================================================================================
    // helpers
    // =======================================================================================

    private enum ReviewAction {
        APPROVE,
        REQUEST_REVISION
    }

    /** Drives the real brand review path and returns every event it published. */
    private List<Object> runReview(ReviewAction action) {
        Workspace workspace = Workspace.newBrand(WORKSPACE_ID, "Acme Brand", "acme", "Fashion", "SMB");
        when(brandContext.requireBrandWorkspace(brandPrincipal)).thenReturn(workspace);
        when(deliverableRepository.findByIdAndWorkspaceId(DELIVERABLE_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(submittedDeliverable()));
        when(collaborationRepository.findById(COLLAB_ID))
                .thenReturn(Optional.of(collaboration()));
        when(brandPrincipal.getUserId()).thenReturn(BRAND_USER_ID);
        lenient()
                .when(escrowService.tryReleaseOnApproval(any(), any()))
                .thenReturn(EscrowService.ReleaseOutcome.RELEASED);

        if (action == ReviewAction.APPROVE) {
            brandDeliverableService.approve(brandPrincipal, DELIVERABLE_ID);
        } else {
            brandDeliverableService.requestRevision(
                    brandPrincipal, DELIVERABLE_ID, new ReviseRequest("Please redo the intro"));
        }

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(reviewEventPublisher, org.mockito.Mockito.atLeast(0)).publishEvent(captor.capture());
        return new ArrayList<>(captor.getAllValues());
    }

    /** True if any published event is accepted by a DealService transactional listener. */
    private boolean anyEventOnTheTrail(List<Object> published) {
        Set<Class<?>> handled = trailEventTypes();
        for (Object event : published) {
            if (event == null) {
                continue;
            }
            for (Class<?> type : handled) {
                if (type.isInstance(event)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The event types {@link DealService} consumes as a transactional listener — read off the class
     * rather than hardcoded, so a new handler is picked up automatically.
     */
    private static Set<Class<?>> trailEventTypes() {
        Set<Class<?>> types = new LinkedHashSet<>();
        for (Method m : DealService.class.getDeclaredMethods()) {
            if (m.isAnnotationPresent(TransactionalEventListener.class)
                    && m.getParameterCount() == 1) {
                types.add(m.getParameterTypes()[0]);
            }
        }
        return types;
    }

    private static String trailEventTypeNames() {
        List<String> names = new ArrayList<>();
        for (Class<?> c : trailEventTypes()) {
            names.add(c.getSimpleName());
        }
        return names.toString();
    }

    private static String describe(List<Object> published) {
        if (published.isEmpty()) {
            return "no application events at all";
        }
        List<String> names = new ArrayList<>();
        for (Object o : published) {
            names.add(o == null ? "null" : o.getClass().getSimpleName());
        }
        return names.toString();
    }

    private void assertRowAppended(String transition, int expectedTotal) {
        ArgumentCaptor<DealMessage> captor = ArgumentCaptor.forClass(DealMessage.class);
        verify(dealMessageRepository, times(expectedTotal)).save(captor.capture());
        List<DealMessage> rows = captor.getAllValues();
        assertEquals(
                expectedTotal,
                rows.size(),
                "'" + transition + "' appended no system message to the deal thread");
        DealMessage row = rows.get(rows.size() - 1);
        assertEquals(
                DealMessageKind.system,
                row.getKind(),
                "'" + transition + "' wrote a row that is not a system message");
        assertEquals(
                DealSenderType.system,
                row.getSenderType(),
                "'" + transition + "' wrote a row that is not attributed to the system");
        assertEquals(
                COLLAB_ID,
                row.getCollaborationId(),
                "'" + transition + "' wrote its row on the wrong deal thread");
        assertTrue(
                row.getContent() != null && !row.getContent().isBlank(),
                "'" + transition + "' wrote an empty system message");
    }

    private void stubCollaborationLookups() {
        Contract contract = org.mockito.Mockito.mock(Contract.class);
        when(contract.getCollaborationId()).thenReturn(COLLAB_ID);
        when(contract.getVersion()).thenReturn(1);
        when(contractRepository.findById(CONTRACT_ID)).thenReturn(Optional.of(contract));

        when(collaborationRepository.findById(COLLAB_ID)).thenReturn(Optional.of(collaboration()));

        EscrowHold hold = org.mockito.Mockito.mock(EscrowHold.class);
        when(hold.getCollaborationId()).thenReturn(COLLAB_ID);
        when(hold.getAmount()).thenReturn(new BigDecimal("10000"));
        when(hold.getCurrency()).thenReturn("INR");
        when(escrowHoldRepository.findById(HOLD_ID)).thenReturn(Optional.of(hold));

        Deliverable deliverable = org.mockito.Mockito.mock(Deliverable.class);
        when(deliverable.getCollaborationId()).thenReturn(COLLAB_ID);
        when(deliverable.getType()).thenReturn(DeliverableType.INSTAGRAM_REEL);
        when(deliverableRepository.findById(DELIVERABLE_ID)).thenReturn(Optional.of(deliverable));
    }

    private static Collaboration collaboration() {
        return Collaboration.invite(COLLAB_ID, CAMPAIGN_ID, CREATOR_USER_ID, "Join us", "INR");
    }

    private static Deliverable submittedDeliverable() {
        Deliverable deliverable =
                Deliverable.builder()
                        .id(DELIVERABLE_ID)
                        .collaborationId(COLLAB_ID)
                        .creatorProfileId("profile1")
                        .slotIndex(1)
                        .type(DeliverableType.INSTAGRAM_REEL)
                        .title("Workout Reel 1")
                        .status(DeliverableStatus.DRAFT)
                        .build();
        deliverable.applyUpload(
                1,
                "[{\"id\":\"f1\",\"fileType\":\"VIDEO\",\"fileName\":\"reel.mp4\",\"url\":\"https://x\"}]",
                "Caption",
                null,
                null);
        deliverable.applySubmit(null, null, null, DeliverableStatus.SUBMITTED);
        return deliverable;
    }
}
