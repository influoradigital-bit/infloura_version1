package com.influora.repository;

import com.influora.domain.entity.Subscription;
import com.influora.domain.enums.SubscriptionStatus;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/**
 * Filter builder for {@code AdminBillingService#listSubscriptions} (Task 25 {@code
 * AdminBillingController}). Same {@code Specification}-per-field pattern as {@code
 * AdminAuditLogSpecs}/{@code AdminBrandService}'s inline Workspace spec-building.
 *
 * <p>{@code Subscription} has no JPA relation to {@code Workspace} (deliberately — see {@code
 * Subscription} class javadoc, it's a plain {@code workspace_id} string column, not a mapped
 * {@code @ManyToOne}), so a workspace-name search cannot be expressed as a join predicate here.
 * {@code AdminBillingService} resolves matching workspace ids via a separate {@code
 * WorkspaceRepository} query FIRST, then passes that id list into {@link #withFilters} as {@code
 * workspaceIdIn} — same two-step shape used for any other cross-entity search in this codebase
 * where no relation is mapped.
 */
public final class SubscriptionAdminSpecs {

    private SubscriptionAdminSpecs() {}

    public static Specification<Subscription> withFilters(
            SubscriptionStatus status, List<String> workspaceIdIn) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (workspaceIdIn != null) {
                // Caller passes an explicit (possibly empty) list only when a search term was
                // supplied — an empty list here correctly yields zero rows (no workspace matched
                // the search) rather than being mistaken for "no filter."
                predicates.add(root.get("workspaceId").in(workspaceIdIn));
            }
            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
