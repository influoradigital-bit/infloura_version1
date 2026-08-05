package com.influora.service.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.common.ApiException;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.Dispute;
import com.influora.domain.entity.EscrowHold;
import com.influora.domain.entity.Payout;
import com.influora.domain.entity.WalletTopUp;
import com.influora.domain.enums.AdminRole;
import com.influora.domain.enums.EscrowStatus;
import com.influora.domain.enums.WalletTopUpStatus;
import com.influora.integration.razorpay.RazorpayClient;
import com.influora.integration.razorpay.RazorpayXClient;
import com.influora.repository.CampaignRepository;
import com.influora.repository.DisputeRepository;
import com.influora.repository.EscrowHoldRepository;
import com.influora.repository.PayoutRepository;
import com.influora.repository.WalletTopUpRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.PayoutReconciliationService;
import com.influora.web.dto.admin.AdminFinanceDtos.EscrowSummaryDto;
import com.influora.web.dto.admin.AdminFinanceDtos.FlaggedEscrowDto;
import com.influora.web.dto.admin.AdminFinanceDtos.PayoutRetryResultDto;
import com.influora.web.dto.admin.AdminFinanceDtos.ReconciliationItemDto;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backs {@code AdminFinanceController} — the admin Finance/Escrow console (Phase 2 per the CEO
 * directive; see {@code PlatformFeeAdminController} class javadoc for why {@code financeApi} is
 * mounted under {@code /admin/finance/*}).
 *
 * <p><b>Role-gating:</b> {@code SUPER_ADMIN} + {@code ADMIN}, MFA-satisfied — financial visibility,
 * excluded from {@code SUPPORT}. Same manual per-call {@link AdminContextService} pattern as every
 * other {@code Admin*Service} (no {@code @PreAuthorize} anywhere in this codebase).
 *
 * <p>Read-only: this first endpoint only READS {@code escrow_holds}; it moves no money and takes no
 * lock. Escrow release/hold/refund actions are separate queued items and get their own confirm.
 */
@Service
public class AdminFinanceService {

    private static final Logger log = LoggerFactory.getLogger(AdminFinanceService.class);

    private static final double SECONDS_PER_HOUR = 3600.0;
    private static final String FROZEN_NO_DISPUTE_REASON = "Frozen — no linked dispute";
    private static final String UNKNOWN_CAMPAIGN = "(unknown campaign)";

    private static final String RECON_STATUS_MATCHED = "MATCHED";
    private static final String RECON_STATUS_MISMATCH = "MISMATCH";
    private static final String RECON_STATUS_PENDING = "PENDING";

    /** RazorpayX payout statuses that are still in flight — never a final MATCHED/MISMATCH verdict. */
    private static final List<String> PAYOUT_IN_FLIGHT_STATUSES =
            List.of("queued", "pending", "processing");

    /** RazorpayX order statuses that are still in flight — mirrors {@link #PAYOUT_IN_FLIGHT_STATUSES}. */
    private static final List<String> ORDER_IN_FLIGHT_STATUSES = List.of("created", "attempted");

    private static final double AMOUNT_EPSILON = 0.01;

    private final AdminContextService adminContext;
    private final EscrowHoldRepository escrowHoldRepository;
    private final CampaignRepository campaignRepository;
    private final DisputeRepository disputeRepository;
    private final PayoutRepository payoutRepository;
    private final WalletTopUpRepository walletTopUpRepository;
    private final RazorpayXClient razorpayXClient;
    private final RazorpayClient razorpayClient;
    private final PayoutReconciliationService payoutReconciliationService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AdminFinanceService(
            AdminContextService adminContext,
            EscrowHoldRepository escrowHoldRepository,
            CampaignRepository campaignRepository,
            DisputeRepository disputeRepository,
            PayoutRepository payoutRepository,
            WalletTopUpRepository walletTopUpRepository,
            RazorpayXClient razorpayXClient,
            RazorpayClient razorpayClient,
            PayoutReconciliationService payoutReconciliationService) {
        this.adminContext = adminContext;
        this.escrowHoldRepository = escrowHoldRepository;
        this.campaignRepository = campaignRepository;
        this.disputeRepository = disputeRepository;
        this.payoutRepository = payoutRepository;
        this.walletTopUpRepository = walletTopUpRepository;
        this.razorpayXClient = razorpayXClient;
        this.razorpayClient = razorpayClient;
        this.payoutReconciliationService = payoutReconciliationService;
    }

    /**
     * {@code GET /admin/finance/escrow} — platform-wide escrow summary. Every figure is derived live
     * from {@code escrow_holds}; see {@link EscrowSummaryDto} for the exact per-field mapping and the
     * declared {@code pendingRelease} assumption.
     */
    @Transactional(readOnly = true)
    public EscrowSummaryDto getEscrowSummary(AuthPrincipal principal) {
        adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN);

        BigDecimal totalLocked = escrowHoldRepository.sumAmountByStatusIn(List.of(EscrowStatus.FUNDED));
        long pendingRelease = escrowHoldRepository.countByStatus(EscrowStatus.FUNDED);
        long flaggedTransactions = escrowHoldRepository.countByStatus(EscrowStatus.FROZEN);
        Double avgReleaseSeconds = escrowHoldRepository.avgReleaseSeconds();

        return new EscrowSummaryDto(
                totalLocked == null ? 0.0 : totalLocked.doubleValue(),
                pendingRelease,
                flaggedTransactions,
                avgReleaseSeconds == null ? 0.0 : avgReleaseSeconds / SECONDS_PER_HOUR);
    }

    /**
     * {@code GET /admin/escrow/flagged} — every {@code FROZEN} escrow hold with its campaign name and
     * flag reason attached (see {@link FlaggedEscrowDto} for per-field derivation and the declared
     * fallbacks). Read-only; moves no money. Campaign titles and dispute reasons are BATCH-loaded
     * (at most two extra queries total, never one-per-row).
     */
    @Transactional(readOnly = true)
    public List<FlaggedEscrowDto> getFlaggedEscrows(AuthPrincipal principal) {
        adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN);

        List<EscrowHold> frozen =
                escrowHoldRepository.findByStatusOrderByCreatedAtDesc(EscrowStatus.FROZEN);
        if (frozen.isEmpty()) {
            return List.of();
        }

        Map<String, String> titleByCampaignId =
                campaignRepository
                        .findAllById(
                                frozen.stream()
                                        .map(EscrowHold::getCampaignId)
                                        .filter(Objects::nonNull)
                                        .distinct()
                                        .toList())
                        .stream()
                        .collect(Collectors.toMap(Campaign::getId, Campaign::getTitle));

        // Most-recent dispute reason per collaboration — a hold is FROZEN precisely because a dispute
        // was opened on its collaboration (EscrowService.freezeUnreleasedForDispute).
        Map<String, String> reasonByCollaborationId =
                disputeRepository
                        .findByCollaborationIdIn(
                                frozen.stream()
                                        .map(EscrowHold::getCollaborationId)
                                        .filter(Objects::nonNull)
                                        .distinct()
                                        .toList())
                        .stream()
                        .sorted(Comparator.comparing(Dispute::getCreatedAt).reversed())
                        .collect(
                                Collectors.toMap(
                                        Dispute::getCollaborationId,
                                        Dispute::getReason,
                                        (mostRecent, older) -> mostRecent));

        return frozen.stream()
                .map(
                        hold ->
                                new FlaggedEscrowDto(
                                        hold.getId(),
                                        hold.getCampaignId(),
                                        hold.getCampaignId() == null
                                                ? null
                                                : titleByCampaignId.getOrDefault(
                                                        hold.getCampaignId(), UNKNOWN_CAMPAIGN),
                                        hold.getAmount() == null ? 0.0 : hold.getAmount().doubleValue(),
                                        hold.getCollaborationId() == null
                                                ? FROZEN_NO_DISPUTE_REASON
                                                : reasonByCollaborationId.getOrDefault(
                                                        hold.getCollaborationId(), FROZEN_NO_DISPUTE_REASON),
                                        hold.getCreatedAt() == null
                                                ? null
                                                : hold.getCreatedAt().toString()))
                .toList();
    }

    /**
     * {@code GET /admin/finance/reconciliation?date=YYYY-MM-DD} — internal ledger vs RazorpayX
     * gateway diff for every {@link Payout}/{@link WalletTopUp} row created on {@code date}. Every
     * figure is derived LIVE — for a {@link Payout}, from the row's own persisted {@code
     * webhookPayload} when present (avoids a live RazorpayX call entirely); only when no webhook has
     * landed yet does this fall back to a live {@link RazorpayXClient#fetchPayout} call. {@link
     * WalletTopUp} carries no persisted raw gateway payload, so its gateway side always requires a
     * live {@link RazorpayClient#fetchOrder} call once an order id exists. A gateway lookup failure
     * for one row (transport error, RazorpayX outage) degrades that ONE row to {@code PENDING} —
     * never fails the whole request. Read-only; moves no money, writes nothing. Gated SUPER_ADMIN +
     * ADMIN, MFA-satisfied — same tier as {@link #getEscrowSummary}.
     *
     * @param date {@code YYYY-MM-DD}; rows are matched by {@code createdAt} falling on this UTC day
     */
    @Transactional(readOnly = true)
    public List<ReconciliationItemDto> getReconciliation(AuthPrincipal principal, String date) {
        adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN, AdminRole.ADMIN);

        LocalDate day;
        try {
            day = LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            throw new ApiException(
                    "INVALID_DATE", "date must be formatted YYYY-MM-DD", HttpStatus.BAD_REQUEST);
        }
        Instant start = day.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        List<ReconciliationItemDto> items = new ArrayList<>();
        for (Payout payout : payoutRepository.findByCreatedAtBetween(start, end)) {
            items.add(reconcilePayout(payout));
        }
        for (WalletTopUp topUp : walletTopUpRepository.findByCreatedAtBetween(start, end)) {
            items.add(reconcileTopUp(topUp));
        }
        return items;
    }

    private ReconciliationItemDto reconcilePayout(Payout payout) {
        double internalAmount = payout.getAmount() == null ? 0.0 : payout.getAmount().doubleValue();
        String razorpayId = payout.getRazorpayPayoutId();

        // Queue-time placeholder (Payout#createPending) — RazorpayX was never actually called yet,
        // so there is nothing on the gateway side to compare against.
        if (razorpayId == null || razorpayId.startsWith("pending:")) {
            return new ReconciliationItemDto(
                    payout.getId(),
                    "",
                    payout.getId(),
                    0.0,
                    internalAmount,
                    internalAmount,
                    RECON_STATUS_PENDING,
                    timestamp(payout.getCreatedAt()));
        }

        GatewayFigure gateway = resolvePayoutGatewayFigure(payout);
        if (gateway == null) {
            return new ReconciliationItemDto(
                    payout.getId(),
                    razorpayId,
                    payout.getId(),
                    0.0,
                    internalAmount,
                    internalAmount,
                    RECON_STATUS_PENDING,
                    timestamp(payout.getCreatedAt()));
        }

        double variance = gateway.amount() - internalAmount;
        String status =
                classify(
                        variance,
                        gateway.status(),
                        PAYOUT_IN_FLIGHT_STATUSES,
                        payout.getStatus().equals(gateway.status()));
        return new ReconciliationItemDto(
                payout.getId(),
                razorpayId,
                payout.getId(),
                gateway.amount(),
                internalAmount,
                variance,
                status,
                timestamp(payout.getCreatedAt()));
    }

    private ReconciliationItemDto reconcileTopUp(WalletTopUp topUp) {
        double internalAmount = topUp.getAmount() == null ? 0.0 : topUp.getAmount().doubleValue();
        String razorpayId = topUp.getRazorpayOrderId();

        if (razorpayId == null || razorpayId.isBlank()) {
            return new ReconciliationItemDto(
                    topUp.getId(),
                    "",
                    topUp.getId(),
                    0.0,
                    internalAmount,
                    internalAmount,
                    RECON_STATUS_PENDING,
                    timestamp(topUp.getCreatedAt()));
        }

        GatewayFigure gateway = resolveTopUpGatewayFigure(razorpayId);
        if (gateway == null) {
            return new ReconciliationItemDto(
                    topUp.getId(),
                    razorpayId,
                    topUp.getId(),
                    0.0,
                    internalAmount,
                    internalAmount,
                    RECON_STATUS_PENDING,
                    timestamp(topUp.getCreatedAt()));
        }

        double variance = gateway.amount() - internalAmount;
        boolean topUpOutcomeAgrees =
                (topUp.getStatus() == WalletTopUpStatus.CREDITED && "paid".equals(gateway.status()))
                        || (topUp.getStatus() == WalletTopUpStatus.PENDING
                                && !"paid".equals(gateway.status()));
        String status = classify(variance, gateway.status(), ORDER_IN_FLIGHT_STATUSES, topUpOutcomeAgrees);
        return new ReconciliationItemDto(
                topUp.getId(),
                razorpayId,
                topUp.getId(),
                gateway.amount(),
                internalAmount,
                variance,
                status,
                timestamp(topUp.getCreatedAt()));
    }

    private static String classify(
            double variance, String gatewayStatus, List<String> inFlightStatuses, boolean outcomeAgrees) {
        if (inFlightStatuses.contains(gatewayStatus)) {
            return RECON_STATUS_PENDING;
        }
        boolean amountMatches = Math.abs(variance) < AMOUNT_EPSILON;
        return amountMatches && outcomeAgrees ? RECON_STATUS_MATCHED : RECON_STATUS_MISMATCH;
    }

    /** Rupees amount + RazorpayX status for one {@link Payout}, or {@code null} if unresolvable. */
    private record GatewayFigure(double amount, String status) {}

    private GatewayFigure resolvePayoutGatewayFigure(Payout payout) {
        String webhookPayload = payout.getWebhookPayload();
        if (webhookPayload != null && !webhookPayload.isBlank()) {
            try {
                JsonNode entity =
                        objectMapper.readTree(webhookPayload).path("payload").path("payout").path("entity");
                if (!entity.isMissingNode() && entity.has("amount")) {
                    return new GatewayFigure(
                            entity.path("amount").asLong(0) / 100.0, entity.path("status").asText(""));
                }
            } catch (Exception e) {
                log.warn(
                        "AdminFinanceService: could not parse stored webhookPayload for payout {} —"
                                + " falling back to a live RazorpayX fetch",
                        payout.getId());
            }
        }
        try {
            RazorpayXClient.PayoutResult result = razorpayXClient.fetchPayout(payout.getRazorpayPayoutId());
            if (result.rawResponse() == null) {
                // Unconfigured-outside-dev / dev-stub path (RazorpayXClient#fetchPayout) — no real
                // amount to report.
                return null;
            }
            JsonNode root = objectMapper.readTree(result.rawResponse());
            if (!root.has("amount")) {
                return null;
            }
            return new GatewayFigure(root.path("amount").asLong(0) / 100.0, result.status());
        } catch (Exception e) {
            log.warn(
                    "AdminFinanceService: live RazorpayX fetchPayout failed for payout {} — reporting"
                            + " PENDING rather than failing the whole reconciliation request",
                    payout.getId());
            return null;
        }
    }

    private GatewayFigure resolveTopUpGatewayFigure(String razorpayOrderId) {
        try {
            RazorpayClient.OrderResult result = razorpayClient.fetchOrder(razorpayOrderId);
            if (result.rawResponse() == null) {
                return null;
            }
            JsonNode root = objectMapper.readTree(result.rawResponse());
            long amountPaise =
                    root.has("amount_paid") && root.path("amount_paid").asLong(0) > 0
                            ? root.path("amount_paid").asLong(0)
                            : root.path("amount").asLong(0);
            return new GatewayFigure(amountPaise / 100.0, result.status());
        } catch (Exception e) {
            log.warn(
                    "AdminFinanceService: live RazorpayClient fetchOrder failed for order {} — reporting"
                            + " PENDING rather than failing the whole reconciliation request",
                    razorpayOrderId);
            return null;
        }
    }

    private static String timestamp(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    /**
     * {@code POST /admin/finance/payouts/{id}/retry} — re-initiates a definitively-failed creator
     * payout. All money-path logic (terminal-failure validation, the double-retry idempotency
     * guard, the re-credit bug-fix safety net, the fresh-debit/fresh-gateway-key retry itself) lives
     * in {@link PayoutReconciliationService#retryFailedPayout}; this method's only job is the role
     * gate and mapping the result to the wire DTO.
     *
     * <p>[Red-team F3 fix, RBAC] SUPER_ADMIN only — NOT SUPER_ADMIN+ADMIN like this class's other
     * (read-only) methods. {@code AdminContextService}'s own suggested role matrix (class javadoc
     * there) explicitly reserves "payout retry" for SUPER_ADMIN alongside escrow release/refund and
     * budget override — real money movement, a strictly higher bar than the finance-visibility
     * reads ({@link #getEscrowSummary}/{@link #getFlaggedEscrows}/{@link #getReconciliation}) ADMIN
     * is still allowed.
     *
     * <p>Deliberately NOT {@code @Transactional} — same reasoning as {@code
     * PayoutService#queuePayout}'s own javadoc: the delegate call below performs a real outbound
     * RazorpayX HTTP request, which must never run inside a held-open local DB transaction. Every
     * actual DB write along the way (wallet ledger posts, the {@code Payout} row save, the
     * idempotency-key reservation) already has its own correct transactional boundary in the beans
     * that perform it.
     */
    public PayoutRetryResultDto retryPayout(AuthPrincipal principal, String payoutId) {
        adminContext.requireRoleWithMfaSatisfied(principal, AdminRole.SUPER_ADMIN);

        Payout payout = payoutReconciliationService.retryFailedPayout(payoutId);

        return new PayoutRetryResultDto(
                payout.getId(),
                payout.getStatus(),
                payout.getRazorpayPayoutId(),
                timestamp(payout.getUpdatedAt()));
    }
}
