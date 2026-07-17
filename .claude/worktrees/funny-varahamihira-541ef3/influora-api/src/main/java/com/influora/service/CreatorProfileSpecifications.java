package com.influora.service;

import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.entity.PlatformStat;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

public final class CreatorProfileSpecifications {

    private CreatorProfileSpecifications() {}

    public static Specification<CreatorProfile> discoverable() {
        return (root, query, cb) -> cb.isTrue(root.get("discoverable"));
    }

    public static Specification<CreatorProfile> nameSearch(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        String pattern = "%" + q.toLowerCase(Locale.ROOT).trim() + "%";
        return (root, query, cb) ->
                cb.or(
                        cb.like(cb.lower(root.get("displayName")), pattern),
                        cb.like(cb.lower(cb.coalesce(root.get("bio"), "")), pattern));
    }

    public static Specification<CreatorProfile> cityIn(List<String> cities) {
        if (cities == null || cities.isEmpty()) {
            return null;
        }
        return (root, query, cb) -> {
            List<Predicate> preds = new ArrayList<>();
            for (String city : cities) {
                preds.add(cb.like(cb.lower(root.get("city")), "%" + city.toLowerCase(Locale.ROOT) + "%"));
            }
            return cb.or(preds.toArray(Predicate[]::new));
        };
    }

    public static Specification<CreatorProfile> singleCity(String city) {
        if (city == null || city.isBlank()) {
            return null;
        }
        if (city.contains(",")) {
            return cityIn(List.of(city.split(",")));
        }
        return cityIn(List.of(city.trim()));
    }

    public static Specification<CreatorProfile> followersBetween(Long min, Long max) {
        return (root, query, cb) -> {
            List<Predicate> preds = new ArrayList<>();
            if (min != null) {
                preds.add(cb.ge(root.get("totalFollowers"), min));
            }
            if (max != null) {
                preds.add(cb.le(root.get("totalFollowers"), max));
            }
            return preds.isEmpty() ? null : cb.and(preds.toArray(Predicate[]::new));
        };
    }

    public static Specification<CreatorProfile> engagementBetween(BigDecimal min, BigDecimal max) {
        return (root, query, cb) -> {
            List<Predicate> preds = new ArrayList<>();
            if (min != null) {
                preds.add(cb.ge(root.get("engagementRate"), min));
            }
            if (max != null) {
                preds.add(cb.le(root.get("engagementRate"), max));
            }
            return preds.isEmpty() ? null : cb.and(preds.toArray(Predicate[]::new));
        };
    }

    public static Specification<CreatorProfile> verifiedOnly(Boolean verified) {
        if (verified == null || !verified) {
            return null;
        }
        return (root, query, cb) -> cb.isTrue(root.get("verified"));
    }

    public static Specification<CreatorProfile> rateOverlap(BigDecimal minRate, BigDecimal maxRate) {
        return (root, query, cb) -> {
            List<Predicate> preds = new ArrayList<>();
            if (maxRate != null) {
                preds.add(
                        cb.or(
                                cb.isNull(root.get("rateMin")),
                                cb.lessThanOrEqualTo(root.get("rateMin"), maxRate)));
            }
            if (minRate != null) {
                preds.add(
                        cb.or(
                                cb.isNull(root.get("rateMax")),
                                cb.greaterThanOrEqualTo(root.get("rateMax"), minRate)));
            }
            return preds.isEmpty() ? null : cb.and(preds.toArray(Predicate[]::new));
        };
    }

    public static Specification<CreatorProfile> hasPlatforms(List<String> platforms) {
        if (platforms == null || platforms.isEmpty()) {
            return null;
        }
        return (root, query, cb) -> {
            Subquery<Long> sub = query.subquery(Long.class);
            Root<PlatformStat> ps = sub.from(PlatformStat.class);
            sub.select(cb.literal(1L));
            List<Predicate> preds = new ArrayList<>();
            preds.add(cb.equal(ps.get("creatorProfileId"), root.get("id")));
            preds.add(ps.get("platform").in(platforms));
            sub.where(preds.toArray(Predicate[]::new));
            return cb.exists(sub);
        };
    }

    public static Specification<CreatorProfile> combine(Specification<CreatorProfile>... specs) {
        Specification<CreatorProfile> combined = discoverable();
        for (Specification<CreatorProfile> spec : specs) {
            if (spec != null) {
                combined = combined.and(spec);
            }
        }
        return combined;
    }
}
