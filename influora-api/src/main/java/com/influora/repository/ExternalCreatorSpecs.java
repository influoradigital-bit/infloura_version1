package com.influora.repository;

import com.influora.domain.entity.ExternalCreator;
import com.influora.domain.enums.ExternalCreatorStatus;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/**
 * Filter builder for {@code ExternalCreatorController#list} (brand-facing, all statuses) and
 * {@code AdminCreatorConnectionService#listExternalCreators} — same {@code Specification}-per-
 * field shape as {@link CreatorProfileSpecs}. Every field is optional; {@code null}/blank means
 * "don't filter on this."
 */
public final class ExternalCreatorSpecs {

    private ExternalCreatorSpecs() {}

    public static Specification<ExternalCreator> withFilters(
            String q, Long minFollowers, Long maxFollowers, ExternalCreatorStatus status) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (q != null && !q.isBlank()) {
                String qLower = "%" + q.toLowerCase() + "%";
                predicates.add(
                        cb.or(
                                cb.like(cb.lower(root.get("igUsername")), qLower),
                                cb.like(cb.lower(cb.coalesce(root.get("displayName"), "")), qLower)));
            }

            // Q1.5 — followers is nullable (every META_MARKETPLACE row and every un-enriched
            // ADMIN_IMPORT/BUSINESS_DISCOVERY stub has no followers count yet, ExternalCreatorService
            // §applySync). SQL's three-valued logic makes `followers >= ?` UNKNOWN — not true — for
            // a NULL column, so a bare comparison silently drops every not-yet-enriched row out of
            // the result with no explanation once the frontend ever sends these filters. OR in an
            // explicit IS NULL so an unknown follower count is never treated as "excluded by this
            // filter" (unknown-follower cards render "—", never as absent from the list).
            if (minFollowers != null) {
                predicates.add(
                        cb.or(
                                cb.isNull(root.get("followers")),
                                cb.greaterThanOrEqualTo(root.get("followers"), minFollowers)));
            }

            if (maxFollowers != null) {
                predicates.add(
                        cb.or(
                                cb.isNull(root.get("followers")),
                                cb.lessThanOrEqualTo(root.get("followers"), maxFollowers)));
            }

            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }

            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
