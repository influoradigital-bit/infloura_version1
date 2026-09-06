package com.influora.repository;

import com.influora.domain.entity.FestivalEnquiry;
import com.influora.domain.enums.FestivalEnquiryStatus;
import com.influora.domain.enums.FestivalEnquiryType;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/**
 * Filter builder for {@code AdminFestivalEnquiryService#list} (T-FESTIVALBOX-0905) — same
 * Specification-per-field shape as {@link ExternalCreatorSpecs}. Every field is optional;
 * null/blank means "don't filter on this".
 */
public final class FestivalEnquirySpecs {

    private FestivalEnquirySpecs() {}

    public static Specification<FestivalEnquiry> withFilters(
            FestivalEnquiryType type, FestivalEnquiryStatus status, String edition, String q) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (type != null) {
                predicates.add(cb.equal(root.get("type"), type));
            }

            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }

            if (edition != null && !edition.isBlank()) {
                predicates.add(cb.equal(root.get("edition"), edition));
            }

            if (q != null && !q.isBlank()) {
                String qLower = "%" + q.toLowerCase() + "%";
                // company/instagramHandle are each populated on only ONE of the two row types, so
                // they are NULL on the other half of the table. cb.like over a NULL column yields
                // UNKNOWN, which inside this OR is simply not-a-match — the desired behaviour here
                // (unlike ExternalCreatorSpecs' range filters, where UNKNOWN silently excluded rows
                // that should have been shown). coalesce to "" anyway so the intent is explicit and
                // does not depend on the reader knowing SQL's three-valued logic.
                predicates.add(
                        cb.or(
                                cb.like(cb.lower(root.get("name")), qLower),
                                cb.like(cb.lower(root.get("email")), qLower),
                                cb.like(cb.lower(cb.coalesce(root.get("company"), "")), qLower),
                                cb.like(
                                        cb.lower(cb.coalesce(root.get("instagramHandle"), "")),
                                        qLower)));
            }

            return predicates.isEmpty()
                    ? cb.conjunction()
                    : cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
