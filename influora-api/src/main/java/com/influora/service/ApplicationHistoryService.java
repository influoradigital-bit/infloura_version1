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
     * {@code DealService#get} — the only call site — is {@code @Transactional(readOnly = true)},
     * and this needs to write regardless of that flag, without the write's outcome (success OR
     * failure) being able to affect the read it hangs off. The caller still wraps this call in a
     * try/catch as defense in depth against the propagation boundary itself throwing.
     *
     * <p>Check-then-insert, not a DB unique constraint: {@code (application_id, event_type)} is
     * NOT unique at the schema level, because {@code CAMPAIGN_APPLIED}/{@code
     * APPLICATION_ACCEPTED}/{@code APPLICATION_REJECTED} legitimately recur across a
     * withdraw-then-reapply cycle (F-0225 revive). Only {@code APPLICATION_VIEWED} needs
     * first-write-wins semantics, so that rule lives here in application code rather than as a
     * blanket constraint that would also, wrongly, cap every other event type at one row per
     * application forever. This does leave a narrow theoretical race between the existence check
     * and the insert under truly concurrent double-opens; acceptable here because this is a
     * timeline dedupe, not a financial or authorization guarantee.
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
