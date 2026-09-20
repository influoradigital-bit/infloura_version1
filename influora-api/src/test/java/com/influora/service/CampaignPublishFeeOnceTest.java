package com.influora.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.EscrowHold;
import com.influora.domain.entity.PlatformFeeConfig;
import com.influora.domain.entity.Wallet;
import com.influora.domain.entity.WalletTransaction;
import com.influora.domain.entity.Workspace;
import com.influora.domain.entity.WorkspaceMember;
import com.influora.domain.enums.CampaignStatus;
import com.influora.domain.enums.EscrowStatus;
import com.influora.domain.enums.TxnDirection;
import com.influora.domain.enums.TxnReferenceType;
import com.influora.domain.enums.VerificationStatus;
import com.influora.domain.enums.WalletTransactionType;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.EscrowHoldRepository;
import com.influora.repository.PlatformFeeConfigRepository;
import com.influora.repository.WalletTransactionRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.billing.SubscriptionService;
import com.influora.web.dto.campaign.CampaignDtos.BudgetDto;
import com.influora.web.dto.campaign.CampaignDtos.CampaignPatchRequest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

/**
 * EV-005 / EV-026 / F-0857 / F-0858 — "the brand pays the publish fee ONCE", counted in POSTINGS,
 * not in {@code chargeOnPublish} call counts.
 *
 * <p>Wires the REAL {@link CampaignService} -&gt; REAL {@link CampaignActivationGuard} -&gt; REAL
 * {@link BrandCampaignFeeService}. Only the ledger is faked, and the fake mirrors the two
 * {@code WalletLedgerService.post} behaviours this rule depends on: a repeat of the same
 * idempotency key with the same amount is a replay (no new rows), and a repeat with a DIFFERENT
 * amount throws {@code LEDGER_IDEMPOTENCY_KEY_COLLISION} (F-0858). The already-paid read
 * ({@code sumAmountByReferenceAndTypeAndDirection}) is answered from the fake ledger's own rows, so
 * "one posting" is a property of what was written, not of what a mock was told to return.
 *
 * <p>{@code CampaignActivationLedgerIntegrationTest} proves the same against real MySQL, but it is
 * skipped without Docker; this test always runs.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CampaignPublishFeeOnceTest {

    private static final String WORKSPACE_ID = "01HWORKSPACE12345678A";
    private static final String CAMPAIGN_ID = "01HCAMPAIGN123456789A";
    private static final String BRAND_WALLET_ID = "01HWALLETBRAND1234567";
    private static final String REVENUE_WALLET_ID = "01HWALLETREVENUE12345";

    @Mock private CampaignRepository campaignRepository;
    @Mock private CollaborationRepository collaborationRepository;
    @Mock private EscrowHoldRepository escrowHoldRepository;
    @Mock private BrandContextService brandContext;
    @Mock private IntegrationHealthService integrationHealthService;
    @Mock private AuthPrincipal principal;
    @Mock private Workspace workspace;
    @Mock private WorkspaceMember member;

    @Mock private PlatformFeeConfigRepository configRepository;
    @Mock private WalletLedgerService ledgerService;
    @Mock private PlatformWalletService platformWalletService;
    @Mock private WalletService walletService;
    @Mock private SubscriptionService subscriptionService;
    @Mock private CommissionInvoiceService commissionInvoiceService;
    @Mock private WalletTransactionRepository walletTransactionRepository;

    private CampaignService campaignService;
    private Campaign campaign;

    /** Fake ledger: idempotency key -> posted amount (one DEBIT leg each). */
    private final Map<String, BigDecimal> postings = new LinkedHashMap<>();
    private final List<BigDecimal> invoicedFees = new ArrayList<>();
    private BigDecimal brandBalance = BigDecimal.valueOf(100_000);

    @BeforeEach
    void setUp() {
        BrandCampaignFeeService feeService =
                new BrandCampaignFeeService(
                        configRepository,
                        ledgerService,
                        platformWalletService,
                        walletService,
                        subscriptionService,
                        commissionInvoiceService,
                        walletTransactionRepository);
        campaignService =
                new CampaignService(
                        campaignRepository,
                        collaborationRepository,
                        escrowHoldRepository,
                        brandContext,
                        new CampaignValidator(),
                        integrationHealthService,
                        new CampaignActivationGuard(escrowHoldRepository, feeService));

        campaign =
                Campaign.builder()
                        .id(CAMPAIGN_ID)
                        .workspaceId(WORKSPACE_ID)
                        .title("Fee Once Campaign")
                        .status(CampaignStatus.DRAFT)
                        .budgetMin(BigDecimal.valueOf(10_000))
                        .budgetMax(BigDecimal.valueOf(10_000))
                        .build();

        when(brandContext.requireBrandWorkspace(principal)).thenReturn(workspace);
        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        when(workspace.getId()).thenReturn(WORKSPACE_ID);
        when(workspace.getVerificationStatus()).thenReturn(VerificationStatus.VERIFIED);
        when(campaignRepository.findByIdForUpdate(CAMPAIGN_ID)).thenReturn(Optional.of(campaign));
        when(campaignRepository.save(any(Campaign.class))).thenAnswer(inv -> inv.getArgument(0));
        when(escrowHoldRepository.findByCampaignId(CAMPAIGN_ID))
                .thenReturn(
                        List.of(
                                EscrowHold.builder()
                                        .id("01HESCROW1234567890AB")
                                        .workspaceId(WORKSPACE_ID)
                                        .campaignId(CAMPAIGN_ID)
                                        .amount(BigDecimal.valueOf(10_000))
                                        .currency("INR")
                                        .status(EscrowStatus.FUNDED)
                                        .idempotencyKey("fund:" + CAMPAIGN_ID)
                                        .build()));

        // 10% global brand fee, FREE plan.
        PlatformFeeConfig config = mock(PlatformFeeConfig.class);
        when(config.getBrandFeeBps()).thenReturn(1000);
        when(configRepository.findById(PlatformFeeConfig.SINGLETON_ID)).thenReturn(Optional.of(config));
        when(subscriptionService.getActivePlanForWorkspace(WORKSPACE_ID)).thenReturn(null);

        Wallet brandWallet = mock(Wallet.class);
        when(brandWallet.getId()).thenReturn(BRAND_WALLET_ID);
        when(brandWallet.getCurrency()).thenReturn("INR");
        when(brandWallet.getBalance()).thenAnswer(inv -> brandBalance);
        when(walletService.requireWorkspaceWallet(WORKSPACE_ID)).thenReturn(brandWallet);
        Wallet revenueWallet = mock(Wallet.class);
        when(revenueWallet.getId()).thenReturn(REVENUE_WALLET_ID);
        when(platformWalletService.requireRevenueWallet()).thenReturn(revenueWallet);

        when(ledgerService.post(
                        eq(BRAND_WALLET_ID),
                        eq(REVENUE_WALLET_ID),
                        any(BigDecimal.class),
                        anyString(),
                        eq(WalletTransactionType.PLATFORM_FEE),
                        eq(TxnReferenceType.CAMPAIGN),
                        eq(CAMPAIGN_ID),
                        anyString(),
                        anyString(),
                        any()))
                .thenAnswer(
                        inv -> {
                            BigDecimal amount = inv.getArgument(2);
                            String key = inv.getArgument(8);
                            BigDecimal existing = postings.get(key);
                            if (existing != null) {
                                if (existing.compareTo(amount) != 0) {
                                    throw new ApiException(
                                            "LEDGER_IDEMPOTENCY_KEY_COLLISION",
                                            "Idempotency key was already used for a different money movement",
                                            HttpStatus.CONFLICT);
                                }
                            } else {
                                if (brandBalance.compareTo(amount) < 0) {
                                    throw new ApiException(
                                            "INSUFFICIENT_BALANCE", "insufficient", HttpStatus.CONFLICT);
                                }
                                postings.put(key, amount);
                                brandBalance = brandBalance.subtract(amount);
                            }
                            return new WalletLedgerService.LedgerPostingResult(
                                    mock(WalletTransaction.class), mock(WalletTransaction.class));
                        });
        when(walletTransactionRepository.sumAmountByReferenceAndTypeAndDirection(
                        TxnReferenceType.CAMPAIGN,
                        CAMPAIGN_ID,
                        WalletTransactionType.PLATFORM_FEE,
                        TxnDirection.DEBIT))
                .thenAnswer(inv -> postings.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add));
        when(commissionInvoiceService.createBrandLegAtPublish(
                        any(Campaign.class), anyString(), anyInt(), any(BigDecimal.class), any()))
                .thenAnswer(
                        inv -> {
                            invoicedFees.add(inv.getArgument(3));
                            return null;
                        });
    }

    @Test
    @DisplayName(
            "publish, pause, RAISE the budget while paused, resume -> exactly one PLATFORM_FEE posting"
                    + " and exactly one invoice, both for the first-publish fee (EV-026/F-0858)")
    void budgetRaisedWhilePausedThenResumePostsOneFee() {
        campaignService.update(principal, CAMPAIGN_ID, patch(CampaignStatus.ACTIVE, null));
        assertEquals(1, postings.size(), "first publish must post the fee, or the rest is vacuous");

        campaignService.update(principal, CAMPAIGN_ID, patch(CampaignStatus.PAUSED, null));
        campaignService.update(
                principal,
                CAMPAIGN_ID,
                patch(null, new BudgetDto(BigDecimal.valueOf(15_000), BigDecimal.valueOf(15_000), "INR")));
        assertEquals(0, campaign.getBudgetMax().compareTo(BigDecimal.valueOf(15_000)));
        campaignService.update(principal, CAMPAIGN_ID, patch(CampaignStatus.ACTIVE, null));

        assertEquals(CampaignStatus.ACTIVE, campaign.getStatus());
        assertEquals(List.of("brand-fee-publish:" + CAMPAIGN_ID), List.copyOf(postings.keySet()));
        assertEquals(0, postings.get("brand-fee-publish:" + CAMPAIGN_ID).compareTo(BigDecimal.valueOf(1000)));
        assertEquals(1, invoicedFees.size());
        assertEquals(0, invoicedFees.get(0).compareTo(BigDecimal.valueOf(1000)));
    }

    @Test
    @DisplayName(
            "publish, pause, resume with the wallet EMPTY -> resume succeeds, still one posting (F-0857)")
    void resumeWithEmptyWalletSucceedsWithOnePosting() {
        campaignService.update(principal, CAMPAIGN_ID, patch(CampaignStatus.ACTIVE, null));
        campaignService.update(principal, CAMPAIGN_ID, patch(CampaignStatus.PAUSED, null));
        brandBalance = BigDecimal.ZERO;

        campaignService.update(principal, CAMPAIGN_ID, patch(CampaignStatus.ACTIVE, null));

        assertEquals(CampaignStatus.ACTIVE, campaign.getStatus());
        assertEquals(1, postings.size());
        assertEquals(1, invoicedFees.size());
    }

    @Test
    @DisplayName("resume of a paid campaign with NO funded hold left -> still refused (same rule as publish)")
    void resumeStillRequiresFundedHold() {
        campaignService.update(principal, CAMPAIGN_ID, patch(CampaignStatus.ACTIVE, null));
        campaignService.update(principal, CAMPAIGN_ID, patch(CampaignStatus.PAUSED, null));
        when(escrowHoldRepository.findByCampaignId(CAMPAIGN_ID)).thenReturn(List.of());

        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> campaignService.update(principal, CAMPAIGN_ID, patch(CampaignStatus.ACTIVE, null)));

        assertEquals("ESCROW_NOT_FUNDED", ex.getCode());
        assertEquals(CampaignStatus.PAUSED, campaign.getStatus());
        assertEquals(1, postings.size());
    }

    private static CampaignPatchRequest patch(CampaignStatus status, BudgetDto budget) {
        return new CampaignPatchRequest(
                null, null, null, status, null, budget, null, null, null, null, null, null, null, null,
                null, null, null, null);
    }
}
