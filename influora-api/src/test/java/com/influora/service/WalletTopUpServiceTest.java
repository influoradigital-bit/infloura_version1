package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.config.WalletProperties;
import com.influora.domain.entity.Wallet;
import com.influora.domain.entity.Workspace;
import com.influora.domain.entity.WorkspaceMember;
import com.influora.domain.enums.MemberRole;
import com.influora.integration.razorpay.RazorpayClient;
import com.influora.repository.WalletTopUpRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * [SEC: Kabir Option-1 audit P1 must-fix] {@code /wallet/topup} server-side max-amount ceiling —
 * {@link WalletTopUpService#initiateTopUp} must reject any amount above the config-driven
 * {@link WalletProperties#getMaxTopupAmount()} with {@code TOPUP_LIMIT_EXCEEDED} BEFORE minting a
 * Razorpay order.
 */
@ExtendWith(MockitoExtension.class)
class WalletTopUpServiceTest {

    private static final String WORKSPACE_ID = "01HWORKSPACE12345678A";
    private static final String IDEMPOTENCY_KEY = "topup-idem-1";
    private static final BigDecimal MAX_TOPUP = new BigDecimal("1000000.00");

    @Mock private WalletTopUpRepository topUpRepository;
    @Mock private WalletService walletService;
    @Mock private WalletLedgerService ledgerService;
    @Mock private PlatformWalletService platformWalletService;
    @Mock private BrandContextService brandContext;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private RazorpayClient razorpayClient;
    @Mock private AuthPrincipal principal;
    @Mock private WorkspaceMember workspaceMember;

    private WalletProperties walletProperties;
    private WalletTopUpService service;

    @BeforeEach
    void setUp() {
        walletProperties = new WalletProperties();
        walletProperties.setMaxTopupAmount(MAX_TOPUP);

        service =
                new WalletTopUpService(
                        topUpRepository,
                        walletService,
                        ledgerService,
                        platformWalletService,
                        brandContext,
                        workspaceRepository,
                        razorpayClient,
                        walletProperties);
    }

    private void stubBrandWorkspace() {
        Workspace workspace =
                Workspace.newBrand(WORKSPACE_ID, "Test Brand", "test-brand", "Beauty", "10-50");
        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(workspaceMember);
    }

    private static Wallet walletWithBalance(BigDecimal balance) {
        return new Wallet() {
            @Override
            public BigDecimal getBalance() {
                return balance;
            }

            @Override
            public String getCurrency() {
                return "INR";
            }
        };
    }

    @Test
    @DisplayName("initiateTopUp: amount above the configured ceiling is rejected with TOPUP_LIMIT_EXCEEDED")
    void initiateTopUpRejectsAmountAboveCeiling() {
        stubBrandWorkspace();
        BigDecimal aboveMax = MAX_TOPUP.add(BigDecimal.ONE);

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.initiateTopUp(
                                        principal, aboveMax, null, null, IDEMPOTENCY_KEY));

        assertEquals("TOPUP_LIMIT_EXCEEDED", ex.getCode());
        assertEquals(400, ex.getStatus().value());
        verify(topUpRepository, never()).save(any());
        verify(razorpayClient, never()).createOrder(any(), any(), any());
    }

    @Test
    @DisplayName("initiateTopUp: amount exactly equal to the ceiling is allowed")
    void initiateTopUpAllowsAmountEqualToCeiling() {
        stubBrandWorkspace();
        when(topUpRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        when(walletService.requireWorkspaceWallet(WORKSPACE_ID))
                .thenReturn(walletWithBalance(BigDecimal.ZERO));
        when(razorpayClient.createOrder(eq(MAX_TOPUP), eq("INR"), any()))
                .thenReturn(new RazorpayClient.OrderResult("order-1", "created"));

        service.initiateTopUp(principal, MAX_TOPUP, null, null, IDEMPOTENCY_KEY);

        verify(brandContext).requireRole(workspaceMember, MemberRole.OWNER, MemberRole.ADMIN);
        verify(topUpRepository, org.mockito.Mockito.times(2)).save(any());
    }

    @Test
    @DisplayName("initiateTopUp: amount just under the ceiling is allowed")
    void initiateTopUpAllowsAmountJustUnderCeiling() {
        stubBrandWorkspace();
        BigDecimal justUnder = MAX_TOPUP.subtract(BigDecimal.ONE);
        when(topUpRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        when(walletService.requireWorkspaceWallet(WORKSPACE_ID))
                .thenReturn(walletWithBalance(BigDecimal.ZERO));
        when(razorpayClient.createOrder(eq(justUnder), eq("INR"), any()))
                .thenReturn(new RazorpayClient.OrderResult("order-1", "created"));

        service.initiateTopUp(principal, justUnder, null, null, IDEMPOTENCY_KEY);

        verify(razorpayClient).createOrder(eq(justUnder), eq("INR"), any());
    }
}
