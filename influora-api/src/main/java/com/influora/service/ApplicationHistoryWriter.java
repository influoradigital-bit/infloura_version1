package com.influora.service;

import com.influora.common.Ulids;
import com.influora.domain.entity.ApplicationHistoryEvent;
import com.influora.domain.enums.ApplicationHistoryEventType;
import com.influora.repository.ApplicationHistoryEventRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one place an {@code application_history_events} row is actually inserted.
 *
 * <p><b>A separate bean on purpose.</b> {@link ApplicationHistoryService} hands this method to
 * {@link com.influora.common.AfterCommit} as a lambda. Calling it on {@code this} would be
 * self-invocation, and {@code @Transactional} would be silently inert. A separate bean means the
 * call goes through the transactional proxy.
 *
 * <p><b>Why {@code REQUIRES_NEW} is still right, and no longer dangerous.</b> The stall came from
 * {@code REQUIRES_NEW} running while the caller still held the {@code collaborations} row. This
 * method now runs only after the caller has committed and InnoDB has released its locks. It still
 * needs its own transaction for two reasons. First, during {@code afterCommit} the committed
 * transaction's resources are still bound to the thread, so a {@code REQUIRED} method would join
 * a transaction that has already committed and its insert would never be committed. Second, the
 * entity's {@code @Id} is a pre-assigned ULID, so {@code save} goes through {@code em.merge()} and
 * the INSERT happens at flush. With its own transaction that flush happens inside this call, where
 * {@code AfterCommit}'s guard can catch a failure.
 *
 * <p>Never called by business code. Go through {@link ApplicationHistoryService}.
 */
@Component
public class ApplicationHistoryWriter {

    private final ApplicationHistoryEventRepository repository;

    public ApplicationHistoryWriter(ApplicationHistoryEventRepository repository) {
        this.repository = repository;
    }

    /**
     * Inserts the row. Throws on failure: {@link com.influora.common.AfterCommit} owns the catch
     * and the ERROR log, because only a guard outside this transactional proxy can see a failure
     * at flush or commit.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(ApplicationHistoryWriteRequest request) {
        if (request.viewIfAbsent()
                && repository.existsByApplicationIdAndEventType(
                        request.applicationId(), ApplicationHistoryEventType.APPLICATION_VIEWED)) {
            // First-write-wins fast path for APPLICATION_VIEWED. V70's partial UNIQUE index
            // (uq_app_history_viewed_once) is the real arbiter under concurrency; a duplicate that
            // slips past this check fails at this method's commit and AfterCommit logs it.
            return;
        }
        repository.save(
                ApplicationHistoryEvent.create(
                        Ulids.newUlid(),
                        request.campaignId(),
                        request.applicationId(),
                        request.dealRoomId(),
                        request.eventType(),
                        request.eventStatus(),
                        request.actorType(),
                        request.actorId(),
                        request.description(),
                        request.metadata(),
                        request.targetRoute(),
                        request.targetId()));
    }
}
