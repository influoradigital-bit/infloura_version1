package com.influora.repository;

import com.influora.domain.entity.Campaign;
import com.influora.domain.enums.CampaignStatus;
import jakarta.persistence.criteria.Predicate;
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
}
