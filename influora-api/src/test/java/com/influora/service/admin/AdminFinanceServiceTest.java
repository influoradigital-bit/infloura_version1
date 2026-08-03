package com.influora.service.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Dispute;
import com.influora.domain.entity.EscrowHold;
import com.influora.domain.enums.AdminRole;
import com.influora.domain.enums.DisputeOpenerType;
import com.influora.domain.enums.EscrowStatus;
import com.influora.repository.CampaignRepository;
import com.influora.repository.DisputeRepository;
import com.influora.repository.EscrowHoldRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.admin.AdminFinanceDtos.EscrowSummaryDto;
import com.influora.web.dto.admin.AdminFinanceDtos.FlaggedEscrowDto;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * Unit tests for {@link AdminFinanceService#getEscrowSummary} — the admin Finance/Escrow console's
 * first endpoint ({@code GET /admin/finance/escrow}, admin-finance-queue item 1). Covers the two
 * things the compile-only gate could NOT: (1) the SUPER_ADMIN+ADMIN / MFA gate is actually invoked
 * and a rejected caller reads no escrow data, and (2) the repository figures map to the DTO
 * correctly including the null-of-empty-set defaults.
 *
 * <p>NOT covered here (declared): whether the native {@code TIMESTAMPDIFF} query in
 * {@link EscrowHoldRepository#avgReleaseSeconds} actually executes against MySQL — that needs a
 * Testcontainers/@DataJpaTest integration harness (H2 does not share MySQL's TIMESTAMPDIFF
 * semantics), which this module does not yet have. This suite mocks the repository, so it proves
 * the service's contract with those queries, not the queries' own SQL.
 */
@ExtendWith(MockitoExtension.class)
class AdminFinanceServiceTest {

    @Mock private AdminContextService adminContext;
    @Mock private EscrowHoldRepository escrowHoldRepository;
    @Mock private CampaignRepository campaignRepository;
    @Mock private DisputeRepository disputeRepository;
    @Mock private AuthPrincipal principal;

    private AdminFinanceService service() {
        return new AdminFinanceService(
                adminContext, escrowHoldRepository, campaignRepository, disputeRepository);
    }

    // --- helpers: real entities via their builders (getters are non-mockable value accessors;
    // createdAt is stamped by build()/open() and read back where a test asserts it) ---
    private static EscrowHold frozenHold(
            String id, String campaignId, String collaborationId, String amount) {
        return EscrowHold.builder()
                .id(id)
                .workspaceId("w1")
                .campaignId(campaignId)
                .collaborationId(collaborationId)
                .amount(amount == null ? null : new BigDecimal(amount))
                .status(EscrowStatus.FROZEN)
                .idempotencyKey("idem-" + id)
                .build();
    }

    private static Campaign campaign(String id, String title) {
        return Campaign.builder().id(id).workspaceId("w1").title(title).build();
    }

    private static Dispute dispute(String collaborationId, String reason) {
        return Dispute.open(
                "d-" + collaborationId, collaborationId, DisputeOpenerType.BRAND, "u1", reason);
    }

    @Test
    @DisplayName("gates on SUPER_ADMIN+ADMIN and maps every figure live from escrow_holds")
    void getEscrowSummary_gatesAndMapsLiveFigures() {
        when(escrowHoldRepository.sumAmountByStatusIn(List.of(EscrowStatus.FUNDED)))
                .thenReturn(new BigDecimal("12500.50"));
        when(escrowHoldRepository.countByStatus(EscrowStatus.FUNDED)).thenReturn(7L);
        when(escrowHoldRepository.countByStatus(EscrowStatus.FROZEN)).thenReturn(2L);
        when(escrowHoldRepository.avgReleaseSeconds()).thenReturn(7200.0); // 2h in seconds

        EscrowSummaryDto dto = service().getEscrowSummary(principal);

        // The gate is genuinely invoked with exactly the two allowed roles (item-1 blind spot).
        verify(adminContext)
                .requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN);
        assertEquals(12500.50, dto.totalLocked(), 1e-9);
        assertEquals(7L, dto.pendingRelease());
        assertEquals(2L, dto.flaggedTransactions());
        assertEquals(2.0, dto.averageReleaseTime(), 1e-9); // 7200s / 3600 = 2h
    }

    @Test
    @DisplayName("null SUM and null AVG (empty sets) default to 0.0, never NPE")
    void getEscrowSummary_nullAggregatesDefaultToZero() {
        when(escrowHoldRepository.sumAmountByStatusIn(List.of(EscrowStatus.FUNDED))).thenReturn(null);
        when(escrowHoldRepository.countByStatus(EscrowStatus.FUNDED)).thenReturn(0L);
        when(escrowHoldRepository.countByStatus(EscrowStatus.FROZEN)).thenReturn(0L);
        when(escrowHoldRepository.avgReleaseSeconds()).thenReturn(null); // no hold released yet

        EscrowSummaryDto dto = service().getEscrowSummary(principal);

        assertEquals(0.0, dto.totalLocked(), 1e-9);
        assertEquals(0L, dto.pendingRelease());
        assertEquals(0.0, dto.averageReleaseTime(), 1e-9);
    }

    @Test
    @DisplayName("an unauthorized (e.g. SUPPORT) caller is blocked BEFORE any escrow read")
    void getEscrowSummary_unauthorizedCallerReadsNothing() {
        when(adminContext.requireRoleWithMfaSatisfied(
                        principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN))
                .thenThrow(
                        new ApiException(
                                "INSUFFICIENT_ROLE",
                                "SUPPORT may not view finance",
                                HttpStatus.FORBIDDEN));

        assertThrows(ApiException.class, () -> service().getEscrowSummary(principal));

        verify(escrowHoldRepository, never()).sumAmountByStatusIn(anyList());
        verify(escrowHoldRepository, never()).countByStatus(any());
        verify(escrowHoldRepository, never()).avgReleaseSeconds();
    }

    @Test
    @DisplayName("flagged: gates, joins campaign title + dispute reason per hold, preserves order")
    void getFlaggedEscrows_joinsCampaignAndDisputeReason() {
        EscrowHold h1 = frozenHold("e1", "c1", "col1", "500.00");
        EscrowHold h2 = frozenHold("e2", "c2", "col2", "300.00");
        Campaign c1 = campaign("c1", "Summer Launch");
        Campaign c2 = campaign("c2", "Diwali Push");
        Dispute d1 = dispute("col1", "Quality dispute");
        Dispute d2 = dispute("col2", "Late delivery");

        when(escrowHoldRepository.findByStatusOrderByCreatedAtDesc(EscrowStatus.FROZEN))
                .thenReturn(List.of(h1, h2)); // repo already orders newest-first
        when(campaignRepository.findAllById(any())).thenReturn(List.of(c1, c2));
        when(disputeRepository.findByCollaborationIdIn(any())).thenReturn(List.of(d1, d2));

        List<FlaggedEscrowDto> rows = service().getFlaggedEscrows(principal);

        verify(adminContext)
                .requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN);
        assertEquals(2, rows.size());
        FlaggedEscrowDto r0 = rows.get(0);
        assertEquals("e1", r0.id());
        assertEquals("c1", r0.campaignId());
        assertEquals("Summer Launch", r0.campaignName());
        assertEquals(500.0, r0.amount(), 1e-9);
        assertEquals("Quality dispute", r0.flagReason());
        assertEquals(h1.getCreatedAt().toString(), r0.createdAt());
        assertEquals("Late delivery", rows.get(1).flagReason());
    }

    @Test
    @DisplayName("flagged: no FROZEN holds -> empty list, no campaign/dispute lookups")
    void getFlaggedEscrows_emptyReturnsEmpty() {
        when(escrowHoldRepository.findByStatusOrderByCreatedAtDesc(EscrowStatus.FROZEN))
                .thenReturn(List.of());

        assertEquals(0, service().getFlaggedEscrows(principal).size());
        verify(campaignRepository, never()).findAllById(any());
        verify(disputeRepository, never()).findByCollaborationIdIn(any());
    }

    @Test
    @DisplayName("flagged: missing campaign row + null collaboration fall back, never NPE or fabricate")
    void getFlaggedEscrows_fallbacks() {
        EscrowHold orphan = frozenHold("e9", "cGone", null, null); // no collaboration, no amount
        when(escrowHoldRepository.findByStatusOrderByCreatedAtDesc(EscrowStatus.FROZEN))
                .thenReturn(List.of(orphan));
        when(campaignRepository.findAllById(any())).thenReturn(List.of()); // campaign row gone
        when(disputeRepository.findByCollaborationIdIn(any())).thenReturn(List.of());

        FlaggedEscrowDto r = service().getFlaggedEscrows(principal).get(0);

        assertEquals("(unknown campaign)", r.campaignName());
        assertEquals("Frozen — no linked dispute", r.flagReason());
        assertEquals(0.0, r.amount(), 1e-9);
    }

    @Test
    @DisplayName("flagged: unauthorized caller is blocked before any escrow read")
    void getFlaggedEscrows_unauthorizedReadsNothing() {
        when(adminContext.requireRoleWithMfaSatisfied(
                        principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN))
                .thenThrow(new ApiException("INSUFFICIENT_ROLE", "nope", HttpStatus.FORBIDDEN));

        assertThrows(ApiException.class, () -> service().getFlaggedEscrows(principal));

        verify(escrowHoldRepository, never()).findByStatusOrderByCreatedAtDesc(any());
    }
}
