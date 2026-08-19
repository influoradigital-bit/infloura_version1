package com.influora.service.billing;

import com.influora.common.ApiException;
import com.influora.common.Ulids;
import com.influora.domain.entity.Plan;
import com.influora.domain.entity.Subscription;
import com.influora.domain.enums.BillingCycle;
import com.influora.domain.enums.PlanCode;
import com.influora.domain.enums.SubscriptionStatus;
import com.influora.integration.razorpay.RazorpayClient;
import com.influora.repository.PlanRepository;
import com.influora.repository.SubscriptionRepository;
import com.influora.service.IdempotencyService;
import com.influora.service.meera.AICreditService;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Subscription lookup, plan resolution, lazy Free-tier provisioning, and (Phase 2, Task 19/20)
 * the real Razorpay Subscriptions checkout/cancel/webhook-upsert flows. Task 18/19/20
 * subscription-billing functional core.
 *
 * <p><b>{@link #getActivePlanForWorkspace(String)} is the load-bearing method here</b> — every
 * later phase's fee resolution (Task 21, {@code BrandCampaignFeeService}) and plan-gating (Task
 * 22, {@code PlanGateFilter}) calls this to find out what a workspace is entitled to. It must
 * never throw for a workspace with no subscription row — it falls back to the Free plan, matching
 * the "Free tier is permanently available, no trial" architecture (§0/§1.3 of the plan).
 *
 * <p><b>No local {@code Subscription} row is created at checkout-initiation time.</b> A newly
 * created Razorpay subscription is unpaid until the brand completes the hosted checkout — writing
 * an ACTIVE row before that would let a brand who abandons checkout (or a forged/duplicated
 * request) get Pro entitlements for free. A {@code PRO} row is created/updated only from a
 * verified webhook ({@link #applySubscriptionWebhookUpdate}), matching TECH-STACK.md rule #4
 * ("money/state changes are server-derived from the payment processor's callback, never a client
 * request").
 *
 * <p><b>BL-5 correction (BrandF.md §101, re-corrected after Priya's review):</b> this class has
 * FIVE other {@code Subscription} writers, none of which goes through the webhook, and the
 * sentence above must not be read as "only ever written from a verified webhook" — that
 * generalization is false and was the load-bearing (and incorrect) claim of an earlier audit
 * pass. (The first correction pass here undercounted this list as "two" and omitted {@link
 * #cancel(String)} entirely; the second added the missing {@code cancel} bullet but left the
 * count word reading "FOUR" against a five-item list, so the enumeration still under-reported a
 * controller-reachable writer to anyone who trusted the count instead of counting the bullets.
 * Both the count and the list below are re-derived from every {@code
 * subscriptionRepository.save(...)}/{@code saveAndFlush(...)} call site in this class — five
 * non-webhook, plus the two inside {@link #applySubscriptionWebhookUpdate} — so they cannot
 * drift apart again without a save call site being added or removed.)
 *
 * <ul>
 *   <li>{@link #createFreeSubscription} — writes a {@code FREE}/{@code ACTIVE} row, reached from
 *       {@code GET /billing/plan} via {@link #getOrCreateFreeSubscription}. No payment risk: Free
 *       is the zero-cost baseline plan, the caller is scoped to their own workspace via {@code
 *       BrandContextService.requireBrandWorkspace}, and this path can never write {@code PRO}.
 *   <li>{@link #cancel(String)} — reached from {@code POST /billing/cancel}. Sets {@code
 *       cancelAtPeriodEnd=true} on the caller's own workspace subscription; status is
 *       intentionally left unchanged (still {@code ACTIVE} until the period actually elapses —
 *       see that method's own comment). No plan/status escalation and no payment risk: this path
 *       can only schedule a future cancellation, never grant or extend paid entitlement.
 *   <li>{@link #applyRenewalSafetyNet} — advances the current period on an existing row. Not
 *       reachable from any controller; called only by {@link
 *       com.influora.job.SubscriptionRenewalResetJob}, an internal scheduled job with no HTTP
 *       entry point.
 *   <li>{@link #grantAdminPlan} — comp/override writes, any plan. SUPER_ADMIN + MFA-gated via
 *       {@code AdminBillingService}; intentionally an administrator-triggered exception to the
 *       "webhook-only" default, not a gap in it.
 *   <li>{@link #finalizeLapsedCancellation} — BL-2 fix (BrandF.md §98): flips a
 *       cancel-at-period-end row to {@code CANCELLED} once its period has lapsed. Not reachable
 *       from any controller; called only by {@link com.influora.job.SubscriptionRenewalResetJob},
 *       same trust boundary as {@link #applyRenewalSafetyNet}. No plan/status escalation and no
 *       payment risk: this path can only move a row DOWN to the terminal cancelled state.
 * </ul>
 *
 * <p>The actual invariant this class enforces is narrower than "webhook-only": <b>no path other
 * than the verified webhook can ever write a paid ({@code PRO}) row funded by an unauthenticated
 * or unverified caller.</b> Free-tier and job-driven writes are workspace-/admin-scoped by
 * construction; only {@link #applySubscriptionWebhookUpdate} can move a workspace onto paid Pro
 * entitlement.
 */
@Service
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

    /**
     * Razorpay Subscriptions has no "run forever" mode — a large-but-finite cycle count is the
     * documented convention for an effectively-indefinite monthly plan. 120 monthly cycles = 10
     * years; cancellation ({@link #cancel}) remains available at any time, so this horizon is
     * never actually reached by a subscription still in good standing.
     */
    private static final int DEFAULT_TOTAL_COUNT = 120;

    private final SubscriptionRepository subscriptionRepository;
    private final PlanRepository planRepository;
    private final PlanService planService;
    private final RazorpayClient razorpayClient;
    private final AICreditService aiCreditService;
    private final IdempotencyService idempotencyService;

    /**
     * Collapses a rare concurrent-first-checkout race (two requests hitting {@link
     * #ensureRazorpayPlanId} before Pro's {@code razorpayPlanId} is persisted) into a single
     * Razorpay Plan creation. Not a correctness guard — a duplicate Plan on Razorpay's side would
     * be harmless-but-wasteful, not a money-path bug — just cheap to avoid.
     */
    private final Object razorpayPlanLock = new Object();

    /**
     * [BL-3 fix, BrandF.md §99] {@link IdempotencyService#runExclusive} scope for {@link
     * #initiateCheckout} — see that method's own javadoc for the full defect/fix writeup. A
     * dedicated scope (distinct from every other {@code IdempotencyService} caller in the codebase)
     * so a colliding raw key can never shadow an unrelated caller — same discipline {@code
     * RazorpayWebhookController} already documents for its own scope constant.
     */
    private static final String CHECKOUT_IDEMPOTENCY_SCOPE = "billing.checkout.pro";

    /**
     * Constant on purpose — {@link IdempotencyService}'s composite reservation key already folds
     * {@code workspaceId} + {@link #CHECKOUT_IDEMPOTENCY_SCOPE} in, so this raw key only needs to
     * distinguish "a Pro checkout attempt" from any other reservation type; it does not need to be
     * unique per-request (a per-request value, e.g. a client-supplied token, would defeat the
     * cross-tab/cross-request mutual exclusion this exists for).
     */
    private static final String CHECKOUT_IDEMPOTENCY_KEY = "PRO_CHECKOUT";

    public SubscriptionService(
            SubscriptionRepository subscriptionRepository,
            PlanRepository planRepository,
            PlanService planService,
            RazorpayClient razorpayClient,
            AICreditService aiCreditService,
            IdempotencyService idempotencyService) {
        this.subscriptionRepository = subscriptionRepository;
        this.planRepository = planRepository;
        this.planService = planService;
        this.razorpayClient = razorpayClient;
        this.aiCreditService = aiCreditService;
        this.idempotencyService = idempotencyService;
    }

    public Optional<Subscription> getByWorkspaceId(String workspaceId) {
        return subscriptionRepository.findByWorkspaceId(workspaceId);
    }

    /**
     * The workspace's plan if an ACTIVE subscription exists, else the Free plan. Never returns
     * null and never throws for a workspace with no subscription row — Free is the honest default.
     * A PAST_DUE/HALTED/CANCELLED subscription also falls back to Free: only ACTIVE grants the
     * upgraded plan's fee/limit benefits (dunning soft-lock semantics, §1.7). A subscription
     * pointing at a plan that has since been deactivated ({@code Plan.active == false}) also falls
     * back to Free — {@code Plan.active} is a kill-switch and must not be bypassable via a stale
     * subscription row.
     */
    @Transactional(readOnly = true)
    public Plan getActivePlanForWorkspace(String workspaceId) {
        return getByWorkspaceId(workspaceId)
                .filter(sub -> sub.getStatus() == SubscriptionStatus.ACTIVE)
                .flatMap(sub -> planRepository.findById(sub.getPlanId()))
                .filter(Plan::isActive)
                .orElseGet(planService::getFreePlan);
    }

    /**
     * Lazily creates a Free-tier {@link Subscription} row for a workspace that doesn't have one
     * yet (e.g. first visit to the billing settings page). Idempotent — returns the existing row
     * if one already exists, regardless of its plan/status.
     */
    @Transactional
    public Subscription getOrCreateFreeSubscription(String workspaceId) {
        return getByWorkspaceId(workspaceId).orElseGet(() -> createFreeSubscription(workspaceId));
    }

    private Subscription createFreeSubscription(String workspaceId) {
        Plan freePlan = planService.getFreePlan();
        Instant periodStart = currentBillingCycleStart();
        Instant periodEnd = nextBillingCycleStart(periodStart);
        Subscription subscription =
                Subscription.builder()
                        .id(Ulids.newUlid())
                        .workspaceId(workspaceId)
                        .planId(freePlan.getId())
                        .status(SubscriptionStatus.ACTIVE)
                        .currentPeriodStart(periodStart)
                        .currentPeriodEnd(periodEnd)
                        .cancelAtPeriodEnd(false)
                        .build();
        return subscriptionRepository.save(subscription);
    }

    /** Start of the current UTC calendar month, at midnight — the Free-tier billing cycle anchor. */
    static Instant currentBillingCycleStart() {
        return LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    static Instant nextBillingCycleStart(Instant periodStart) {
        return periodStart.atZone(ZoneOffset.UTC).toLocalDate().plusMonths(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /**
     * Starts Pro-tier hosted checkout: resolves the Pro plan, lazily ensures it has a
     * Razorpay-side {@code razorpayPlanId} (see {@link #ensureRazorpayPlanId}), creates a
     * Razorpay Subscription with {@code notes.workspaceId} so the eventual webhook can correlate
     * back to this workspace, and returns the hosted checkout URL. Does NOT write a local {@link
     * Subscription} row — see class javadoc.
     *
     * <p><b>BL-3 fix (BrandF.md §99):</b> because this method deliberately writes no local row
     * before calling Razorpay (see class javadoc), the {@code ALREADY_SUBSCRIBED} guard just above
     * the Razorpay call has nothing to catch a concurrent double-submit — on a workspace's FIRST
     * upgrade there is no row (or only a Free row), so two requests racing this method (two
     * browser tabs, a replayed request, or a direct curl) both sail past that guard and would both
     * reach {@link RazorpayClient#createSubscription}, producing two distinct real {@code sub_*}
     * subscriptions billing the same workspace. The webhook upsert ({@link
     * #applySubscriptionWebhookUpdate}, {@code findByRazorpaySubscriptionId(...).or(() ->
     * findByWorkspaceId(...))}) then resolves both events into ONE local row — the second webhook's
     * {@link Subscription#linkRazorpaySubscription} silently overwrites the first's link, leaving
     * the first Razorpay subscription active, charging, and permanently un-cancellable through the
     * product (its id is gone from the row the moment the second webhook lands).
     *
     * <p>The Razorpay-calling section below is now wrapped in {@link
     * IdempotencyService#runExclusive} (scope {@link #CHECKOUT_IDEMPOTENCY_SCOPE}), which reserves
     * a per-workspace row in its own transaction BEFORE the outbound Razorpay call — the DB's
     * {@code UNIQUE(idempotency_key)} constraint (V15), not application logic, is what arbitrates a
     * genuine concurrent double-submit: exactly one racing caller's reservation insert succeeds and
     * proceeds to call Razorpay; every other concurrent caller sees the row already present and is
     * rejected with {@code AlreadyInProgressException}, translated below to a clean 409 {@code
     * CHECKOUT_IN_PROGRESS} — never silently retried, never allowed to call Razorpay a second time.
     *
     * <p><b>Why {@code runExclusive} and not the more familiar {@link IdempotencyService#executeOnce}
     * with a static per-workspace key:</b> {@code executeOnce} marks a key COMPLETED forever on
     * success, which is exactly right for a dedupe-forever ledger (webhook deliveries, tool calls)
     * but wrong here — it would permanently lock a workspace out of ever calling {@code
     * initiateCheckout} again after its first successful call, even months later after a
     * legitimate cancel + re-subscribe (a normal flow this class's own webhook-upsert javadoc
     * already documents: "may be from a prior Razorpay subscription if the workspace previously
     * cancelled and is now re-subscribing"). {@code runExclusive} instead releases (deletes) the
     * reservation the instant this call finishes — success or failure — so it only ever blocks a
     * TRULY CONCURRENT duplicate, never a later legitimate retry or resubscription. If the Razorpay
     * call itself throws (network error, timeout, Razorpay-side failure), the reservation is left
     * {@code FAILED} (not deleted) but is atomically reclaimable by the very next call for this
     * workspace — see {@code runExclusive}'s javadoc — so a genuinely failed attempt is always
     * retryable, and even a caller that crashes between reserving and releasing is recovered by
     * {@code IdempotencyReservationReaperJob}'s generic stale-{@code IN_PROGRESS} sweep. The
     * pre-existing {@code ALREADY_SUBSCRIBED} check above is untouched by any of this — it still
     * runs, unconditionally, before the lock is even attempted.
     */
    public String initiateCheckout(String workspaceId, PlanCode planCode) {
        if (planCode != PlanCode.PRO) {
            throw new ApiException(
                    "FREE_PLAN_NO_CHECKOUT",
                    "Free is always available and has no checkout — only PRO can be purchased",
                    HttpStatus.BAD_REQUEST);
        }

        // [SEC: Kabir red-team HIGH-1, wiki/errors/subscription-phase2-kabir-redteam.md] Refuse to
        // start a REAL Razorpay payment we could never confirm. isConfigured() (keyId/keySecret)
        // and the webhook secret are provisioned independently in Razorpay's dashboard and are
        // commonly set via separate env vars/secret-manager entries — a rotation, copy-paste of
        // the wrong secret, or an incomplete migration is enough to leave keyId/keySecret live
        // while webhookSecret is missing/wrong. In that state, RazorpayClient.createSubscription
        // below would take a genuine payment on Razorpay's hosted checkout, but
        // WebhookSignatureVerifier fails closed on every subsequent subscription.activated/charged
        // webhook forever (a paid PRO row is only ever written from a verified webhook — see
        // class javadoc's BL-5 correction), silently stranding a paying customer with no Pro access.
        // Checked per-request (not just at boot) so this still protects even if
        // SecretsStartupValidator's env-gated check was bypassed by a misconfigured influora.env.
        if (razorpayClient.isConfigured() && !razorpayClient.isFullyConfigured()) {
            throw new ApiException(
                    "RAZORPAY_MISCONFIGURED",
                    "Payment is not fully configured (webhook secret missing) — refusing to start"
                            + " a real payment that could not be confirmed. Contact an administrator.",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }

        Plan proPlan = planService.getProPlan();
        if (!proPlan.isActive()) {
            throw new ApiException(
                    "PLAN_NOT_AVAILABLE", "Pro plan is currently disabled", HttpStatus.CONFLICT);
        }

        Subscription existing = getByWorkspaceId(workspaceId).orElse(null);
        if (existing != null
                && existing.getStatus() == SubscriptionStatus.ACTIVE
                && proPlan.getId().equals(existing.getPlanId())) {
            throw new ApiException(
                    "ALREADY_SUBSCRIBED", "Workspace already has an active Pro subscription", HttpStatus.CONFLICT);
        }

        try {
            return idempotencyService.runExclusive(
                    CHECKOUT_IDEMPOTENCY_KEY,
                    workspaceId,
                    CHECKOUT_IDEMPOTENCY_SCOPE,
                    () -> {
                        String razorpayPlanId = ensureRazorpayPlanId(proPlan);

                        JSONObject notes = new JSONObject();
                        notes.put("workspaceId", workspaceId);

                        RazorpayClient.SubscriptionResult result =
                                razorpayClient.createSubscription(razorpayPlanId, DEFAULT_TOTAL_COUNT, notes);
                        return result.shortUrl();
                    });
        } catch (IdempotencyService.AlreadyInProgressException concurrentCheckout) {
            // [BL-3 fix] A second racing request (or a stuck-mid-flight caller within the reaper's
            // grace period) — never silently retried, never allowed to reach Razorpay a second time.
            throw new ApiException(
                    "CHECKOUT_IN_PROGRESS",
                    "A checkout is already being processed for this workspace — please wait a"
                            + " moment, then refresh your billing page before trying again.",
                    HttpStatus.CONFLICT);
        }
    }

    /**
     * Cancels the workspace's paid subscription at the end of the current billing period (never
     * a hard mid-cycle cancel — plan §1.1). Only meaningful for a workspace with a Razorpay-linked
     * subscription; a Free-tier row (no {@code razorpaySubscriptionId}) has nothing to cancel.
     */
    @Transactional
    public void cancel(String workspaceId) {
        Subscription subscription =
                getByWorkspaceId(workspaceId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                "SUBSCRIPTION_NOT_FOUND",
                                                "No subscription found for this workspace",
                                                HttpStatus.NOT_FOUND));

        if (subscription.getRazorpaySubscriptionId() == null) {
            throw new ApiException(
                    "NO_PAID_SUBSCRIPTION",
                    "Workspace has no paid subscription to cancel",
                    HttpStatus.BAD_REQUEST);
        }
        if (subscription.getStatus() == SubscriptionStatus.CANCELLED) {
            throw new ApiException(
                    "ALREADY_CANCELLED", "Subscription is already cancelled", HttpStatus.CONFLICT);
        }

        razorpayClient.cancelSubscription(subscription.getRazorpaySubscriptionId(), true);

        // Status is intentionally left ACTIVE here — the subscription keeps working until the
        // current paid period actually elapses. The renewal/dunning job (Phase 4, Task 24) is
        // what flips status to CANCELLED once currentPeriodEnd passes with cancelAtPeriodEnd set.
        subscription.setCancelAtPeriodEnd(true);
        subscriptionRepository.save(subscription);
    }

    /**
     * Effectively-indefinite horizon for a comp/override grant with no admin-supplied {@code
     * expiresAt} — same "large-but-finite is the documented convention for indefinite" reasoning as
     * {@link #DEFAULT_TOTAL_COUNT} (120 monthly cycles ~= 10 years for a real Razorpay
     * subscription); 10 years here too, for consistency.
     */
    private static final Duration ADMIN_GRANT_DEFAULT_HORIZON = Duration.ofDays(3650);

    /**
     * Task 25 ({@code AdminBillingController}) — the single shared mechanism behind both {@code
     * POST /admin/billing/comp} and {@code POST /admin/billing/override}. Deliberately the ONLY
     * place that writes an admin-granted (non-Razorpay-backed) plan assignment, so comp and
     * override can never drift into two different "give this workspace a plan" code paths (the
     * exact anti-pattern MP-1's origin incident was about — two independent paths flipping the
     * same entity state).
     *
     * <p>Reuses this class's own {@link #reconcileAiCreditAllotment(String)} for AI credits — the
     * same call every real Razorpay webhook activation goes through. Seats
     * ({@code WorkspaceMemberService}) and the brand fee ({@code BrandCampaignFeeService}) need NO
     * separate reconciliation call: both derive LIVE from {@link #getActivePlanForWorkspace(String)}
     * on every read (see that method's javadoc), so the moment this method points the {@link
     * Subscription} row at {@code targetPlan} with {@code ACTIVE} status, seat limits and fee bps
     * are already correct on the very next read — exactly the same guarantee a real webhook
     * activation gets, satisfying the "do NOT write a parallel give-them-Pro-benefits code path"
     * constraint.
     *
     * @throws ApiException 409 {@code ALREADY_PAID_SUBSCRIBER} if the workspace already has a REAL
     *     Razorpay-backed subscription ({@code razorpaySubscriptionId != null}) — overwriting that
     *     row here would silently produce an inconsistent {@code comp=true} + {@code
     *     razorpaySubscriptionId != null} state and could mask/clobber a genuine paying customer's
     *     billing record. An admin must cancel the real subscription first (or wait for it to
     *     lapse) before granting a comp/override on top of it.
     */
    @Transactional
    public Subscription grantAdminPlan(
            String workspaceId, Plan targetPlan, String grantedByAdminId, String reason, Instant expiresAt) {
        Subscription existing = getByWorkspaceId(workspaceId).orElse(null);

        if (existing != null && existing.getRazorpaySubscriptionId() != null) {
            throw new ApiException(
                    "ALREADY_PAID_SUBSCRIBER",
                    "Workspace already has a real Razorpay-backed subscription — cancel it first"
                            + " before granting an admin comp/override",
                    HttpStatus.CONFLICT);
        }

        Instant periodStart = Instant.now();
        Instant periodEnd = expiresAt != null ? expiresAt : periodStart.plus(ADMIN_GRANT_DEFAULT_HORIZON);

        Subscription subscription;
        if (existing == null) {
            subscription =
                    Subscription.builder()
                            .id(Ulids.newUlid())
                            .workspaceId(workspaceId)
                            .planId(targetPlan.getId())
                            .status(SubscriptionStatus.ACTIVE)
                            .currentPeriodStart(periodStart)
                            .currentPeriodEnd(periodEnd)
                            .cancelAtPeriodEnd(false)
                            .comp(true)
                            .compReason(reason)
                            .compGrantedBy(grantedByAdminId)
                            .compExpiresAt(expiresAt)
                            .build();
        } else {
            subscription = existing;
            subscription.changePlan(targetPlan.getId());
            subscription.setStatus(SubscriptionStatus.ACTIVE);
            subscription.renewPeriod(periodStart, periodEnd);
            subscription.markComp(reason, grantedByAdminId, expiresAt);
        }
        subscriptionRepository.save(subscription);

        // Directly allot for targetPlan rather than reconcileAiCreditAllotment's re-derive-via
        // -getActivePlanForWorkspace + swallow-failures pattern: that pattern exists for the
        // webhook path (see #applySubscriptionWebhookUpdate javadoc) where a best-effort,
        // non-blocking re-sync after an already-committed write is the right call. Here we just
        // wrote this exact subscription row to targetPlan/ACTIVE ourselves in this same
        // transaction — there is no ambiguity to re-derive, and an admin-initiated comp/override
        // grant should surface (not silently swallow) a credit-allotment failure so the admin
        // knows the grant didn't fully take effect.
        aiCreditService.applyPlanAllotment(workspaceId, targetPlan.getAiMonthlyAllotment());
        return subscription;
    }

    /**
     * Lazily creates Pro's Razorpay Plan on first checkout rather than at deploy/seed time — V55
     * seeds Pro with {@code razorpay_plan_id=NULL} as a placeholder specifically so this method
     * backfills it. Idempotent: returns the existing id immediately once one is persisted.
     */
    private String ensureRazorpayPlanId(Plan plan) {
        if (plan.getRazorpayPlanId() != null) {
            return plan.getRazorpayPlanId();
        }
        synchronized (razorpayPlanLock) {
            Plan fresh = planRepository.findById(plan.getId()).orElse(plan);
            if (fresh.getRazorpayPlanId() != null) {
                return fresh.getRazorpayPlanId();
            }
            // Plan.priceInr is already stored in INR paise (V54 column comment) — do not
            // multiply by 100 again here.
            String razorpayPlanId =
                    razorpayClient.createPlan(
                            fresh.getName(), fresh.getPriceInr(), toRazorpayPeriod(fresh.getBillingCycle()));
            fresh.setRazorpayPlanId(razorpayPlanId);
            planRepository.save(fresh);
            return razorpayPlanId;
        }
    }

    private static String toRazorpayPeriod(BillingCycle billingCycle) {
        return billingCycle.name().toLowerCase(Locale.ROOT);
    }

    /**
     * Upserts the local {@link Subscription} row from a verified Razorpay subscription webhook
     * (Task 20 — {@code RazorpayWebhookController}). This is the ONLY place subscription state is
     * written from Razorpay's side of the world, per TECH-STACK.md rule #4.
     *
     * <p>Because {@code subscriptions.workspace_id} is UNIQUE (1:1 with workspaces), this upserts
     * by workspace rather than blindly inserting: the row may already exist as the lazily-created
     * Free-tier row ({@link #getOrCreateFreeSubscription}), or from a prior Razorpay subscription
     * if the workspace previously cancelled and is now re-subscribing. {@code periodStart}/{@code
     * periodEnd} are {@code null} for status-only events ({@code pending}/{@code halted}/{@code
     * cancelled}) — the existing period is left untouched for those.
     *
     * @param razorpayPlanId the Razorpay plan id from the webhook payload; if it doesn't resolve
     *     to a known {@link Plan} row (e.g. mock/test-mode ids), falls back to the Pro plan, since
     *     Free never has a Razorpay-side subscription in this system.
     * @param webhookEventAt the webhook envelope's own {@code created_at} (Kabir red-team
     *     MEDIUM-1) — used to detect and skip a delivery that is older than the last one actually
     *     applied to this row, since Razorpay only guarantees delivery ordering within retries of
     *     the same event, never across distinct events. May be {@code null} if the envelope
     *     carried no parseable {@code created_at}, in which case no staleness check is possible
     *     for this delivery and it is applied as-is (matches the pre-existing behavior).
     *     <p><b>AI-credit reconciliation (Task 24, Priya's Phase 3 sign-off finding):</b> seats
     *     ({@code WorkspaceMemberService}) and the brand fee ({@code BrandCampaignFeeService})
     *     both derive LIVE from {@link #getActivePlanForWorkspace(String)} on every read, so
     *     they can never go stale. {@code BrandAiCredit.monthlyAllotment} does NOT — it's a
     *     snapshot written once at signup/first-launch/the monthly {@code AICreditResetJob}
     *     cron. Without this fix, a brand who upgrades Free-&gt;Pro mid-cycle would not see
     *     their 400 AI credits until the next monthly reset (up to a month later), despite
     *     already paying for Pro; a brand who downgrades/cancels would symmetrically keep 400
     *     credits until the same cron next ran. Every successful status/plan write below now
     *     immediately re-syncs {@code monthlyAllotment} to whatever plan {@link
     *     #getActivePlanForWorkspace(String)} resolves to right after the write — for EVERY
     *     status this method can set (not only activate/cancel/halt), since {@code
     *     getActivePlanForWorkspace} already treats PAST_DUE as an immediate Free-tier
     *     fallback (dunning soft-lock, see that method's javadoc) and the AI-credit allotment
     *     must not lag behind that same live-derived answer. Subscriptions only ever exist for
     *     BRAND workspaces ({@code BillingController}/{@code initiateCheckout} are both gated
     *     via {@code BrandContextService.requireBrandWorkspace}), so calling {@link
     *     AICreditService#applyPlanAllotment} here can never spuriously create a credit row for
     *     an AGENCY workspace the way an unfiltered sweep could (the bug {@code
     *     AICreditResetJob}'s {@code WorkspaceType.BRAND} scoping already fixed for the monthly
     *     cron path). The monthly cron still runs for every brand as the steady-state reset;
     *     this closes the "up to a month of drift on every mid-cycle plan change" gap on top of
     *     it, not a replacement for it.
     */
    @Transactional
    public void applySubscriptionWebhookUpdate(
            String razorpaySubscriptionId,
            String workspaceId,
            String razorpayPlanId,
            SubscriptionStatus targetStatus,
            Instant periodStart,
            Instant periodEnd,
            Instant webhookEventAt) {
        if (razorpaySubscriptionId == null || razorpaySubscriptionId.isBlank()) {
            throw new ApiException(
                    "MISSING_SUBSCRIPTION_ID",
                    "Webhook payload missing payload.subscription.entity.id",
                    HttpStatus.BAD_REQUEST);
        }
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new ApiException(
                    "MISSING_WORKSPACE_ID",
                    "Webhook payload missing notes.workspaceId for subscription "
                            + razorpaySubscriptionId
                            + " — cannot correlate to a workspace",
                    HttpStatus.BAD_REQUEST);
        }

        Plan plan = resolvePlanForWebhook(razorpayPlanId);

        Subscription subscription =
                subscriptionRepository
                        .findByRazorpaySubscriptionId(razorpaySubscriptionId)
                        .or(() -> subscriptionRepository.findByWorkspaceId(workspaceId))
                        .orElse(null);

        if (subscription == null) {
            Instant start = periodStart != null ? periodStart : Instant.now();
            Instant end = periodEnd != null ? periodEnd : start;
            subscription =
                    Subscription.builder()
                            .id(Ulids.newUlid())
                            .workspaceId(workspaceId)
                            .planId(plan.getId())
                            .status(targetStatus)
                            .razorpaySubscriptionId(razorpaySubscriptionId)
                            .currentPeriodStart(start)
                            .currentPeriodEnd(end)
                            .cancelAtPeriodEnd(false)
                            .lastWebhookEventAt(webhookEventAt)
                            .build();
            subscriptionRepository.save(subscription);
            reconcileAiCreditAllotment(workspaceId);
            return;
        }

        // [SEC: Kabir red-team MEDIUM-1] Reject/no-op an out-of-order delivery rather than
        // blindly applying it. Razorpay's delivery ordering guarantee only covers retries of the
        // SAME event, not distinct events for the same subscription — e.g. a delayed retry of an
        // earlier subscription.charged (queued during an outage) can arrive after a genuinely
        // newer subscription.halted was already applied. Applying the stale one would flip status
        // back and/or reset the billing period to older values.
        if (webhookEventAt != null
                && subscription.getLastWebhookEventAt() != null
                && webhookEventAt.isBefore(subscription.getLastWebhookEventAt())) {
            log.warn(
                    "Skipping stale subscription webhook delivery: razorpaySubscriptionId={},"
                            + " workspaceId={}, targetStatus={}, incomingEventAt={},"
                            + " lastAppliedEventAt={} — a newer event was already applied to this row",
                    razorpaySubscriptionId, workspaceId, targetStatus, webhookEventAt,
                    subscription.getLastWebhookEventAt());
            return;
        }

        // Captured BEFORE the writes below overwrite them — the ACTIVE branch needs to know what
        // this row looked like BEFORE this delivery was applied to tell a real (re)activation
        // apart from a routine renewal event on a subscription that is already ACTIVE.
        SubscriptionStatus previousStatus = subscription.getStatus();
        String previousRazorpaySubscriptionId = subscription.getRazorpaySubscriptionId();

        subscription.linkRazorpaySubscription(razorpaySubscriptionId);
        if (!plan.getId().equals(subscription.getPlanId())) {
            subscription.changePlan(plan.getId());
        }
        subscription.setStatus(targetStatus);
        if (targetStatus == SubscriptionStatus.ACTIVE) {
            // [Re-subscribe latch fix] Clear a stale cancel-at-period-end flag on (re)activation.
            // The flag is only ever SET by SubscriptionService#cancel and, before this fix, was
            // only ever cleared on brand-new-row creation above — never on an existing row being
            // reactivated. Left uncleared, a workspace that cancels and later re-subscribes (this
            // UPDATE branch, via a subscription.activated webhook) keeps cancelAtPeriodEnd=true
            // forever even though it is now ACTIVE and paying again. Any SUBSEQUENT missed/delayed
            // webhook then leaves this ACTIVE row with a lapsed period AND the stale flag still
            // set — exactly the input SubscriptionRenewalResetJob's cancelAtPeriodEnd partition
            // (see that job's class javadoc) routes to finalizeLapsedCancellation, wrongly
            // revoking a currently-paying customer's Pro entitlement while Razorpay keeps charging
            // them. Clearing it here, alongside every other (re)activation write, keeps the flag
            // meaning exactly one thing: "the customer's MOST RECENT cancel is still pending."
            //
            // ...which is precisely why the clear is NOT unconditional — only a real
            // (re)activation may clear it. This method handles subscription.charged as well as
            // subscription.activated and maps both to ACTIVE, but a charged delivery for the
            // subscription the customer ALREADY cancelled via #cancel is not a reactivation: a
            // cancel-at-period-end row deliberately stays ACTIVE and keeps billing until its
            // period elapses (see #cancel), so the final cycle's renewal charge — or a charged
            // retry queued during an outage — legitimately arrives on an ACTIVE, flag-set row,
            // ahead of the terminal subscription.cancelled. Clearing on THAT delivery silently
            // un-cancels a customer who did cancel: the flag is what SubscriptionRenewalResetJob
            // partitions on, so the row gets renewed instead of finalized, GET /billing/plan
            // reports cancelAtPeriodEnd=false, and the only way back is to cancel a second time.
            //
            // The event type is not a parameter here, so the discriminator is the row's own
            // BEFORE-state: a genuine (re)activation is a TRANSITION into ACTIVE (from
            // PAST_DUE/HALTED/CANCELLED — every other status this enum has), or an event whose
            // sub_* id DIFFERS from the one this row tracked — which is what a re-subscribe
            // produces, since it
            // must go back through #initiateCheckout and create a brand-new Razorpay
            // subscription. A delivery that is neither (already ACTIVE, same subscription) can
            // only be a renewal event on the very subscription whose cancel is still pending,
            // and must leave the flag alone.
            boolean reactivation =
                    previousStatus != SubscriptionStatus.ACTIVE
                            || !razorpaySubscriptionId.equals(previousRazorpaySubscriptionId);
            if (reactivation) {
                subscription.setCancelAtPeriodEnd(false);
            } else if (subscription.isCancelAtPeriodEnd()) {
                log.info(
                        "Preserving pending cancel-at-period-end on an ACTIVE webhook for an"
                                + " already-ACTIVE row: razorpaySubscriptionId={}, workspaceId={}"
                                + " — the customer's cancel is still pending; only"
                                + " subscription.cancelled or the lapsed-period job may retire it",
                        razorpaySubscriptionId, workspaceId);
            }
        }
        if (periodStart != null && periodEnd != null) {
            subscription.renewPeriod(periodStart, periodEnd);
        }
        subscription.markWebhookApplied(webhookEventAt);

        // [SEC: Kabir red-team MEDIUM-2] saveAndFlush (not save) so the @Version WHERE-clause
        // check runs synchronously here, inside this method's own transaction, rather than
        // deferring to commit time. Deliberately NOT caught here: an
        // ObjectOptimisticLockingFailureException propagates as an uncaught RuntimeException up
        // through IdempotencyService#executeOnce (RazorpayWebhookController), which marks the
        // idempotency key FAILED and rethrows — the webhook endpoint returns a 5xx, Razorpay
        // retries the delivery, and the retry re-reads the now-merged row instead of silently
        // losing this update. Same precedent as DisputeService#resolveDispute, except that
        // caller translates the exception to a synchronous 409 for a client-invoked endpoint;
        // this is a webhook-invoked, retry-friendly path, so letting it propagate is correct here.
        subscriptionRepository.saveAndFlush(subscription);
        reconcileAiCreditAllotment(workspaceId);
    }

    /**
     * Re-syncs {@code BrandAiCredit.monthlyAllotment} to the workspace's CURRENT live plan
     * immediately after a subscription status/plan write — see {@link
     * #applySubscriptionWebhookUpdate} javadoc for why this must happen on every transition, not
     * only activate/cancel/halt. Deliberately swallows its own failures: a credit-reconciliation
     * problem must never roll back (or fail-retry) an already-correctly-applied subscription
     * status write, matching the fail-loud-but-non-blocking discipline used elsewhere for
     * best-effort side effects in this codebase (e.g. {@code ContractService
     * #generateAndDeliverContractPdf}). A failure here is a real bug (the exact drift this method
     * exists to close) and must not go unnoticed, so it is logged at ERROR, not swallowed
     * silently.
     *
     * <p><b>Public, not private</b> [SEC: Kabir red-team Phase 4a MEDIUM-1] — also called
     * explicitly by {@code SubscriptionDunningJob#haltOne} on the PAST_DUE->HALTED transition.
     * That transition is currently a same-fallback no-op today ({@link
     * #getActivePlanForWorkspace} already maps both PAST_DUE and HALTED to Free identically, and
     * reconciliation already fired when the subscription entered PAST_DUE), but relying on that
     * implicit coupling of two enum values is fragile: if a future feature ever gives PAST_DUE a
     * different effective plan than HALTED (e.g. grace-period Pro access), the dunning job would
     * silently stop reconciling credits on HALTED unless it calls this explicitly. Calling it here
     * removes that implicit coupling — the HALTED transition reconciles credits by design, not by
     * accident.
     */
    @Transactional
    public void reconcileAiCreditAllotment(String workspaceId) {
        try {
            Plan currentPlan = getActivePlanForWorkspace(workspaceId);
            aiCreditService.applyPlanAllotment(workspaceId, currentPlan.getAiMonthlyAllotment());
        } catch (Exception e) {
            log.error(
                    "AI-credit allotment reconciliation FAILED after a subscription webhook update"
                            + " for workspace {} — monthlyAllotment may be stale until the next"
                            + " monthly AICreditResetJob run or the next subscription webhook",
                    workspaceId,
                    e);
        }
    }

    /**
     * Transactional core of {@code SubscriptionRenewalResetJob}'s safety-net renewal — advances
     * the billing period and re-syncs AI-credit allotment/reset in a SINGLE transaction boundary.
     *
     * <p>[SEC: Kabir red-team Phase 4a MEDIUM-2] Previously the job performed the period-advance
     * {@code save()}, the plan-allotment sync, and the credit-cycle reset as three separately
     * auto-committing calls. If a credit-sync step threw AFTER the period-advance had already
     * committed, the period was left permanently advanced but that cycle's credit reset was
     * silently skipped with no automatic retry — the job's own trigger condition ({@code
     * currentPeriodEnd < now()}) would no longer match the row on the next run, so the drift would
     * persist until the next real webhook or billing cycle happened to catch it, not "the very
     * next day's run" as one might assume. Wrapping the whole sequence in one transaction here
     * means a partial failure rolls back the period-advance too, so the SAME subscription is
     * retried cleanly by the job's normal {@code findByStatus(ACTIVE)} + stale-period query on its
     * very next run instead of being left half-updated.
     *
     * <p>Deliberately called from the job as a single external method (not left as inline steps
     * in the job) so Spring's transactional proxy actually intercepts the call — a
     * {@code @Transactional} annotation on a private method the job called on itself would be a
     * no-op under Spring AOP's self-invocation limitation.
     *
     * <p>The subscription's own {@code @Version} optimistic lock still protects against a
     * concurrent webhook race exactly as before (see {@code SubscriptionRenewalResetJob} javadoc
     * FOCUS AREA 2b/Kabir's trace) — this change only removes the partial-commit window between
     * this job's own sequential steps, it does not alter cross-actor concurrency behavior.
     */
    @Transactional
    public void applyRenewalSafetyNet(Subscription subscription, Instant newStart, Instant newEnd) {
        subscription.renewPeriod(newStart, newEnd);
        subscriptionRepository.save(subscription);

        String workspaceId = subscription.getWorkspaceId();
        Plan activePlan = getActivePlanForWorkspace(workspaceId);
        if (activePlan != null && activePlan.getCode() == PlanCode.PRO) {
            aiCreditService.applyPlanAllotment(workspaceId, activePlan.getAiMonthlyAllotment());
        }
        aiCreditService.resetForNewCycle(workspaceId);
    }

    /**
     * BL-2 fix (BrandF.md §98): terminal transition for a subscription the customer already
     * cancelled ({@link Subscription#isCancelAtPeriodEnd()} {@code == true}) whose paid period has
     * now lapsed. Called only from {@link com.influora.job.SubscriptionRenewalResetJob}, for rows
     * its own stale-period query would otherwise hand to {@link #applyRenewalSafetyNet} — which
     * would silently RE-RENEW a subscription the customer cancelled (advancing the period and
     * re-allotting Pro AI credits), undoing {@link #cancel(String)} every single day forever. That
     * job partitions its stale-period batch on {@code cancelAtPeriodEnd} and routes rows with the
     * flag set here instead of into {@code applyRenewalSafetyNet}.
     *
     * <p>Prior to this fix, {@code SubscriptionDunningJob} only ever queried {@code PAST_DUE} rows
     * (a cancelled-at-period-end row is still {@code ACTIVE}, so it was never visible there) and
     * {@code RazorpayWebhookController} had no {@code subscription.cancelled}/{@code
     * subscription.completed} case (that terminal Razorpay event was silently discarded via {@code
     * default -> {}}) — so nothing in production ever wrote {@link SubscriptionStatus#CANCELLED}
     * for the scheduled-cancellation path. This method, together with the new webhook cases, are
     * the two writers that now do.
     *
     * <p>{@code saveAndFlush} (not {@code save}), matching {@link #applySubscriptionWebhookUpdate}'s
     * MEDIUM-2 pattern: the row's {@code @Version} optimistic lock is checked synchronously inside
     * this method's own transaction, and a lock failure is deliberately left uncaught so it
     * propagates up to the job's own per-subscription {@code catch (Exception)} — the row's status
     * write (and this method's own {@code reconcileAiCreditAllotment} call) both roll back together,
     * and because the row is still {@code ACTIVE} with {@code cancelAtPeriodEnd == true} and an
     * elapsed period, it is picked up again cleanly on the job's very next run instead of being
     * silently skipped for a full cycle.
     */
    @Transactional
    public void finalizeLapsedCancellation(Subscription subscription) {
        subscription.setStatus(SubscriptionStatus.CANCELLED);
        subscriptionRepository.saveAndFlush(subscription);
        // getActivePlanForWorkspace already falls back to Free the instant status != ACTIVE, so
        // this brings BrandAiCredit.monthlyAllotment back down to Free's allotment immediately —
        // same discipline as reconcileAiCreditAllotment's other callers (webhook activate/cancel/
        // halt, SubscriptionDunningJob#haltOne) rather than leaving it to drift until the next
        // monthly AICreditResetJob.
        reconcileAiCreditAllotment(subscription.getWorkspaceId());
    }

    private Plan resolvePlanForWebhook(String razorpayPlanId) {
        if (razorpayPlanId != null) {
            Optional<Plan> found = planRepository.findByRazorpayPlanId(razorpayPlanId);
            if (found.isPresent()) {
                return found.get();
            }
        }
        // Subscriptions are Pro-only in this system — Free never has a Razorpay-side subscription
        // — so an unresolvable plan id (mock/test-mode webhook, or a plan created outside this
        // service) still safely defaults to Pro rather than failing the webhook.
        return planService.getProPlan();
    }
}
