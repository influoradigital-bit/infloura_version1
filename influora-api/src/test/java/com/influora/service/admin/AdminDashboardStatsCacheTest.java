package com.influora.service.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.domain.enums.CampaignStatus;
import com.influora.domain.enums.EscrowStatus;
import com.influora.domain.enums.TicketStatus;
import com.influora.domain.enums.UserType;
import com.influora.domain.enums.WalletTransactionType;
import com.influora.repository.CampaignRepository;
import com.influora.repository.EscrowHoldRepository;
import com.influora.repository.SupportTicketRepository;
import com.influora.repository.UserRepository;
import com.influora.repository.WalletTransactionRepository;
import com.influora.web.dto.admin.AdminDashboardDtos.CeoPulseDataDto;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Mockito unit tests for {@code AdminDashboardStatsCache#pulseStats}, covering P1 "revenue +
 * honest deltas" (Rohan's, CFO, signed-off formula 2026-07-15): {@code revenue =
 * SUM(wallet_transactions.amount) WHERE type = 'PLATFORM_FEE'}, and the three {@code *Change}
 * fields returning {@code null} rather than a fabricated {@code 0} until a {@code
 * kpi_daily_snapshot} table exists.
 *
 * <p>Plain Mockito, no {@code @SpringBootTest} — same constraint as the sibling {@code
 * AdminDashboardServiceTest} (Testcontainers/Docker discovery does not work in this environment;
 * boot itself is separately blocked, per Meera's parallel V54 work). {@code @Cacheable} is a
 * Spring-AOP-proxy concern and is inert when the bean is constructed directly like this, so it
 * does not interfere with asserting the method body's own logic.
 */
@ExtendWith(MockitoExtension.class)
class AdminDashboardStatsCacheTest {

    @Mock private CampaignRepository campaignRepository;
    @Mock private EscrowHoldRepository escrowHoldRepository;
    @Mock private SupportTicketRepository supportTicketRepository;
    @Mock private UserRepository userRepository;
    @Mock private WalletTransactionRepository walletTransactionRepository;

    private AdminDashboardStatsCache statsCache;

    @BeforeEach
    void setUp() {
        statsCache =
                new AdminDashboardStatsCache(
                        campaignRepository,
                        escrowHoldRepository,
                        supportTicketRepository,
                        userRepository,
                        walletTransactionRepository);
    }

    private void stubUnrelatedStats() {
        when(campaignRepository.countByStatus(CampaignStatus.ACTIVE)).thenReturn(5L);
        when(escrowHoldRepository.sumAmountByStatusIn(List.of(EscrowStatus.FUNDED)))
                .thenReturn(new BigDecimal("1000.00"));
        when(escrowHoldRepository.sumAmountByStatusIn(
                        List.of(EscrowStatus.FUNDED, EscrowStatus.RELEASED)))
                .thenReturn(new BigDecimal("5000.00"));
        when(supportTicketRepository.countByStatusIn(
                        List.of(TicketStatus.OPEN, TicketStatus.IN_PROGRESS, TicketStatus.WAITING_USER)))
                .thenReturn(2L);
        when(userRepository.countByUserTypeAndLastLoginAtAfter(eq(UserType.BRAND), any(Instant.class)))
                .thenReturn(3L);
        when(userRepository.countByUserTypeAndLastLoginAtAfter(eq(UserType.CREATOR), any(Instant.class)))
                .thenReturn(4L);
        when(supportTicketRepository.findTop5ByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
                        eq(TicketStatus.OPEN), any(Instant.class)))
                .thenReturn(List.of());
    }

    @Test
    @DisplayName(
            "pulseStats().revenue sums only PLATFORM_FEE wallet_transactions rows, via the ledger"
                    + " repository, ignoring other transaction types and the invoice tables entirely")
    void testRevenueSumsOnlyPlatformFeeRows() {
        stubUnrelatedStats();
        when(walletTransactionRepository.sumAmountByTypeAndCreatedAtBefore(
                        eq(WalletTransactionType.PLATFORM_FEE), any(Instant.class)))
                .thenReturn(new BigDecimal("1234.56"));

        CeoPulseDataDto result = statsCache.pulseStats();

        assertEquals(new BigDecimal("1234.56"), result.revenue());
        // Exactly one call, scoped to PLATFORM_FEE -- no other WalletTransactionType is ever
        // summed for revenue, and this class has no dependency on
        // CampaignServiceInvoiceRepository / PlatformCommissionInvoiceRepository at all, so the
        // gross-invoice / commission-invoice double-count traps are structurally unreachable here.
        verify(walletTransactionRepository)
                .sumAmountByTypeAndCreatedAtBefore(eq(WalletTransactionType.PLATFORM_FEE), any(Instant.class));
        verify(walletTransactionRepository, never())
                .sumAmountByTypeAndCreatedAtBefore(
                        eq(WalletTransactionType.ESCROW_RELEASE), any(Instant.class));
        verify(walletTransactionRepository, never())
                .sumAmountByTypeAndCreatedAtBefore(eq(WalletTransactionType.DEPOSIT), any(Instant.class));
    }

    @Test
    @DisplayName(
            "pulseStats() reports gmvChange/revenueChange/activeCampaignsChange as null, not 0.0 --"
                    + " honest 'no snapshot yet' rather than a fabricated flat reading")
    void testDeltasAreNullPreSnapshot() {
        stubUnrelatedStats();
        when(walletTransactionRepository.sumAmountByTypeAndCreatedAtBefore(
                        eq(WalletTransactionType.PLATFORM_FEE), any(Instant.class)))
                .thenReturn(BigDecimal.ZERO);

        CeoPulseDataDto result = statsCache.pulseStats();

        assertNull(result.gmvChange());
        assertNull(result.revenueChange());
        assertNull(result.activeCampaignsChange());
    }
}
