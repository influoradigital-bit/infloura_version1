package com.influora.repository;

import com.influora.domain.entity.SupportTicket;
import com.influora.domain.enums.TicketPriority;
import com.influora.domain.enums.TicketStatus;
import com.influora.domain.enums.UserType;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/**
 * Filter builder for {@code AdminSupportService#list}, mirroring {@code
 * TicketFilters} in {@code src/admin/types/admin.types.ts} — same {@code Specification}-per-field
 * shape as {@code CampaignSpecs}. Every field is optional; {@code null}/blank means "don't filter
 * on this."
 */
public final class SupportTicketSpecs {

    private SupportTicketSpecs() {}

    public static Specification<SupportTicket> withFilters(
            TicketStatus status,
            TicketPriority priority,
            UserType userType,
            String assignedTo,
            String category,
            String search) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (priority != null) {
                predicates.add(cb.equal(root.get("priority"), priority));
            }
            if (userType != null) {
                predicates.add(cb.equal(root.get("userType"), userType));
            }
            if (assignedTo != null && !assignedTo.isBlank()) {
                predicates.add(cb.equal(root.get("assignedTo"), assignedTo));
            }
            if (category != null && !category.isBlank()) {
                predicates.add(cb.equal(root.get("category"), category));
            }
            if (search != null && !search.isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("subject")), "%" + search.toLowerCase() + "%"));
            }
            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
