package com.influora.repository;

import com.influora.domain.entity.PaymentMilestone;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentMilestoneRepository extends JpaRepository<PaymentMilestone, String> {

    List<PaymentMilestone> findByContractIdOrderBySequenceNoAsc(String contractId);

    Optional<PaymentMilestone> findByIdAndCollaborationId(String id, String collaborationId);

    Optional<PaymentMilestone> findByIdempotencyKey(String idempotencyKey);
}
