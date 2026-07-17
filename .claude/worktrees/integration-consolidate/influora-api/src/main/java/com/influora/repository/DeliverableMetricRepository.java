package com.influora.repository;

import com.influora.domain.entity.DeliverableMetric;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeliverableMetricRepository extends JpaRepository<DeliverableMetric, String> {

    Optional<DeliverableMetric> findByMilestoneId(String milestoneId);

    /** All reported metrics across every milestone belonging to a campaign's collaborations. */
    List<DeliverableMetric> findByCollaborationIdIn(List<String> collaborationIds);

    List<DeliverableMetric> findByCollaborationId(String collaborationId);
}
