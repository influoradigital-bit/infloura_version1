package com.influora.service;

import com.influora.common.Ulids;
import com.influora.domain.entity.ApplicationHistoryEvent;
import com.influora.domain.enums.ApplicationHistoryActorType;
import com.influora.domain.enums.ApplicationHistoryEventType;
import com.influora.domain.enums.CollaborationStatus;
import com.influora.repository.ApplicationHistoryEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Append-only writer for {@link ApplicationHistoryEvent}. The one place that knows how to build
 * and persist a history row, mirroring {@link CollaborationReviveService}'s "one decision, one
 * place" shape — every call site that needs to record an event goes through here instead of
 * constructing the entity inline, so the append-only rule and the view-dedupe rule stay enforced
 * in one spot rather than copied at each call site.
 *
 * <p><b>Callers own the safety net, but only because {@link #record} is {@code REQUIRES_NEW}.</b>
 * (Sign-off review fix — this javadoc previously documented bare {@code @Transactional}/{@code
 * REQUIRED} here, and asserted that a caller-side try/catch alone was enough to protect the
 * business transaction. That was untrue. Called cross-bean through the proxy under {@code
 * REQUIRED}, an exception inside {@link #record} hits ITS OWN {@code TransactionInterceptor}
 * first, which — because {@code globalRollbackOnParticipationFailure} defaults {@code true} for a
 * transaction that is merely PARTICIPATING, not the one that opened it — marks the shared,
 * ambient transaction {@code rollbackOnly} before the exception ever reaches the caller's catch
 * block. The caller's try/catch then swallows the exception and believes it succeeded, but the
 * transaction is already doomed: the caller's own eventual commit throws {@code
 * UnexpectedRollbackException}, and the business write (escrow funding, a contract signature, a
 * deliverable approval, an accept) rolls back anyway — the exact outcome every call-site comment
 * in this codebase says cannot happen.
 *
 * <p>There is a second, independent hazard under {@code REQUIRED}: Spring Data calls {@code
 * em.merge()} for an entity with a pre-assigned non-null {@code @Id} (this entity's {@code
 * historyId} always is), so the actual {@code INSERT} is deferred to flush — which for a
 * participating transaction happens at the AMBIENT (caller's) commit, not here. A constraint
 * violation on that deferred flush would surface outside this method's own body entirely,
 * unreachable by a try/catch wrapped around the {@code record(...)} call site no matter how
 * carefully it's written.
 *
 * <p>{@code REQUIRES_NEW} closes both holes at once: {@link #record} now runs in its own,
 * independent transaction/connection, so it can never mark the caller's ambient transaction
 * rollback-only, and its commit (flush included) happens INSIDE this method's own boundary —
 * so a deferred-flush constraint violation surfaces as an exception thrown FROM the {@code
 * record(...)} call itself, landing exactly where the caller's try/catch already is. Every call
 * site MUST still wrap it in a try/catch the same way {@code
 * CreatorCampaignService#recordApplicationOnTimeline} already wraps its {@code DealMessage}
 * write — {@code REQUIRES_NEW} makes that catch actually effective; it does not make the catch
 * optional. See {@code ApplicationHistoryServiceRollbackIsolationTest} for a real-transaction-
 * manager proof (a mocked {@code ApplicationHistoryService} cannot exercise any of this — the
 * mock throws synchronously and every catch appears to work whether or not the real annotation is
 * correct).
 */
@Service
public class ApplicationHistoryService {

    private final ApplicationHistoryEventRepository repository;

    public ApplicationHistoryService(ApplicationHistoryEventRepository repository) {
        this.repository = repository;
    }

    /**
     * Appends one immutable event, in its OWN independent transaction — never the caller's
     * ambient one. See the class javadoc for why {@code REQUIRES_NEW} is load-bearing here, not a
     * style preference.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
            String campaignId,
            String applicationId,
            String dealRoomId,
            ApplicationHistoryEventType eventType,
            CollaborationStatus eventStatus,
            ApplicationHistoryActorType actorType,
            String actorId,
            String description,
            String metadata,
            String targetRoute,
            String targetId) {
        repository.save(
                ApplicationHistoryEvent.create(
                        Ulids.newUlid(),
                        campaignId,
                        applicationId,
                        dealRoomId,
                        eventType,
                        eventStatus,
                        actorType,
                        actorId,
                        description,
                        metadata,
                        targetRoute,
                        targetId));
    }

    /**
     * Brand-view tracking. Idempotent per application: only the FIRST brand view is recorded, so
     * re-opening the same application never spams the timeline with duplicate {@code
     * APPLICATION_VIEWED} rows. A collaboration belongs to exactly one campaign/workspace, so
     * "per (application, brand)" collapses to "per application" — there is only ever one brand on
     * the other side of a given application.
     *
     * <p>Deliberately its own independent transaction ({@code REQUIRES_NEW}), not the caller's.
     * {@code DealService#get} — originally the only call site, {@code @Transactional(readOnly =
     * true)} — needs to write regardless of that flag, without the write's outcome (success OR
     * failure) being able to affect the read it hangs off. Decision 6
     * (.proof-os/tasks/T-RULING-0818/SWAPNIL-RULING.md) added three more call sites — {@code
     * DealService#doAccept}/{@code #doReject}/{@code #doCounter}, each guarded on {@code
     * UserType.BRAND} — so a brand acting on a bid directly from the campaign-detail Bids tab
     * (never opening {@code GET /deals/{id}}) still produces a view row before its decision event.
     * Every call site still wraps this call in a try/catch as defense in depth against the
     * propagation boundary itself throwing.
     *
     * <p>Check-then-insert as the FAST PATH, a real DB constraint as the BACKSTOP.
     * {@code (application_id, event_type)} is deliberately NOT unique at the schema level — {@code
     * CAMPAIGN_APPLIED}/{@code APPLICATION_ACCEPTED}/{@code APPLICATION_REJECTED}/{@code
     * APPLICATION_WITHDRAWN} legitimately recur across a withdraw-then-reapply cycle (F-0225
     * revive), and a blanket constraint on that pair would 500 every legitimate re-application.
     * Only {@code APPLICATION_VIEWED} needs first-write-wins semantics. V70
     * ({@code V70__application_history_events_viewed_unique.sql}) adds a UNIQUE index scoped to
     * exactly that: a generated column that is NULL for every other event_type and carries {@code
     * application_id} only when {@code event_type = 'APPLICATION_VIEWED'} — InnoDB never treats
     * two NULLs as equal in a UNIQUE index, so every other event type is exempt by construction.
     *
     * <p>Before V70, this method's javadoc described the check-then-insert race between the
     * existence check above and the {@code save} below as an accepted "narrow theoretical" gap.
     * That was overstated the moment Decision 6 (.proof-os/tasks/T-RULING-0818/SWAPNIL-RULING.md)
     * added three more call sites on top of {@code DealService#get} — four independent HTTP
     * requests can race the SAME application, not one (see V70's migration comment for the full
     * reachability argument). V70 closes the race at the schema level; this method deliberately
     * adds NO Java-level {@code try/catch} around the {@code save} call below for it. The reason is
     * the same hazard already documented and fixed once in this very file for {@link #record}:
     * this entity's {@code @Id} is a pre-assigned ULID, so {@code save} routes through {@code
     * em.merge()} and defers the actual {@code INSERT} to flush, which for a {@code REQUIRES_NEW}
     * method happens at ITS OWN transactional-proxy commit — outside this method's own body, not
     * reachable by a {@code try/catch} wrapped around the {@code save} call inside it. A
     * constraint violation instead surfaces from the {@code recordViewIfAbsent(...)} CALL itself,
     * landing exactly where every one of its four call sites (get/doAccept/doReject/doCounter)
     * already wraps it in {@code catch (RuntimeException e)} — already-correct, already-shipped
     * best-effort handling (log and continue; the primary business action must never fail because
     * a view row could not be written) that needed no change to absorb this. See
     * {@code ApplicationHistoryEventViewedUniquenessTest} for the real-database proof that V70's
     * constraint rejects a genuine duplicate {@code APPLICATION_VIEWED} insert while leaving the
     * recurring event types untouched.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordViewIfAbsent(
            String campaignId, String applicationId, String actorId, String description, CollaborationStatus eventStatus) {
        if (repository.existsByApplicationIdAndEventType(
                applicationId, ApplicationHistoryEventType.APPLICATION_VIEWED)) {
            return;
        }
        repository.save(
                ApplicationHistoryEvent.create(
                        Ulids.newUlid(),
                        campaignId,
                        applicationId,
                        null,
                        ApplicationHistoryEventType.APPLICATION_VIEWED,
                        eventStatus,
                        ApplicationHistoryActorType.BRAND,
                        actorId,
                        description,
                        null,
                        null,
                        null));
    }
}
