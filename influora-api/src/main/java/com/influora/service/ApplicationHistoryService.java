package com.influora.service;

import com.influora.common.AfterCommit;
import com.influora.domain.entity.ApplicationHistoryEvent;
import com.influora.domain.enums.ApplicationHistoryActorType;
import com.influora.domain.enums.ApplicationHistoryEventType;
import com.influora.domain.enums.CollaborationStatus;
import java.time.Instant;
import org.springframework.stereotype.Service;

/**
 * Append-only writer for {@link ApplicationHistoryEvent}. The one place that knows how to build
 * and persist a history row: every call site records through here instead of constructing the
 * entity inline, so the append-only rule and the view-dedupe rule are enforced in one spot.
 *
 * <h2>How a row gets written: captured now, inserted after the caller commits</h2>
 *
 * <p>{@link #record} and {@link #recordViewIfAbsent} do not touch the database. They build an
 * immutable {@link ApplicationHistoryWriteRequest} and hand it to {@link AfterCommit}, which
 * performs the INSERT through {@link ApplicationHistoryWriter} (its own {@code REQUIRES_NEW}
 * transaction) once the caller's transaction has committed and released its row locks. With no
 * transaction active, the write runs inline. Both methods take no locks inside the caller's
 * transaction and never throw.
 *
 * <h2>Why</h2>
 *
 * <p>Both methods used to be {@code @Transactional(REQUIRES_NEW)} themselves, so the INSERT ran on
 * a second connection while the caller's transaction was still open. {@code
 * application_history_events.application_id} has an InnoDB FK to {@code collaborations(id)}
 * ({@code fk_app_history_application}, V69:64), and InnoDB checks it by taking {@code
 * S,REC_NOT_GAP} on the parent row. The callers had already locked that row {@code X}: {@code
 * DealService#doAccept} through the flushed status UPDATE, {@code ContractService#generate} and
 * the contract-signing path through {@code CollaborationRepository#findByIdForUpdate}, {@code
 * EscrowService}'s funding path through {@code CollaborationLifecycleService#onEscrowFunded}'s
 * UPDATE. The insert waited on its own caller until {@code innodb_lock_wait_timeout} (50 s), then
 * failed, and the call site's catch logged it. Measured at 2d143c6 on MySQL 8.0.40: accept 50.44
 * s, contract generation 50.48 s, final signature 50.84 s, 0 history rows.
 *
 * <h2>What the caller can rely on</h2>
 *
 * <ul>
 *   <li>A failed history write never rolls back or fails the business write. It runs after the
 *       commit, inside {@link AfterCommit}'s guard, and is logged at ERROR with {@link
 *       ApplicationHistoryWriteRequest#toLogContext()} (every field needed to rebuild the row).
 *   <li>If the caller's transaction rolls back, the row is discarded and logged at WARN. Under
 *       the old shape a later rollback left the row committed, asserting a fact that did not
 *       happen.
 *   <li>If the commit outcome is unknown, the row is withheld and logged at ERROR. See {@link
 *       AfterCommit}.
 *   <li>If the process dies between the business commit and the write, the row is lost with no
 *       log line. There is no outbox. The old shape lost the row on every locked call.
 * </ul>
 *
 * <p>The try/catch blocks around {@code record(...)} at the call sites are now defence in depth;
 * neither method can throw. They were left in place, so no call site needed an edit.
 */
@Service
public class ApplicationHistoryService {

    private final ApplicationHistoryWriter writer;

    public ApplicationHistoryService(ApplicationHistoryWriter writer) {
        this.writer = writer;
    }

    /** Appends one immutable event after the caller's transaction commits. Never throws. */
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
        enqueue(
                new ApplicationHistoryWriteRequest(
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
                        targetId,
                        false,
                        Instant.now()));
    }

    /**
     * Brand-view tracking. Idempotent per application: only the first brand view is recorded, so
     * reopening an application never adds duplicate {@code APPLICATION_VIEWED} rows. A
     * collaboration belongs to one campaign and workspace, so "per (application, brand)" is "per
     * application".
     *
     * <p>Check-then-insert is the fast path (in {@link ApplicationHistoryWriter#write}) and a DB
     * constraint is the backstop. {@code (application_id, event_type)} is deliberately not unique:
     * {@code CAMPAIGN_APPLIED}, {@code APPLICATION_ACCEPTED}, {@code APPLICATION_REJECTED} and
     * {@code APPLICATION_WITHDRAWN} legitimately recur across a withdraw-then-reapply cycle
     * (F-0225). V70 ({@code uq_app_history_viewed_once}) scopes a UNIQUE index to {@code
     * APPLICATION_VIEWED} only, through a generated column that is NULL for every other type. Four
     * requests can race the same application ({@code DealService#get}, {@code doAccept}, {@code
     * doReject}, {@code doCounter}; Decision 6, .proof-os/tasks/T-RULING-0818/SWAPNIL-RULING.md),
     * so that constraint is load-bearing. A duplicate that slips past the check fails inside the
     * writer's own transaction after the caller has committed, and is logged, never thrown.
     *
     * <p>{@code DealService#get} is {@code readOnly = true}. The write is deferred to after that
     * read-only transaction completes and runs in its own transaction, so the flag does not block
     * it.
     */
    public void recordViewIfAbsent(
            String campaignId,
            String applicationId,
            String actorId,
            String description,
            CollaborationStatus eventStatus) {
        enqueue(
                new ApplicationHistoryWriteRequest(
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
                        null,
                        true,
                        Instant.now()));
    }

    private void enqueue(ApplicationHistoryWriteRequest request) {
        AfterCommit.run(
                "application-history event", request::toLogContext, () -> writer.write(request));
    }
}
