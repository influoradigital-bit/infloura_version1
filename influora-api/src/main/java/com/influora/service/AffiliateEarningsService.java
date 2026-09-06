package com.influora.service;

import com.influora.common.ApiException;
import com.influora.common.Ulids;
import com.influora.domain.entity.AffiliateEarning;
import com.influora.domain.entity.Campaign;
import com.influora.domain.entity.CouponCode;
import com.influora.domain.entity.CouponRedemption;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.Workspace;
import com.influora.repository.AffiliateEarningRepository;
import com.influora.repository.CampaignRepository;
import com.influora.repository.CouponCodeRepository;
import com.influora.repository.CouponRedemptionRepository;
import com.influora.repository.WorkspaceRepository;
import com.influora.security.AuthPrincipal;
import com.influora.web.dto.affiliate.AffiliateEarningDtos.AffiliateEarningRow;
import com.influora.web.dto.affiliate.AffiliateEarningDtos.AffiliateEarningsSummary;
import com.influora.web.dto.affiliate.AffiliateEarningDtos.CreatorAffiliateEarningsResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes and records a creator's affiliate commission when a qualifying conversion happens (Wave
 * D task D4, wiki/tech/REMAINING_WORK_PLAN.md). "Qualifying conversion" here means a {@link
 * CouponRedemption} -- unlike a bare UTM click/conversion (which has no {@code workspace_id} and no
 * per-creator revenue rollup, see {@code ConversionTrackingService} class javadoc), a coupon
 * redemption is already workspace/campaign/creator-scoped via its owning {@link CouponCode} and
 * carries the exact per-order commission base ({@code orderAmount}) this service needs.
 *
 * <p><b>Extension point, not a new idempotency mechanism [task instruction: "don't duplicate their
 * idempotency logic, extend it"]</b> -- {@link #recordEarning} is called by {@code
 * RedemptionWriter#doRedeem} AFTER {@code redemptionRepository.save(redemption)} has already run
 * inside {@code RedemptionService}'s own {@code IdempotencyService.executeOnce} wrapper (see {@code
 * RedemptionService#redeem} javadoc). This means: (1) this method never needs to re-derive "was this
 * redemption processed" -- {@code RedemptionService} already answered that; a redemption row only
 * ever gets built once, ever, and replayed redemptions return the SAME persisted row and never
 * re-enter {@code doRedeem} at all, so this method is never invoked twice for the same redemption
 * via that path. (2) The commission write itself still needs its OWN idempotency guard, independent
 * of the caller's -- not because the caller can replay, but because this service is also the natural
 * place a future retry/backfill path would call from directly (e.g. re-processing a redemption after
 * this service's own earlier attempt failed). That guard is the {@code UNIQUE(redemption_id)}
 * constraint on {@code affiliate_earnings} (V27) plus a derived {@code idempotencyKey} (see {@link
 * #deriveIdempotencyKey}), run through the SAME shared {@link IdempotencyService#executeOnce} helper
 * {@code PayoutService}/{@code RedemptionService} use -- not a bespoke check-then-insert.
 *
 * <p><b>Validation before executeOnce [mirrors PayoutService's E2 HIGH-1 fix]</b> -- the coupon
 * lookup (for {@code workspaceId}/{@code campaignId}/{@code creatorId}) and commission calculation
 * both run BEFORE {@link IdempotencyService#executeOnce} reserves the key, exactly like {@code
 * PayoutService#validateForPayout} runs before its own {@code executeOnce} call. A missing/invalid
 * coupon throws straight out without ever touching {@code idempotency_keys}, so a bad input can
 * never reserve-then-FAIL a key and wedge a later legitimate (corrected) retry.
 *
 * <p><b>Commission rate [P2-13, updated]</b> -- {@link Campaign} now carries an optional
 * per-campaign {@code commissionRate} override (V50 migration; see
 * wiki/decisions/2026-07-12-P2-13-affiliate-commission-rate-model.md). {@link #validateAndCompute}
 * resolves the rate to use as: the redemption's coupon's campaign's {@code commissionRate} if that
 * campaign has one configured (non-null), otherwise the flat {@link #DEFAULT_COMMISSION_RATE}
 * fallback -- applied to each redemption's {@code orderAmount} (the qualifying sale total)
 * deliberately NOT {@code discountApplied} (the discount given to the customer); those are two
 * different fields on {@link CouponRedemption} and mixing them up would either overpay or
 * underpay the creator. A rate change on a campaign only affects redemptions recorded AFTER the
 * change -- already-persisted {@link AffiliateEarning} rows are never recomputed (rate is resolved
 * once, at record time).
 *
 * <p><b>[SEC: Kabir, Wave D task D4 HIGH-1 -- FIXED] Self-invocation defeated {@code
 * @Transactional} here too (the review's "second instance").</b> {@link #recordEarning} previously
 * called {@code this.doRecordEarning(...)} from inside the {@code executeOnce} lambda -- the same
 * same-bean self-invocation bug as {@code RedemptionService#doRedeem} (see that class's javadoc for
 * the full mechanism). Fixed identically: an {@code @Lazy}-qualified self-reference ({@link #self})
 * is injected and {@link #recordEarning} now calls {@code self.doRecordEarning(...)} through the
 * Spring-managed proxy, so {@code @Transactional} on {@link #doRecordEarning} is genuinely honored.
 *
 * <p><b>V-GA-7</b> — {@link #listForCreator} is the principal-scoped read for {@code GET
 * /creator/affiliate-earnings}. Identity from {@link CreatorContextService}; rows filtered by
 * {@code creator_id = profile.id} only.
 */
@Service
public class AffiliateEarningsService {

    private static final String IDEMPOTENCY_SCOPE = "affiliate.earning.record";

    /**
     * Flat commission rate applied to a qualifying redemption's {@code orderAmount} until a real
     * per-campaign rate configuration exists (see class javadoc). 10% is a deliberately
     * conservative placeholder -- {@code TODO(follow-up)}: replace with a configured rate once
     * product/Rohan sign off on the number and a schema column exists to store it per campaign.
     */
    static final BigDecimal DEFAULT_COMMISSION_RATE = new BigDecimal("0.10");

    /**
     * Reserved-namespace prefix for the derived idempotency key (see {@link
     * #deriveIdempotencyKey}) -- kept for consistency with {@code ConversionTrackingService}'s
     * {@code DERIVED_KEY_PREFIX} convention even though, unlike that class, there is no
     * caller-supplied-key path here to squat: {@code redemptionId} is always server-derived (see
     * {@link #deriveIdempotencyKey} javadoc), never accepted directly from an external caller.
     */
    static final String DERIVED_KEY_PREFIX = "affearn:";

    private final AffiliateEarningRepository affiliateEarningRepository;
    private final CouponCodeRepository couponCodeRepository;
    private final CouponRedemptionRepository couponRedemptionRepository;
    private final CampaignRepository campaignRepository;
    private final WorkspaceRepository workspaceRepository;
    private final CreatorContextService creatorContext;
    private final AuditLogService auditLogService;
    private final IdempotencyService idempotencyService;

    /**
     * {@code @Lazy} self-reference used ONLY so {@link #recordEarning} can invoke {@link
     * #doRecordEarning} through the Spring-managed proxy instead of same-bean self-invocation --
     * see class javadoc "[SEC: Kabir ... FIXED]" and {@code RedemptionService}'s identical {@code
     * self} field for why this is required. Deliberately NOT {@code final}: Spring always supplies
     * it via the {@code @Lazy} constructor parameter in production (this is not a mutability hole
     * in real usage), but leaving it settable lets a plain unit test wire {@code self} back to the
     * instance under test without a proxy, via {@link #setSelfForTesting} -- there is no AOP
     * container in a Mockito-based unit test, so the test's "proxy" is simply itself.
     */
    private AffiliateEarningsService self;

    public AffiliateEarningsService(
            AffiliateEarningRepository affiliateEarningRepository,
            CouponCodeRepository couponCodeRepository,
            CouponRedemptionRepository couponRedemptionRepository,
            CampaignRepository campaignRepository,
            WorkspaceRepository workspaceRepository,
            CreatorContextService creatorContext,
            AuditLogService auditLogService,
            IdempotencyService idempotencyService,
            @Lazy AffiliateEarningsService self) {
        this.affiliateEarningRepository = affiliateEarningRepository;
        this.couponCodeRepository = couponCodeRepository;
        this.couponRedemptionRepository = couponRedemptionRepository;
        this.campaignRepository = campaignRepository;
        this.workspaceRepository = workspaceRepository;
        this.creatorContext = creatorContext;
        this.auditLogService = auditLogService;
        this.idempotencyService = idempotencyService;
        this.self = self;
    }

    /**
     * Test-only seam -- wires {@link #self} to {@code instance} itself so a plain unit test (no
     * Spring container/proxy) can exercise {@link #recordEarning}'s call through {@code self}
     * without needing a real AOP proxy. Production code never calls this; Spring supplies {@link
     * #self} via the {@code @Lazy} constructor parameter instead. Package-visible, test-only.
     */
    void setSelfForTesting(AffiliateEarningsService instance) {
        this.self = instance;
    }

    /** CR-83 — caps a caller-supplied {@code limit} so a large value can't force one unbounded query. */
    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 20;

    /**
     * Principal-scoped list + SETTLED-vs-pending summary for the authenticated creator (V-GA-7).
     * Never accepts a creator-id path/query param — identity from JWT via {@link
     * CreatorContextService#requireCreatorProfile}.
     *
     * <p>CR-83 — {@code page}/{@code limit} slice which rows come back; {@code summary} is still
     * computed from the creator's FULL history (it has to be — "unsettled commission" and "this
     * month" are all-time/period aggregates, not properties of one page) so the underlying full
     * fetch stays as it was. What changed is that the response no longer serializes every row
     * ever recorded — only the requested page, plus enough metadata ({@code totalElements},
     * {@code hasMore}) for the client to page through the rest instead of it growing unboundedly.
     */
    @Transactional(readOnly = true)
    public CreatorAffiliateEarningsResponse listForCreator(AuthPrincipal principal, Integer page, Integer limit) {
        int pageNumber = page != null && page >= 0 ? page : 0;
        int pageSize = limit != null && limit > 0 ? Math.min(limit, MAX_PAGE_SIZE) : DEFAULT_PAGE_SIZE;

        CreatorProfile profile = creatorContext.requireCreatorProfile(principal);
        List<AffiliateEarning> earnings =
                affiliateEarningRepository.findByCreatorIdOrderByCreatedAtDesc(profile.getId());

        // CR-83 follow-up (Priya red-team) — `pageNumber * pageSize` as a plain int multiply
        // overflows for a large caller-supplied page (e.g. page=100000000, limit=100), wrapping
        // negative and throwing IndexOutOfBoundsException out of subList. Widened to long before
        // the multiply so an absurd page number degrades to "empty page", not a 500.
        long fromIndexLong = (long) pageNumber * pageSize;
        int fromIndex = (int) Math.min(fromIndexLong, earnings.size());
        int toIndex = (int) Math.min(fromIndexLong + pageSize, earnings.size());
        List<AffiliateEarning> pageOfEarnings = earnings.subList(fromIndex, toIndex);

        Set<String> campaignIds =
                pageOfEarnings.stream().map(AffiliateEarning::getCampaignId).collect(Collectors.toSet());
        Map<String, Campaign> campaignsById =
                campaignRepository.findAllById(campaignIds).stream()
                        .collect(Collectors.toMap(Campaign::getId, Function.identity()));

        Set<String> workspaceIds =
                pageOfEarnings.stream().map(AffiliateEarning::getWorkspaceId).collect(Collectors.toSet());
        Map<String, Workspace> workspacesById =
                workspaceRepository.findAllById(workspaceIds).stream()
                        .collect(Collectors.toMap(Workspace::getId, Function.identity()));

        // Fetched once against the FULL history, not just this page — buildSummary needs every
        // earning's redemption for its all-time/this-month aggregates, and reusing this map for
        // row-building too (rather than a second, page-scoped query) avoids querying it twice.
        Set<String> redemptionIds =
                earnings.stream().map(AffiliateEarning::getRedemptionId).collect(Collectors.toSet());
        Map<String, CouponRedemption> redemptionsById =
                couponRedemptionRepository.findAllById(redemptionIds).stream()
                        .collect(Collectors.toMap(CouponRedemption::getId, Function.identity()));

        List<AffiliateEarningRow> rows =
                pageOfEarnings.stream()
                        .map(
                                e ->
                                        toRow(
                                                e,
                                                campaignsById.get(e.getCampaignId()),
                                                workspacesById.get(e.getWorkspaceId()),
                                                redemptionsById.get(e.getRedemptionId())))
                        .toList();

        return new CreatorAffiliateEarningsResponse(
                rows,
                buildSummary(earnings, redemptionsById),
                pageNumber,
                pageSize,
                earnings.size(),
                toIndex < earnings.size());
    }

    private static AffiliateEarningRow toRow(
            AffiliateEarning earning,
            Campaign campaign,
            Workspace workspace,
            CouponRedemption redemption) {
        return new AffiliateEarningRow(
                earning.getId(),
                earning.getCampaignId(),
                campaign != null ? Objects.requireNonNullElse(campaign.getTitle(), "") : "",
                workspace != null ? Objects.requireNonNullElse(workspace.getName(), "") : "",
                earning.getRedemptionId(),
                redemption != null ? redemption.getOrderId() : null,
                redemption != null ? redemption.getOrderAmount() : null,
                earning.getCommissionAmount(),
                earning.getCurrency(),
                earning.getStatus().name(),
                earning.getSettlementBatchId(),
                earning.getCreatedAt(),
                earning.getSettledAt());
    }

    private static AffiliateEarningsSummary buildSummary(
            List<AffiliateEarning> earnings, Map<String, CouponRedemption> redemptionsById) {
        Instant monthStart =
                LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1).atStartOfDay().toInstant(ZoneOffset.UTC);

        long thisMonthSales = 0;
        BigDecimal thisMonthRevenue = BigDecimal.ZERO;
        BigDecimal thisMonthCommission = BigDecimal.ZERO;
        BigDecimal unsettledCommission = BigDecimal.ZERO;
        String currency = "INR";

        for (AffiliateEarning e : earnings) {
            currency = e.getCurrency();
            if (e.getStatus() == AffiliateEarning.Status.PENDING
                    || e.getStatus() == AffiliateEarning.Status.FAILED) {
                unsettledCommission = unsettledCommission.add(e.getCommissionAmount());
            }
            if (e.getCreatedAt() != null && !e.getCreatedAt().isBefore(monthStart)) {
                thisMonthSales++;
                thisMonthCommission = thisMonthCommission.add(e.getCommissionAmount());
                CouponRedemption redemption = redemptionsById.get(e.getRedemptionId());
                if (redemption != null && redemption.getOrderAmount() != null) {
                    thisMonthRevenue = thisMonthRevenue.add(redemption.getOrderAmount());
                }
            }
        }

        return new AffiliateEarningsSummary(
                thisMonthSales,
                thisMonthRevenue.setScale(2, RoundingMode.HALF_UP),
                thisMonthCommission.setScale(2, RoundingMode.HALF_UP),
                unsettledCommission.setScale(2, RoundingMode.HALF_UP),
                currency);
    }

    /**
     * Records (or, on replay, no-ops on) the affiliate commission earned for one qualifying
     * redemption. Never throws for a redemption that has already been credited -- returns the
     * existing {@link AffiliateEarning} row instead, exactly like {@code RedemptionService#redeem}'s
     * own replay semantics.
     *
     * <p><b>Callers -- there are exactly two, and the distinction matters:</b>
     *
     * <ol>
     *   <li>{@link com.influora.service.tracking.RedemptionWriter#doRedeem} -- SYNCHRONOUS, in the
     *       same transaction, immediately after the redemption row is saved and the money event is
     *       audited. This is the primary path: it is how a creator's commission actually gets
     *       created in production.
     *   <li>{@code AffiliateEarningReconciliationJob#runReconciliation} -- the hourly
     *       belt-and-suspenders sweep, for any redemption past its grace period with no matching
     *       earning. Post-fix a nonzero backfill is a DEFECT SIGNAL, not routine.
     * </ol>
     *
     * <p><b>History, because this doc has been wrong in both directions.</b> Caller (1) was
     * documented across several files as an accomplished fact long before it existed -- the Wave D
     * task D4 fix was written and reviewed but the redemption-side half was never committed. A
     * verification note added here during T-FESTIVALBOX-0905 phase 4 correctly recorded that
     * absence ("that call does NOT exist"). {@code wiki/tech/tracking-subsystem-ruling.md} Q1
     * (Priya, CTO, binding) then ruled the synchronous call was the intended design all along and
     * its absence a P0 regression, and it has since been wired into {@code RedemptionWriter}. So
     * BOTH earlier versions of this paragraph are now historical: the original was aspirational,
     * the phase-4 correction was accurate when written and is no longer true. This one describes
     * the code as it actually is -- verify before trusting it, as ever.
     *
     * <p>The brand-level guard below is load-bearing for both callers: a page-level Festival Box
     * coupon has no creator, so there is nobody to pay a commission to.
     *
     * @param redemption the just-recorded (or already-existing, on replay) {@link CouponRedemption}
     * @return the recorded (or already-existing, on replay) {@link AffiliateEarning}, or {@code
     *     null} if {@code redemption}'s coupon is brand-level ({@link CouponCode#isBrandLevel()}) --
     *     see "Brand-level coupons" below. Never throws for that case.
     * @throws ApiException {@code COUPON_NOT_FOUND} (404) if the redemption's {@code couponId} does
     *     not resolve -- should not happen in practice (the coupon must have existed for the
     *     redemption to have been created), kept as a fail-closed guard rather than an assumption
     * @throws ApiException {@code IDEMPOTENCY_KEY_IN_PROGRESS} (409) if a concurrent attempt to
     *     record this exact earning is in flight -- retry-safe
     */
    public AffiliateEarning recordEarning(CouponRedemption redemption) {
        // [SEC: Kabir] Structural replay check FIRST, before any validation/mutation -- mirrors
        // PayoutService#replayIfPresent / RedemptionService#replayIfPresent. UNIQUE(redemption_id)
        // backstops this at the DB level regardless.
        AffiliateEarning replay = replayIfPresent(redemption.getId());
        if (replay != null) {
            return replay;
        }

        CouponCode coupon = findCoupon(redemption);

        // [T-FESTIVALBOX-0905 phase 4, task brief "landmine 1"] A brand-level coupon
        // (CouponCode#isBrandLevel(), creator_id IS NULL) has no creator to pay a commission to.
        // AffiliateEarning.creatorId is, and remains, NOT NULL -- so this MUST be checked here,
        // before validateAndCompute/executeOnce ever run, exactly like the COUPON_NOT_FOUND check
        // below: skip cleanly and return null, never throw, never reserve an idempotency key for a
        // commission that structurally cannot exist. The redemption itself (and its usage-count
        // increment) already happened in RedemptionWriter#doRedeem before this method is ever
        // called -- skipping here only skips the (nonexistent) commission, not the sale record.
        if (coupon.isBrandLevel()) {
            return null;
        }

        // [mirrors PayoutService E2 HIGH-1] ALL validation + the commission calculation run here,
        // BEFORE executeOnce reserves the idempotency key -- a validation failure must never
        // reserve-then-FAIL a key and wedge a later legitimate retry.
        EarningContext ctx = validateAndCompute(redemption, coupon);
        String idempotencyKey = deriveIdempotencyKey(redemption.getId());

        try {
            // [SEC: Kabir, HIGH-1 fix] Call through the injected self-proxy, NOT `this` -- see
            // class javadoc. This makes doRecordEarning's @Transactional genuinely honored.
            return idempotencyService.executeOnce(
                    idempotencyKey,
                    ctx.coupon().getWorkspaceId(),
                    IDEMPOTENCY_SCOPE,
                    () -> self.doRecordEarning(ctx, idempotencyKey));
        } catch (IdempotencyService.AlreadyInProgressException
                | IdempotencyService.AlreadyCompletedException raced) {
            AffiliateEarning won = replayIfPresent(redemption.getId());
            if (won != null) {
                return won;
            }
            throw new ApiException(
                    "IDEMPOTENCY_KEY_IN_PROGRESS",
                    "This affiliate earning is already being recorded -- retry shortly",
                    HttpStatus.CONFLICT);
        }
    }

    private AffiliateEarning replayIfPresent(String redemptionId) {
        return affiliateEarningRepository.findByRedemptionId(redemptionId).orElse(null);
    }

    /**
     * Bundles the redemption id alongside the validated coupon/commission so {@link
     * #doRecordEarning} needs no re-lookup. {@code commissionRate} is carried through too (not just
     * the computed amount) purely so {@link #doRecordEarning} can log which rate was actually used
     * in the audit event (P2-13) without re-deriving it.
     */
    private record EarningContext(
            String redemptionId, CouponCode coupon, BigDecimal commissionRate, BigDecimal commissionAmount) {}

    /**
     * Resolves {@code redemption}'s coupon. Extracted so {@link #recordEarning} can check {@link
     * CouponCode#isBrandLevel()} BEFORE calling {@link #validateAndCompute} -- both need the coupon,
     * but only one lookup should happen per call.
     *
     * @throws ApiException {@code COUPON_NOT_FOUND} (404) -- see {@link #recordEarning} javadoc
     */
    private CouponCode findCoupon(CouponRedemption redemption) {
        return couponCodeRepository
                .findById(redemption.getCouponId())
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "COUPON_NOT_FOUND",
                                        "Coupon for this redemption was not found",
                                        HttpStatus.NOT_FOUND));
    }

    private EarningContext validateAndCompute(CouponRedemption redemption, CouponCode coupon) {
        // P2-13 (wiki/decisions/2026-07-12-P2-13-affiliate-commission-rate-model.md): resolve the
        // campaign's configured override rate, falling back to the flat DEFAULT_COMMISSION_RATE
        // when the campaign has none set (null) OR — fail-safe — when the campaign row itself
        // can't be found (should not happen in practice; a coupon's campaignId always points at a
        // real campaign, but this keeps existing flat-10% behavior rather than throwing on a
        // data-integrity edge case this service has no business enforcing).
        BigDecimal commissionRate =
                campaignRepository
                        .findById(coupon.getCampaignId())
                        .map(Campaign::getCommissionRate)
                        .filter(Objects::nonNull)
                        .orElse(DEFAULT_COMMISSION_RATE);

        BigDecimal commissionAmount =
                redemption.getOrderAmount().multiply(commissionRate).setScale(2, RoundingMode.HALF_UP);

        return new EarningContext(redemption.getId(), coupon, commissionRate, commissionAmount);
    }

    /**
     * Runs ONLY inside {@code executeOnce} -- {@code ctx} was already validated/computed before the
     * idempotency key was reserved (see {@link #recordEarning} javadoc above). The only things that
     * can throw from this point on are the persistence calls themselves, which SHOULD mark the key
     * FAILED (and remain retryable, per {@link IdempotencyService}) on failure.
     *
     * <p><b>[Kabir M-4] {@code public}, and it has to be — {@code protected} made the
     * {@code @Transactional} below a silent no-op.</b> The self-proxy call in {@link
     * #recordEarning} fixed the self-invocation half of this problem (HIGH-1, see class javadoc)
     * and the annotation still did nothing, because Spring's default {@code
     * AnnotationTransactionAttributeSource} is constructed with {@code publicMethodsOnly = true}:
     * it returns no transaction attribute for a non-public method, no warning, no error. Verified
     * for this project rather than assumed — Spring Boot 3.3.5 with no custom {@code
     * TransactionAttributeSource} and no {@code @EnableTransactionManagement} override anywhere in
     * {@code src/main}, so the auto-configured default is what applies.
     *
     * <p>WHAT THAT ACTUALLY COST, and why it was not visible in testing: this method makes two
     * writes — the {@link AffiliateEarning} row and the {@code AFFILIATE_EARNING_RECORDED} money
     * audit event. Whether they are atomic depended entirely on the CALLER's ambient transaction:
     *
     * <ul>
     *   <li>{@code AffiliateEarningRecordingListener#onCouponRedeemed} is {@code
     *       @Transactional(REQUIRES_NEW)}, so on the normal redemption path the two writes were
     *       atomic — by accident of the caller, not because of the annotation here.
     *   <li>{@code AffiliateEarningReconciliationJob#reconcileMissingAffiliateEarnings} is {@code
     *       @Scheduled} with NO transaction (and delegates to a private method, so annotating it
     *       would have been self-invocation anyway). On that path the two writes were independent:
     *       a failure in {@code recordMoneyEvent} left a committed commission with no money-audit
     *       entry — an unauditable payout.
     * </ul>
     *
     * <p>The cron is the RECOVERY path — it exists precisely because the synchronous path can miss
     * — so the weaker guarantee sat on the path that runs when something has already gone wrong.
     * Making this method public gives it its own transaction and makes both callers atomic without
     * either of them having to know.
     *
     * <p>Widened visibility is not an invitation: this still must only be called through {@code
     * executeOnce}, because nothing in here re-checks the idempotency key. {@code
     * RedemptionWriter#doRedeem} is public under the same contract for the same reason. In this
     * case the compiler happens to enforce it for free — {@link EarningContext} is a PRIVATE nested
     * record, so no code outside this class can construct the argument, and the method is reachable
     * in practice only through the proxy. Keep it that way: making {@code EarningContext} public
     * would quietly turn this into a genuinely callable unguarded write path.
     */
    @Transactional
    public AffiliateEarning doRecordEarning(EarningContext ctx, String idempotencyKey) {
        CouponCode coupon = ctx.coupon();

        AffiliateEarning earning =
                AffiliateEarning.builder()
                        .id(Ulids.newUlid())
                        .workspaceId(coupon.getWorkspaceId())
                        .campaignId(coupon.getCampaignId())
                        .creatorId(coupon.getCreatorId())
                        .redemptionId(ctx.redemptionId())
                        .commissionAmount(ctx.commissionAmount())
                        .currency("INR")
                        .idempotencyKey(idempotencyKey)
                        .build();

        affiliateEarningRepository.save(earning);

        auditLogService.recordMoneyEvent(
                coupon.getWorkspaceId(),
                "AFFILIATE_EARNING_RECORDED",
                ctx.commissionAmount(),
                null,
                null,
                idempotencyKey,
                Map.of(
                        "earningId", earning.getId(),
                        "redemptionId", earning.getRedemptionId(),
                        "couponId", coupon.getId(),
                        "campaignId", coupon.getCampaignId(),
                        "creatorId", coupon.getCreatorId(),
                        // P2-13: which rate actually applied to this earning (default 0.10 or the
                        // campaign's configured override) — closes the reconciliation gap flagged
                        // in the spec's "Audit trail" section before this becomes a multi-rate
                        // system with no record of which rate produced which payout.
                        "commissionRate", ctx.commissionRate().toPlainString()));

        return earning;
    }

    /**
     * Derives a deterministic idempotency key from {@code redemptionId} alone -- unlike {@code
     * ConversionTrackingService#deriveFallbackKey}, there is no attacker-controlled-input threat
     * model here: {@code redemptionId} is a server-generated ULID from {@code
     * RedemptionService#doRedeem}'s own validated, idempotency-guarded write, never a raw
     * caller-supplied value. A plain, reserved-prefixed concatenation is sufficient (mirrors {@code
     * PayoutService}'s {@code "payout:" + milestoneId} shape) -- hashing would add no security value
     * here since there is nothing attacker-visible to hide or predict.
     */
    static String deriveIdempotencyKey(String redemptionId) {
        return DERIVED_KEY_PREFIX + redemptionId;
    }
}
