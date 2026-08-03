package com.influora.service.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.enums.AdminRole;
import com.influora.domain.enums.EscrowStatus;
import com.influora.repository.EscrowHoldRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.admin.AdminFinanceDtos.EscrowSummaryDto;
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
    @Mock private AuthPrincipal principal;

    private AdminFinanceService service() {
        return new AdminFinanceService(adminContext, escrowHoldRepository);
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
}
