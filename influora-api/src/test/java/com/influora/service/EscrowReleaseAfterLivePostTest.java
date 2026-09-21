package com.influora.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.Collaboration;
import com.influora.domain.entity.Deliverable;
import com.influora.domain.entity.EscrowHold;
import com.influora.domain.entity.PaymentMilestone;
import com.influora.domain.entity.Wallet;
import com.influora.domain.entity.WalletTransaction;
import com.influora.domain.entity.WorkspaceMember;
import com.influora.domain.enums.DeliverableStatus;
import com.influora.domain.enums.DeliverableType;
import com.influora.domain.enums.EscrowStatus;
import com.influora.domain.enums.MilestoneStatus;
import com.influora.domain.enums.ReleaseCondition;
import com.influora.domain.enums.TxnDirection;
import com.influora.domain.enums.TxnReferenceType;
import com.influora.domain.enums.WalletTransactionType;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.ContractRepository;
import com.influora.repository.DeliverableRepository;
import com.influora.repository.DisputeRepository;
import com.influora.repository.EscrowHoldRepository;
import com.influora.repository.PaymentMilestoneRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.escrow.EscrowBackend;
import com.influora.service.escrow.LedgerEscrowBackend;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * [paytrigger, 2026-09-21] "The creator is paid after the post is live" — proved end to end on the
 * MANUAL release path, and proved to be the SHIPPED default rather than something an operator has
 * to remember to switch on.
 *
 * <p>The defect this class exists to stop coming back: {@code
 * EscrowService#assertReleaseConditionSatisfied} was a real, correct gate that the shipped
 * configuration never consulted. Its only control was {@code
 * influora.escrow.release-gate.cutover-instant}, which defaulted to BLANK, and a blank cutover
 * makes {@code isPostCutover(...)} answer {@code false} for every milestone — so the gate returned
 * immediately, always, and a release could pay a creator while every deliverable on the deal was
 * still a DRAFT. Every existing test of the gate ({@code EscrowServiceReleaseTest}, {@code
 * EscrowServiceTest}, {@code EscrowReleaseGateIntegrationTest}) sets a cutover of its own first, so
 * all of them proved the gate's LOGIC while none of them ever proved it was ON.
 *
 * <p>So this class does not hard-code a cutover. It reads the two gate properties out of the real
 * {@code application.yml} on the classpath, resolving the shipped defaults, and wires {@link
 * EscrowService}'s own {@code @PostConstruct} with exactly those values — the same path Spring
 * takes at boot. If someone sets {@code enabled: false}, or empties the cutover default, or moves
 * the cutover into the future, {@link #shippedDefaultsLeaveTheGateOn()} goes red AND every
 * behavioural test below it goes red with it, because they are all wired from the same source.
 */
@ExtendWith(MockitoExtension.class)
class EscrowReleaseAfterLivePostTest {

    private static final String WORKSPACE_ID = "01HWORKSPACE12345678A";
    private static final String MILESTONE_ID = "01HMILESTONE123456789";
    private static final String ESCROW_HOLD_ID = "01HESCROW1234567890AB";
    private static final String COLLABORATION_ID = "01HCOLLAB1234567890AB";
    private static final String CAMPAIGN_ID = "01HCAMPAIGN1234567AB";
    private static final String CREATOR_USER_ID = "01HCREATORUSER1234AB";
    private static final String CLEARING_WALLET_ID = "01HCLEARING1234567890";
    private static final String PAYEE_WALLET_ID = "01HPAYEE123456789012";

    private static final String ENABLED_KEY = "influora.escrow.release-gate.enabled";
    private static final String CUTOVER_KEY = "influora.escrow.release-gate.cutover-instant";

    @Mock private EscrowHoldRepository escrowHoldRepository;
    @Mock private PaymentMilestoneRepository milestoneRepository;
    @Mock private CampaignRepository campaignRepository;
    @Mock private CollaborationRepository collaborationRepository;
    @Mock private ContractRepository contractRepository;
    @Mock private DisputeRepository disputeRepository;
    @Mock private WalletLedgerService ledgerService;
    @Mock private PlatformWalletService platformWalletService;
    @Mock private PlatformFeeService platformFeeService;
    @Mock private WalletService walletService;
    @Mock private BrandContextService brandContext;
    @Mock private CreatorContextService creatorContext;
    @Mock private AuthPrincipal principal;
    @Mock private WorkspaceMember member;
    @Mock private CampaignServiceInvoiceService campaignServiceInvoiceService;
    @Mock private DeliverableRepository deliverableRepository;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private CollaborationLifecycleService collaborationLifecycleService;
    @Mock private ApplicationHistoryService applicationHistoryService;

    private EscrowService service;

    @BeforeEach
    void setUp() {
        EscrowBackend escrowBackend =
                new LedgerEscrowBackend(
                        ledgerService, platformWalletService, platformFeeService, walletService);
        service =
                new EscrowService(
                        escrowHoldRepository,
                        milestoneRepository,
                        campaignRepository,
                        collaborationRepository,
                        contractRepository,
                        disputeRepository,
                        walletService,
                        brandContext,
                        creatorContext,
                        campaignServiceInvoiceService,
                        deliverableRepository,
                        workspaceRepository,
                        eventPublisher,
                        collaborationLifecycleService,
                        escrowBackend,
                        applicationHistoryService);
        applyShippedGateConfig(service);
    }

    // ------------------------------------------------------------------------------------------
    // The default-state assertion. Reads application.yml, not a literal.
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "shipped application.yml leaves the release gate ON: enabled=true and a cutover that is"
                    + " already in the past, so a milestone created today is gated")
    void shippedDefaultsLeaveTheGateOn() {
        assertEquals(
                "true",
                shippedProperty(ENABLED_KEY),
                ENABLED_KEY
                        + " must ship as true. With it false, EscrowService#initReleaseGateCutoverInstant"
                        + " nulls the cutover and assertReleaseConditionSatisfied returns immediately —"
                        + " secured funds could be released before the creator's post is live.");

        String rawCutover = shippedProperty(CUTOVER_KEY);
        assertThat(rawCutover)
                .as(
                        CUTOVER_KEY
                                + " must ship with a real ISO-8601 default, not blank. A blank cutover is"
                                + " how this gate shipped inert for months.")
                .isNotBlank();

        Instant cutover = Instant.parse(rawCutover.trim());
        assertThat(cutover)
                .as(
                        "the shipped cutover must already be in the past, otherwise milestones created"
                                + " today are still on the fail-open side of isPostCutover(...)")
                .isBefore(Instant.now());

        // And the value the service actually runs on, resolved through its own @PostConstruct.
        assertThat(resolvedCutover(service))
                .as("EscrowService must boot with the gate armed at the shipped cutover")
                .isEqualTo(cutover);
    }

    @Test
    @DisplayName(
            "a blank cutover while enabled now fails CLOSED at EPOCH (gate on for every milestone),"
                    + " instead of silently disabling the gate")
    void blankCutoverWhileEnabledFailsClosed() {
        ReflectionTestUtils.setField(service, "releaseGateEnabled", true);
        ReflectionTestUtils.setField(service, "releaseGateCutoverInstantRaw", "");
        ReflectionTestUtils.invokeMethod(service, "initReleaseGateCutoverInstant");

        assertThat(resolvedCutover(service)).isEqualTo(Instant.EPOCH);
    }

    @Test
    @DisplayName("enabled=false is the one and only way to turn the gate off, and it is explicit")
    void explicitlyDisablingTheGateIsTheOnlyWayOff() {
        ReflectionTestUtils.setField(service, "releaseGateEnabled", false);
        ReflectionTestUtils.setField(service, "releaseGateCutoverInstantRaw", "2026-09-21T00:00:00Z");
        ReflectionTestUtils.invokeMethod(service, "initReleaseGateCutoverInstant");

        assertThat(resolvedCutover(service)).isNull();
    }

    // ------------------------------------------------------------------------------------------
    // The ruling, on the manual Release path: paid after the post is LIVE, not at draft approval.
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "manual Release is REFUSED while the deliverable is only SUBMITTED — no ledger posting,"
                    + " no fee deduction, hold stays FUNDED")
    void manualReleaseRefusedBeforeThePostIsLive() {
        EscrowHold hold = refusalFixture(DeliverableStatus.SUBMITTED);

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> service.release(principal, WORKSPACE_ID, MILESTONE_ID));

        assertEquals("RELEASE_CONDITION_NOT_MET", ex.getCode());
        assertThat(ex.getMessage())
                .as("the brand reads this message verbatim in the Payments panel")
                .contains("post is live")
                .contains("1 of 1 deliverable");
        assertNoMoneyMoved(hold);
    }

    @Test
    @DisplayName(
            "manual Release is REFUSED while the deliverable is APPROVED but not POSTED — approving"
                    + " a draft is not the payment trigger")
    void approvingTheDraftIsNotThePaymentTrigger() {
        EscrowHold hold = refusalFixture(DeliverableStatus.APPROVED);

        ApiException ex =
                assertThrows(
                        ApiException.class, () -> service.release(principal, WORKSPACE_ID, MILESTONE_ID));

        assertEquals("RELEASE_CONDITION_NOT_MET", ex.getCode());
        assertNoMoneyMoved(hold);
    }

    @Test
    @DisplayName(
            "manual Release is ALLOWED once the deliverable is POSTED — the live link is in, the"
                    + " money moves")
    void manualReleaseAllowedOnceThePostIsLive() {
        PaymentMilestone milestone = fundedMilestone();
        Collaboration collaboration =
                Collaboration.invite(COLLABORATION_ID, CAMPAIGN_ID, CREATOR_USER_ID, null, "INR");
        EscrowHold hold = fundedHold();

        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        when(milestoneRepository.findByIdAndWorkspaceId(MILESTONE_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(milestone));
        when(escrowHoldRepository.findByIdForUpdate(ESCROW_HOLD_ID)).thenReturn(Optional.of(hold));
        when(collaborationRepository.findById(COLLABORATION_ID)).thenReturn(Optional.of(collaboration));
        when(disputeRepository.existsByCollaborationIdAndStatusIn(any(), any())).thenReturn(false);
        when(deliverableRepository.findByCollaborationIdOrderBySlotIndexAsc(COLLABORATION_ID))
                .thenReturn(List.of(deliverable(DeliverableStatus.POSTED)));

        Wallet clearingWallet = Wallet.forWorkspace(CLEARING_WALLET_ID, "platform-clearing");
        Wallet payeeWallet = Wallet.forUser(PAYEE_WALLET_ID, CREATOR_USER_ID);
        when(platformWalletService.requireClearingWallet()).thenReturn(clearingWallet);
        when(walletService.requireOrCreateUserWallet(CREATOR_USER_ID)).thenReturn(payeeWallet);
        when(platformFeeService.deductAtRelease(
                        eq(clearingWallet),
                        eq(MILESTONE_ID),
                        eq(CREATOR_USER_ID),
                        eq(new BigDecimal("10000.00")),
                        eq("INR"),
                        eq(ESCROW_HOLD_ID)))
                .thenReturn(
                        new PlatformFeeService.FeeDeductionResult(
                                new BigDecimal("10000.00"),
                                1500,
                                new BigDecimal("1500.00"),
                                new BigDecimal("8500.00"),
                                null));
        when(ledgerService.post(
                        eq(CLEARING_WALLET_ID),
                        eq(PAYEE_WALLET_ID),
                        eq(new BigDecimal("8500.00")),
                        eq("INR"),
                        eq(WalletTransactionType.ESCROW_RELEASE),
                        eq(TxnReferenceType.MILESTONE),
                        eq(MILESTONE_ID),
                        any(),
                        eq("release:" + ESCROW_HOLD_ID),
                        eq(null)))
                .thenReturn(
                        new WalletLedgerService.LedgerPostingResult(ledgerTxn("debit"), ledgerTxn("credit")));

        service.release(principal, WORKSPACE_ID, MILESTONE_ID);

        assertThat(hold.getStatus()).isEqualTo(EscrowStatus.RELEASED);
        assertThat(milestone.getStatus()).isEqualTo(MilestoneStatus.RELEASED);
        verify(escrowHoldRepository).save(hold);
    }

    // ------------------------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------------------------

    /** Seeds the whole release path up to the gate, with one deliverable in {@code status}. */
    private EscrowHold refusalFixture(DeliverableStatus status) {
        PaymentMilestone milestone = fundedMilestone();
        Collaboration collaboration =
                Collaboration.invite(COLLABORATION_ID, CAMPAIGN_ID, CREATOR_USER_ID, null, "INR");
        EscrowHold hold = fundedHold();

        when(brandContext.requireMember(principal, WORKSPACE_ID)).thenReturn(member);
        when(milestoneRepository.findByIdAndWorkspaceId(MILESTONE_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(milestone));
        when(escrowHoldRepository.findByIdForUpdate(ESCROW_HOLD_ID)).thenReturn(Optional.of(hold));
        when(collaborationRepository.findById(COLLABORATION_ID)).thenReturn(Optional.of(collaboration));
        when(disputeRepository.existsByCollaborationIdAndStatusIn(any(), any())).thenReturn(false);
        when(deliverableRepository.findByCollaborationIdOrderBySlotIndexAsc(COLLABORATION_ID))
                .thenReturn(List.of(deliverable(status)));
        return hold;
    }

    private void assertNoMoneyMoved(EscrowHold hold) {
        assertThat(hold.getStatus()).isEqualTo(EscrowStatus.FUNDED);
        // No ledger rows, no platform-fee rows, no clearing-wallet lookup: nothing downstream of
        // the gate was reached at all.
        verifyNoInteractions(ledgerService, platformFeeService, platformWalletService);
        verify(escrowHoldRepository, never()).save(any());
        verify(milestoneRepository, never()).save(any());
    }

    /**
     * Deliberately built with NO explicit {@code releaseCondition} — exactly like the milestones
     * {@code ContractService#generate} and {@code ContractService#amend} create — so this asserts
     * the default a real beta deal actually gets, not one the test chose.
     */
    private static PaymentMilestone fundedMilestone() {
        PaymentMilestone milestone =
                PaymentMilestone.builder()
                        .id(MILESTONE_ID)
                        .contractId("01HCONTRACT123456789")
                        .collaborationId(COLLABORATION_ID)
                        .sequenceNo(1)
                        .amount(new BigDecimal("10000.00"))
                        .status(MilestoneStatus.PENDING)
                        .build();
        assertEquals(
                ReleaseCondition.ON_POSTED,
                milestone.getReleaseCondition(),
                "a milestone created the way ContractService creates one must default to ON_POSTED");
        milestone.markFunded(ESCROW_HOLD_ID);
        return milestone;
    }

    private static EscrowHold fundedHold() {
        return EscrowHold.builder()
                .id(ESCROW_HOLD_ID)
                .workspaceId(WORKSPACE_ID)
                .campaignId(CAMPAIGN_ID)
                .milestoneId(MILESTONE_ID)
                .amount(new BigDecimal("10000.00"))
                .currency("INR")
                .status(EscrowStatus.FUNDED)
                .idempotencyKey("fund-idem")
                .build();
    }

    private static Deliverable deliverable(DeliverableStatus status) {
        return Deliverable.builder()
                .id("01HDELIVERABLE12345A")
                .collaborationId(COLLABORATION_ID)
                .creatorProfileId("profile-1")
                .slotIndex(1)
                .type(DeliverableType.INSTAGRAM_REEL)
                .title("Reel 1")
                .status(status)
                .build();
    }

    private static WalletTransaction ledgerTxn(String id) {
        return WalletTransaction.builder()
                .id(id)
                .walletId(CLEARING_WALLET_ID)
                .groupId("grp")
                .direction(TxnDirection.CREDIT)
                .type(WalletTransactionType.ESCROW_RELEASE)
                .amount(BigDecimal.ONE)
                .currency("INR")
                .balanceAfter(BigDecimal.ZERO)
                .build();
    }

    // ------------------------------------------------------------------------------------------
    // Shipped-config plumbing
    // ------------------------------------------------------------------------------------------

    /**
     * Wires the service's gate fields from the real {@code application.yml} and runs its own
     * {@code @PostConstruct} — the same two steps Spring performs at boot. Nothing in this class
     * hard-codes a cutover, so the behavioural tests below can only pass while the shipped
     * configuration genuinely leaves the gate on.
     */
    private static void applyShippedGateConfig(EscrowService service) {
        ReflectionTestUtils.setField(
                service, "releaseGateEnabled", Boolean.parseBoolean(shippedProperty(ENABLED_KEY)));
        ReflectionTestUtils.setField(
                service, "releaseGateCutoverInstantRaw", shippedProperty(CUTOVER_KEY));
        ReflectionTestUtils.invokeMethod(service, "initReleaseGateCutoverInstant");
    }

    private static Instant resolvedCutover(EscrowService service) {
        return (Instant) ReflectionTestUtils.getField(service, "releaseGateCutoverInstant");
    }

    /**
     * Resolves a property against the shipped {@code application.yml} ALONE — no system properties
     * and no OS environment in the property-source list. That is deliberate: this asserts what the
     * repository ships, so an {@code INFLUORA_ESCROW_RELEASE_GATE_*} variable set in whatever shell
     * happens to run the suite cannot make the assertion pass or fail. The {@code ${VAR:default}}
     * placeholder therefore always falls through to its committed default.
     */
    private static String shippedProperty(String key) {
        MutablePropertySources sources = new MutablePropertySources();
        for (PropertySource<?> source : loadApplicationYaml()) {
            sources.addLast(source);
        }
        return new PropertySourcesPropertyResolver(sources).getProperty(key, String.class);
    }

    private static List<PropertySource<?>> loadApplicationYaml() {
        try {
            return new YamlPropertySourceLoader()
                    .load("application", new ClassPathResource("application.yml"));
        } catch (IOException e) {
            throw new IllegalStateException("application.yml is not on the test classpath", e);
        }
    }
}
