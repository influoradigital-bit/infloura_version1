package com.influora.job;

import com.influora.domain.entity.Subscription;
import com.influora.domain.enums.SubscriptionStatus;
import com.influora.repository.SubscriptionRepository;
import com.influora.service.AuditLogService;
import com.influora.service.BrandContextService;
import com.influora.service.BrandContextService.BillingRecipient;
import com.influora.service.billing.SubscriptionService;
import com.influora.service.notification.event.SubscriptionHaltedEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Dunning sweep — Task 24 subscription-billing Phase 4a. Money-lifecycle code: mandatory Kabir
 * gate after Kavya, same discipline as Phase 2/3a (per Arjun's routing note).
 *
 * <p>Daily, scans every {@code Subscription} row stuck in {@link SubscriptionStatus#PAST_DUE} and
 * transitions it to {@link SubscriptionStatus#HALTED} once it has sat past-due beyond {@link
 * #DUNNING_GRACE_PERIOD}. This is the LOCAL grace-period enforcement — Razorpay runs its own
 * retry/dunning cycle on its side and will eventually send a {@code subscription.halted} webhook
 * of its own ({@code RazorpayWebhookController}), but this job is the backstop for the case where
 * that webhook is missed/delayed: a workspace must not sit PAST_DUE (paying the Pro fee rate,
 * Pro-tier feature-gated) indefinitely just because Razorpay's own halt notification never
 * arrived.
 *
 * <p><b>Grace-period anchor:</b> {@link Subscription#getCurrentPeriodEnd()}, not {@code
 * lastWebhookEventAt}. {@code currentPeriodEnd} is left untouched by a {@code subscription.pending}
 * webhook ({@code RazorpayWebhookController#handleSubscriptionEvent}'s {@code updatePeriod=false}
 * for that event type — see the dispatch switch), so it still holds the boundary of the period
 * whose renewal charge failed, which is exactly "when this subscription became past-due" — the
 * correct anchor per {@code wiki/processes/subscription-billing-task-breakdown.md} Task 24's own
 * query condition (Note: {@code lastWebhookEventAt} would instead drift forward on every retried
 * {@code subscription.pending} delivery of the SAME underlying failure, understating how long the
 * workspace has actually been past-due).
 *
 * <p><b>Grace period length ({@link #DUNNING_GRACE_PERIOD}):</b> no SLA number is specified
 * anywhere in the plan docs (checked {@code SUBSCRIPTION-BILLING-PLAN.md} and the task
 * breakdown) — 7 days is a defensible, common default (comparable to Stripe/Recurly's typical
 * 3-14 day dunning windows) but is an engineering judgment call, not a confirmed product decision.
 * Flagged for Priya/product to override if a specific SLA exists or is decided later; kept as a
 * package-visible constant specifically so it's easy to find and change.
 *
 * <p><b>Audit trail — deliberately NOT {@code AdminAuditLogService}/{@code admin_audit_log}
 * despite Arjun's routing note suggesting it (and a possible {@code SUBSCRIPTION_HALTED} entry in
 * {@code AdminAuditLogService.ALLOWED_ACTIONS}).</b> Investigated before deviating:
 * {@code AdminAuditLogService#record} requires a real {@code AuthPrincipal} + {@code
 * HttpServletRequest} and (Rule 1 of that class) re-resolves {@code admin_id}/{@code admin_email}
 * from an actual {@code admin_users} row — neither exists for an autonomous scheduled job, and
 * {@code admin_audit_log.admin_id} carries a hard {@code FOREIGN KEY REFERENCES admin_users(id)}
 * (V34__admin_tables.sql) that a synthetic "SYSTEM" id would violate. That same migration's own
 * comment states the correct table for this: {@code audit_log} (V15, written by {@link
 * AuditLogService}) is explicitly "AI tool-call / money-movement audit trail: actor_type
 * MEERA_AI|HUMAN|SYSTEM|SERVICE" — built for exactly this kind of system-initiated event, and
 * already has a real precedent for an autonomous daily job doing so: {@code
 * StaleTokenCleanupJob#runCleanup} logs its own autonomous state changes via {@code
 * AuditLogService}, not {@code AdminAuditLogService}. This job follows that established pattern.
 * Flagged explicitly in the Task 24 handoff so Kavya/Kabir/Priya can override this call if
 * admin-console visibility (Task 25) specifically needs an {@code admin_audit_log} row instead —
 * that would require a real seeded system {@code AdminUser} row (a migration), which is out of
 * scope for this task.
 */
@Component
public class SubscriptionDunningJob {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionDunningJob.class);

    /** See class javadoc "Grace period length" note. */
    static final Duration DUNNING_GRACE_PERIOD = Duration.ofDays(7);

    private final SubscriptionRepository subscriptionRepository;
    private final AuditLogService auditLog;
    private final BrandContextService brandContext;
    private final ApplicationEventPublisher eventPublisher;
    private final SubscriptionService subscriptionService;
    private final TransactionTemplate transactionTemplate;

    private final AtomicBoolean running = new AtomicBoolean(false);

    public SubscriptionDunningJob(
            SubscriptionRepository subscriptionRepository,
            AuditLogService auditLog,
            BrandContextService brandContext,
            ApplicationEventPublisher eventPublisher,
            SubscriptionService subscriptionService,
            PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.subscriptionRepository = subscriptionRepository;
        this.auditLog = auditLog;
        this.brandContext = brandContext;
        this.eventPublisher = eventPublisher;
        this.subscriptionService = subscriptionService;
    }

    /** Daily at 3:00am UTC — ahead of {@code SubscriptionRenewalResetJob} (3:30am) and {@code StaleTokenCleanupJob} (4am). */
    @Scheduled(cron = "0 0 3 * * *", zone = "UTC")
    @SchedulerLock(name = "SubscriptionDunningJob", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void runDunning() {
        if (!running.compareAndSet(false, true)) {
            log.warn("SubscriptionDunningJob: previous run still in progress, skipping this trigger");
            return;
        }
        try {
            doRun();
        } catch (Exception e) {
            log.error("SubscriptionDunningJob: crashed mid-run", e);
            throw e;
        } finally {
            running.set(false);
        }
    }

    private void doRun() {
        List<Subscription> pastDue = subscriptionRepository.findByStatus(SubscriptionStatus.PAST_DUE);
        Instant graceThreshold = Instant.now().minus(DUNNING_GRACE_PERIOD);

        int halted = 0;
        int withinGrace = 0;
        int failed = 0;

        for (Subscription subscription : pastDue) {
            try {
                if (subscription.getCurrentPeriodEnd().isAfter(graceThreshold)) {
                    // Still within the grace period — must NOT transition yet.
                    withinGrace++;
                    continue;
                }
                haltOne(subscription);
                halted++;
            } catch (Exception e) {
                failed++;
                log.error(
                        "SubscriptionDunningJob: unexpected failure halting subscription {}",
                        subscription.getId(),
                        e);
            }
        }

        log.info(
                "SubscriptionDunningJob: completed — halted={}, withinGrace={}, failed={}, totalPastDue={}",
                halted, withinGrace, failed, pastDue.size());
    }

    private void haltOne(Subscription subscription) {
        SubscriptionStatus previousStatus = subscription.getStatus();
        subscription.setStatus(SubscriptionStatus.HALTED);
        subscriptionRepository.save(subscription);

        // [SEC: Kabir red-team Phase 4a MEDIUM-1] Explicit AI-credit reconciliation on the
        // PAST_DUE->HALTED transition — see SubscriptionService#reconcileAiCreditAllotment
        // javadoc for why this must not rely on the implicit (and currently coincidental)
        // PAST_DUE/HALTED-both-map-to-Free coupling in getActivePlanForWorkspace. Best-effort:
        // the method already swallows/logs its own failures internally, matching the same
        // fail-loud-but-non-blocking discipline as the halted-email dispatch below — a
        // reconciliation problem must never unwind the already-correctly-applied HALTED status.
        subscriptionService.reconcileAiCreditAllotment(subscription.getWorkspaceId());

        auditLog.recordMoneyEvent(
                subscription.getWorkspaceId(),
                "SUBSCRIPTION_HALTED",
                null,
                null,
                null,
                "subscription-dunning-halt:" + subscription.getId(),
                Map.of(
                        "subscriptionId", subscription.getId(),
                        "previousStatus", previousStatus.name(),
                        "currentPeriodEnd", String.valueOf(subscription.getCurrentPeriodEnd()),
                        "gracePeriodDays", DUNNING_GRACE_PERIOD.toDays()));

        log.warn(
                "SubscriptionDunningJob: halted subscription {} (workspace {}) — PAST_DUE since"
                        + " period end {}, exceeded {}-day grace period",
                subscription.getId(),
                subscription.getWorkspaceId(),
                subscription.getCurrentPeriodEnd(),
                DUNNING_GRACE_PERIOD.toDays());

        // Best-effort halted email — mirrors RazorpayWebhookController's subscription.halted
        // trigger (see SubscriptionHaltedEvent javadoc for why both publishers intentionally
        // share the same event shape/entityId so a genuine double-fire dedupes at the
        // EmailOutbox idempotency layer instead of double-emailing the brand).
        //
        // W3-2b — NotificationListener's handlers are now @TransactionalEventListener(AFTER_COMMIT)
        // (D2), which is silently never invoked for an event published with no transaction active.
        // Every write above this point (subscriptionRepository.save, auditLog.recordMoneyEvent) is
        // its own individual repository-level transaction, not one shared transaction wrapping this
        // whole method — so publishing here directly, like every call before this task, would have
        // been silently dropped, not delayed. Wrapped in an explicit transaction (same idiom
        // AnalyzeSiteTriggerService#trigger already uses) so AFTER_COMMIT has a real commit to fire
        // after.
        try {
            transactionTemplate.executeWithoutResult(status -> publishHaltedEmail(subscription));
        } catch (Exception e) {
            log.error(
                    "SubscriptionDunningJob: halted email dispatch failed for subscription {} —"
                            + " status transition + audit log were still recorded",
                    subscription.getId(),
                    e);
        }
    }

    private void publishHaltedEmail(Subscription subscription) {
        BillingRecipient recipient = brandContext.resolveBillingRecipient(subscription.getWorkspaceId());
        if (recipient != null && recipient.email() != null) {
            eventPublisher.publishEvent(
                    new SubscriptionHaltedEvent(
                            recipient.userId(),
                            subscription.getWorkspaceId(),
                            subscription.getId(),
                            recipient.email()));
        }
    }
}
