package com.influora.repository;

import com.influora.domain.entity.Campaign;
import com.influora.domain.enums.CampaignStatus;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

public final class CampaignSpecs {

    private CampaignSpecs() {}

    public static Specification<Campaign> forWorkspace(
            String workspaceId, List<CampaignStatus> statuses, String search) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("workspaceId"), workspaceId));
            if (statuses != null && !statuses.isEmpty()) {
                predicates.add(root.get("status").in(statuses));
            }
            if (search != null && !search.isBlank()) {
                String pattern = "%" + search.toLowerCase() + "%";
                predicates.add(
                        cb.or(
                                cb.like(cb.lower(root.get("title")), pattern),
                                cb.like(cb.lower(root.get("description")), pattern)));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /**
     * Task #7 (creator campaign browse) — the creator-facing "open for applications" gate. {@code
     * isPrivate} is the closest real-schema analog to the 05_CREATOR_CAMPAIGNS_SPEC.md
     * {@code isPublic}/{@code inviteOnly} pair (that spec's separate flags were never built);
     * private campaigns are excluded from browse entirely — a creator can still see/apply to one
     * via {@link com.influora.service.CreatorCampaignService} only if they already hold an
     * invitation ({@code Collaboration} row), same shape as the DRAFT exclusion below (never leak
     * a campaign the brand hasn't opened up).
     */
    public static Specification<Campaign> browsableForCreator() {
        return (root, query, cb) ->
                cb.and(
                        cb.equal(root.get("status"), CampaignStatus.ACTIVE),
                        cb.isFalse(root.get("isPrivate")));
    }

    public static Specification<Campaign> applicationDeadlineNotPassed() {
        return (root, query, cb) ->
                cb.or(
                        cb.isNull(root.get("applicationDeadline")),
                        cb.greaterThanOrEqualTo(root.get("applicationDeadline"), LocalDate.now()));
    }

    /** Budget-range overlap, same shape as {@code CreatorProfileSpecifications.rateOverlap}. */
    public static Specification<Campaign> budgetOverlap(BigDecimal min, BigDecimal max) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (max != null) {
                predicates.add(
                        cb.or(
                                cb.isNull(root.get("budgetMin")),
                                cb.lessThanOrEqualTo(root.get("budgetMin"), max)));
            }
            if (min != null) {
                predicates.add(
                        cb.or(
                                cb.isNull(root.get("budgetMax")),
                                cb.greaterThanOrEqualTo(root.get("budgetMax"), min)));
            }
            return predicates.isEmpty() ? null : cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
