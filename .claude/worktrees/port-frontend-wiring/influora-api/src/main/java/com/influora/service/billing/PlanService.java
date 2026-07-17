package com.influora.service.billing;

import com.influora.common.ApiException;
import com.influora.domain.entity.Plan;
import com.influora.domain.enums.PlanCode;
import com.influora.repository.PlanRepository;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Read-only lookups over {@link Plan} (FREE/PRO pricing tiers). Task 18 subscription-billing
 * functional core, Phase 1 — no write path here; plan rows are seeded by V55 and edited only via
 * a future admin console (Task 25).
 */
@Service
public class PlanService {

    private final PlanRepository planRepository;

    public PlanService(PlanRepository planRepository) {
        this.planRepository = planRepository;
    }

    public Plan getByCode(PlanCode code) {
        return planRepository
                .findByCode(code)
                .orElseThrow(
                        () ->
                                new ApiException(
                                        "PLAN_NOT_FOUND",
                                        "Plan " + code + " is not configured — run the V55 seed migration",
                                        HttpStatus.NOT_FOUND));
    }

    public List<Plan> getActivePlans() {
        return planRepository.findByActiveTrue();
    }

    public Plan getFreePlan() {
        return getByCode(PlanCode.FREE);
    }

    public Plan getProPlan() {
        return getByCode(PlanCode.PRO);
    }
}
