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
 * request) get Pro entitlements for free. The local row is created/updated only from a verified
 * webhook ({@link #applySubscriptionWebhookUpdate}), matching TECH-STACK.md rule #4 ("money/state
 * changes are server-derived from the payment processor's callback, never a client request").
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

    /**
     * Collapses a rare concurrent-first-checkout race (two requests hitting {@link
     * #ensureRazorpayPlanId} before Pro's {@code razorpayPlanId} is persisted) into a single
     * Razorpay Plan creation. Not a correctness guard — a duplicate Plan on Razorpay's side would
     * be harmless-but-wasteful, not a money-path bug — just cheap to avoid.
     */
    private final Object razorpayPlanLock = new Object();

    public SubscriptionService(
            SubscriptionRepository subscriptionRepository,
            PlanRepository planRepository,
            PlanService planService,
            RazorpayClient razorpayClient,
            AICreditService aiCreditService) {
        this.subscriptionRepository = subscriptionRepository;
        this.planRepository = planRepository;
        this.planService = planService;
        this.razorpayClient = razorpayClient;
        this.aiCreditService = aiCreditService;
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
        // webhook forever (the local Subscription row is only ever written from a verified
        // webhook — see class javadoc), silently stranding a paying customer with no Pro access.
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

        String razorpayPlanId = ensureRazorpayPlanId(proPlan);

        JSONObject notes = new JSONObject();
        notes.put("workspaceId", workspaceId);

        RazorpayClient.SubscriptionResult result =
                razorpayClient.createSubscription(razorpayPlanId, DEFAULT_TOTAL_COUNT, notes);
        return result.shortUrl();
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

        subscription.linkRazorpaySubscription(razorpaySubscriptionId);
        if (!plan.getId().equals(subscription.getPlanId())) {
            subscription.changePlan(plan.getId());
        }
        subscription.setStatus(targetStatus);
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
