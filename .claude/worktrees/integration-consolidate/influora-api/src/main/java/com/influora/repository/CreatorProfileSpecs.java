package com.influora.repository;

import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.enums.CreatorApplicationStatus;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/**
 * Filter builder for {@code AdminCreatorService#list}, mirroring {@code CreatorFilters} in {@code
 * src/admin/types/admin.types.ts} — same {@code Specification}-per-field shape as {@code
 * SupportTicketSpecs}/{@code CampaignSpecs}. Every field is optional; {@code null}/blank means
 * "don't filter on this."
 *
 * <p>Search filtering: {@code search} param searches across {@code displayName} only at the
 * database level. Email and Instagram handle search would require joins to {@code users} and
 * {@code platform_stats} tables; those are handled as post-filters in the service layer if
 * needed for more complex search (current spec only searches displayName for simplicity and
 * performance, same pattern as {@code SupportTicketSpecs} which searches {@code subject} only).
 */
public final class CreatorProfileSpecs {

    private CreatorProfileSpecs() {}

    public static Specification<CreatorProfile> withFilters(
            CreatorApplicationStatus applicationStatus, Boolean suspended, String search) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (applicationStatus != null) {
                predicates.add(cb.equal(root.get("applicationStatus"), applicationStatus));
            }

            if (suspended != null) {
                predicates.add(cb.equal(root.get("suspended"), suspended));
            }

            if (search != null && !search.isBlank()) {
                String searchLower = "%" + search.toLowerCase() + "%";
                // Search by displayName only (email/handle would require joins, kept simple for now)
                predicates.add(cb.like(cb.lower(root.get("displayName")), searchLower));
            }

            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
