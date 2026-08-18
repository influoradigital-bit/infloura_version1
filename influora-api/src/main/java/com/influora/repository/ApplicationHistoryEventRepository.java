package com.influora.repository;

import com.influora.domain.entity.ApplicationHistoryEvent;
import com.influora.domain.enums.ApplicationHistoryEventType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApplicationHistoryEventRepository
        extends JpaRepository<ApplicationHistoryEvent, String> {

    /**
     * Chronological timeline for one application — backs {@code GET
     * /creator/applications/{dealId}/history}. Ordered by {@code created_at} then {@code
     * sequence_no}: {@code created_at} is {@code TIMESTAMP(3)} (millisecond precision) and two
     * events can land in the same millisecond (e.g. {@code CreatorCampaignService
     * #recordApplicationHistory}'s back-to-back writes), so {@code sequence_no} — a real
     * DB-assigned {@code AUTO_INCREMENT} column, monotonic by construction — is the tiebreak. See
     * {@code ApplicationHistoryEvent#sequenceNo}'s javadoc for why this was chosen over an
     * id-based tiebreak.
     */
    List<ApplicationHistoryEvent> findByApplicationIdOrderByCreatedAtAscSequenceNoAsc(String applicationId);

    /**
     * Brand-view dedupe check. Only {@code
     * ApplicationHistoryEventType.APPLICATION_VIEWED} is ever queried against this — see {@code
     * ApplicationHistoryService#recordViewIfAbsent}.
     */
    boolean existsByApplicationIdAndEventType(String applicationId, ApplicationHistoryEventType eventType);
}
