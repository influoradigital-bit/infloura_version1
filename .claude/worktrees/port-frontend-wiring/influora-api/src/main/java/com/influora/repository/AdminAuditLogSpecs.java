package com.influora.repository;

import com.influora.domain.entity.AdminAuditLog;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/**
 * Filter builder for {@code AdminAuditLogService#list}, mirroring {@code auditApi.list}'s filter
 * shape in {@code src/admin/services/api-contracts.ts} ({@code adminId}, {@code entityType},
 * {@code action}, {@code startDate}/{@code endDate}) — same {@code Specification}-per-field
 * pattern as {@code SupportTicketSpecs}. Every field is optional; {@code null}/blank means "don't
 * filter on this." {@code action}/{@code entityType} are matched as plain equality against the
 * VARCHAR columns, not against {@code AdminAuditLogService}'s write-side allow-lists — an unknown
 * filter value legitimately just returns zero rows here, it isn't a client error the way an
 * unrecognized value is on the write path.
 */
public final class AdminAuditLogSpecs {

    private AdminAuditLogSpecs() {}

    public static Specification<AdminAuditLog> withFilters(
            String adminId, String entityType, String action, Instant startDate, Instant endDate) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (adminId != null && !adminId.isBlank()) {
                predicates.add(cb.equal(root.get("adminId"), adminId));
            }
            if (entityType != null && !entityType.isBlank()) {
                predicates.add(cb.equal(root.get("entityType"), entityType));
            }
            if (action != null && !action.isBlank()) {
                predicates.add(cb.equal(root.get("action"), action));
            }
            if (startDate != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), startDate));
            }
            if (endDate != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), endDate));
            }
            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
