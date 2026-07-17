package com.influora.repository;

import com.influora.domain.entity.Plan;
import com.influora.domain.enums.PlanCode;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Subscription pricing tiers (FREE/PRO). Task 17 subscription-billing functional core, Phase 1.
 */
public interface PlanRepository extends JpaRepository<Plan, String> {

    Optional<Plan> findByCode(PlanCode code);

    List<Plan> findByActiveTrue();

    /** Resolves the local {@link Plan} row from a Razorpay-side plan id — used by webhook handling (Task 20) to recover which tier a subscription event belongs to. */
    Optional<Plan> findByRazorpayPlanId(String razorpayPlanId);
}
