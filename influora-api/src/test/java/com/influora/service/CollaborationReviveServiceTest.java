package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.Shipment;
import com.influora.domain.enums.CollaborationSource;
import com.influora.domain.enums.CollaborationStatus;
import com.influora.domain.enums.ContractStatus;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.ContractRepository;
import com.influora.repository.EscrowHoldRepository;
import com.influora.repository.ShipmentRepository;
import java.util.Collection;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * {@code CollaborationReviveService} — F-0225's fail-closed guard.
 *
 * WHY THIS FILE EXISTS
 * --------------------
 * Reviving a CANCELLED collaboration is safe only when nothing durable hangs off it. The
 * tempting argument — CANCELLED is only written by {@code DealService#doReject}, which is gated
 * on the pre-contract {@code canReject()} allowlist, therefore no contract can exist — does NOT
 * hold, for three reasons the service's javadoc sets out: rows cancelled under the pre-CR-22a
 * denylist are in the database today with live contracts; {@code ShipmentService} never reads
 * collaboration status at all, so a shipment can attach to a genuinely pre-contract row; and
 * reviving is precisely what re-arms {@code EscrowService}'s status-based funding and release
 * guards against whatever is still attached.
 *
 * So the refusal below is not defence-in-depth against an impossible state — it is the only
 * thing standing between a withdrawn-then-restarted deal and a silently re-opened escrow.
 *
 * Run: mvn -o test -Dtest=CollaborationReviveServiceTest
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CollaborationReviveServiceTest {

    private static final String CAMPAIGN_ID = "camp_1";
    private static final String CREATOR_ID = "creator_1";
    private static final String COLLAB_ID = "collab_1";

    @Mock private CollaborationRepository collaborationRepository;
    @Mock private ContractRepository contractRepository;
    @Mock private EscrowHoldRepository escrowHoldRepository;
    @Mock private ShipmentRepository shipmentRepository;

    private CollaborationReviveService service;

    @BeforeEach
    void setUp() {
        service =
                new CollaborationReviveService(
                        collaborationRepository, contractRepository, escrowHoldRepository, shipmentRepository);
        when(collaborationRepository.save(any(Collaboration.class))).thenAnswer(i -> i.getArgument(0));
        // Clean by default; each encumbrance test turns exactly one of these on.
        when(contractRepository.existsByCollaborationIdAndStatusNot(anyString(), any(ContractStatus.class)))
                .thenReturn(false);
        when(escrowHoldRepository.hasEscrowForCollaboration(anyString(), any(Collection.class)))
                .thenReturn(false);
        when(shipmentRepository.findByCollaborationId(anyString())).thenReturn(Optional.empty());
    }

    private void priorRow(CollaborationStatus status) {
        Collaboration prior =
                Collaboration.apply(COLLAB_ID, CAMPAIGN_ID, CREATOR_ID, "first try", "INR");
        prior.transitionTo(status);
        when(collaborationRepository.findByCampaignIdAndCreatorId(CAMPAIGN_ID, CREATOR_ID))
                .thenReturn(Optional.of(prior));
    }

    private Collaboration call() {
        return service.reviveOrRefuse(
                CAMPAIGN_ID,
                CREATOR_ID,
                CollaborationStatus.APPLIED,
                CollaborationSource.APPLICATION,
                "trying again",
                "INR",
                "ALREADY_APPLIED",
                "You have already applied to this campaign");
    }

    @Test
    @DisplayName("no prior row -> null, so the caller inserts a fresh collaboration")
    void testNoPriorRow() {
        when(collaborationRepository.findByCampaignIdAndCreatorId(CAMPAIGN_ID, CREATOR_ID))
                .thenReturn(Optional.empty());

        assertNull(call());
        verify(collaborationRepository, never()).save(any(Collaboration.class));
    }

    @Test
    @DisplayName("clean CANCELLED row -> revived and saved")
    void testRevivesCleanCancelledRow() {
        priorRow(CollaborationStatus.CANCELLED);

        Collaboration result = call();

        assertNotNull(result);
        assertEquals(CollaborationStatus.APPLIED, result.getStatus());
        assertEquals("trying again", result.getNotes());
        verify(collaborationRepository).save(any(Collaboration.class));
    }

    @Test
    @DisplayName("live row -> the caller's own 409 code, never a revive")
    void testLiveRowKeepsCallersCode() {
        priorRow(CollaborationStatus.IN_NEGOTIATION);

        ApiException ex = assertThrows(ApiException.class, this::call);

        assertEquals("ALREADY_APPLIED", ex.getCode());
        assertEquals(409, ex.getStatus().value());
        verify(collaborationRepository, never()).save(any(Collaboration.class));
    }

    @Test
    @DisplayName("COMPLETED row -> 409; only CANCELLED ever revives")
    void testCompletedRowRefused() {
        priorRow(CollaborationStatus.COMPLETED);

        assertEquals("ALREADY_APPLIED", assertThrows(ApiException.class, this::call).getCode());
    }

    // ---- the encumbrance refusals: one per artifact, each must fail CLOSED ----

    @Test
    @DisplayName("CANCELLED with a contract that was never voided -> refuse, do not revive")
    void testRefusesWhenContractStillAttached() {
        priorRow(CollaborationStatus.CANCELLED);
        // Exactly the pre-CR-22a legacy shape: cancelled from a post-contract status, contract
        // never voided, escrow never refunded.
        when(contractRepository.existsByCollaborationIdAndStatusNot(COLLAB_ID, ContractStatus.CANCELLED))
                .thenReturn(true);

        ApiException ex = assertThrows(ApiException.class, this::call);

        assertEquals("COLLABORATION_NOT_REVIVABLE", ex.getCode());
        assertEquals(409, ex.getStatus().value());
        // The whole point: the row stays CANCELLED, so EscrowService's status guards stay armed.
        verify(collaborationRepository, never()).save(any(Collaboration.class));
    }

    @Test
    @DisplayName("CANCELLED with an escrow hold -> refuse rather than re-arm the money path")
    void testRefusesWhenEscrowStillAttached() {
        priorRow(CollaborationStatus.CANCELLED);
        when(escrowHoldRepository.hasEscrowForCollaboration(anyString(), any(Collection.class)))
                .thenReturn(true);

        assertEquals(
                "COLLABORATION_NOT_REVIVABLE",
                assertThrows(ApiException.class, this::call).getCode());
        verify(collaborationRepository, never()).save(any(Collaboration.class));
    }

    @Test
    @DisplayName("CANCELLED with a shipment -> refuse; ShipmentService never reads deal status")
    void testRefusesWhenShipmentStillAttached() {
        priorRow(CollaborationStatus.CANCELLED);
        // Reachable under TODAY's correct allowlist: apply -> submit address -> withdraw.
        when(shipmentRepository.findByCollaborationId(COLLAB_ID))
                .thenReturn(Optional.of(org.mockito.Mockito.mock(Shipment.class)));

        assertEquals(
                "COLLABORATION_NOT_REVIVABLE",
                assertThrows(ApiException.class, this::call).getCode());
        verify(collaborationRepository, never()).save(any(Collaboration.class));
    }
}
