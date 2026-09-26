package com.influora.service.meera;

import com.influora.domain.entity.AudienceDemographics;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The creator's OWN audience as shares (Swapnil 2026-09-26): the arithmetic and the honest
 * not-available reasons shared by {@code get_my_audience} and the "Your audience" line in
 * {@link MeeraContextService}, so the tool and the context block can never word the same state two
 * ways in one conversation.
 *
 * <p>Input is the raw {@code {bucket: count}} breakdown maps of one {@code audience_demographics}
 * snapshot (follower or engaged). Values are read as {@code Object}: the maps are decoded from JSON
 * with a raw {@code Map.class}, so a value is usually an {@code Integer} at runtime despite the
 * declared {@code Long}. Nothing here guesses: an empty breakdown gives an empty list, never zeros.
 * Every percentage is an integer share of THAT breakdown's own total; an entry that rounds to 0% is
 * left out rather than shown as "0%".
 */
public final class CreatorAudienceShares {

    /** Instagram is not connected (or the connection was revoked or expired). */
    public static final String NOT_CONNECTED = "Instagram is not connected";

    /** Connected, but no follower snapshot has been written yet. */
    public static final String FOLLOWERS_NOT_YET =
            "Instagram is connected, but its audience details have not arrived yet (Instagram shares"
                    + " them only for accounts with 100 or more followers)";

    /** The read itself failed. */
    public static final String READ_FAILED = "the audience details could not be read just now";

    /** Engaged: Meta returned nothing (fewer than 100 engagements this month). */
    public static final String ENGAGED_BELOW_THRESHOLD =
            "fewer than 100 engagements this month, and Instagram shares who engaged only above that";

    /** Engaged: the call failed on the last weekly check. */
    public static final String ENGAGED_FETCH_FAILED = "Instagram did not return it on the last weekly check";

    /** Engaged: never fetched for this snapshot (written before the engaged fetch existed). */
    public static final String ENGAGED_NOT_YET = "not fetched yet; it arrives with the next weekly Instagram update";

    public static final int TOP_CITIES = 5;
    public static final int TOP_COUNTRIES = 5;

    private static final Pattern CONTROL_CHARS = Pattern.compile("\\p{Cntrl}+");

    /** One share: a key (age band, gender word, country code) and its integer percentage. */
    public record Share(String key, int pct) {}

    private CreatorAudienceShares() {}

    /**
     * The engaged audience's not-available reason for a snapshot's {@code engaged_status} (see
     * {@code V20260926120000}). {@code AVAILABLE} with nothing in it reads as below the threshold,
     * the only way an empty engaged result can arise.
     */
    public static String engagedReason(String engagedStatus) {
        if (engagedStatus == null) {
            return ENGAGED_NOT_YET;
        }
        return switch (engagedStatus) {
            case AudienceDemographics.ENGAGED_FETCH_FAILED -> ENGAGED_FETCH_FAILED;
            case AudienceDemographics.ENGAGED_BELOW_THRESHOLD, AudienceDemographics.ENGAGED_AVAILABLE ->
                    ENGAGED_BELOW_THRESHOLD;
            default -> ENGAGED_NOT_YET;
        };
    }

    /** Every age band with its share of the age/gender total, in band order ("13-17", "18-24", ..., "65+"). */
    public static List<Share> ageBands(Map<String, ?> ageGender) {
        Map<String, Long> totals = new LinkedHashMap<>();
        long total = fold(ageGender, totals, 1);
        List<Share> out = new ArrayList<>();
        totals.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> addShare(out, e.getKey(), e.getValue(), total));
        return out;
    }

    /** "women" / "men" / "unknown" with their shares of the age/gender total, largest first. */
    public static List<Share> genders(Map<String, ?> ageGender) {
        Map<String, Long> totals = new LinkedHashMap<>();
        long total = fold(ageGender, totals, 0);
        List<Share> out = new ArrayList<>();
        largestFirst(totals, Integer.MAX_VALUE)
                .forEach(e -> addShare(out, genderWord(e.getKey()), e.getValue(), total));
        return out;
    }

    /** The largest cities by name only (control characters removed), at most {@code limit}. */
    public static List<String> topCities(Map<String, ?> cities, int limit) {
        Map<String, Long> counts = labelCounts(cities);
        return largestFirst(counts, limit).stream().map(Map.Entry::getKey).toList();
    }

    /** The largest countries (Meta's ISO codes) with their share of the country total, at most {@code limit}. */
    public static List<Share> topCountries(Map<String, ?> countries, int limit) {
        Map<String, Long> counts = labelCounts(countries);
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        List<Share> out = new ArrayList<>();
        largestFirst(counts, limit).forEach(e -> addShare(out, e.getKey(), e.getValue(), total));
        return out;
    }

    /** Folds an age/gender map into totals keyed by gender code (part 0) or age band (part 1). */
    private static long fold(Map<String, ?> ageGender, Map<String, Long> totals, int part) {
        long total = 0;
        if (ageGender == null) {
            return 0;
        }
        for (Map.Entry<String, ?> entry : ageGender.entrySet()) {
            long count = countOf(entry.getValue());
            String[] genderAndAge = MeeraContextService.splitAgeGenderKey(entry.getKey());
            if (count <= 0 || genderAndAge == null) {
                continue;
            }
            String key = sanitize(genderAndAge[part]);
            if (key.isEmpty()) {
                continue;
            }
            totals.merge(key, count, Long::sum);
            total += count;
        }
        return total;
    }

    private static Map<String, Long> labelCounts(Map<String, ?> raw) {
        Map<String, Long> counts = new LinkedHashMap<>();
        if (raw == null) {
            return counts;
        }
        for (Map.Entry<String, ?> entry : raw.entrySet()) {
            long count = countOf(entry.getValue());
            if (count <= 0 || entry.getKey() == null) {
                continue;
            }
            String label = sanitize(entry.getKey());
            if (!label.isEmpty()) {
                counts.merge(label, count, Long::sum);
            }
        }
        return counts;
    }

    private static void addShare(List<Share> out, String key, long count, long total) {
        if (total <= 0) {
            return;
        }
        int pct = (int) Math.round(count * 100.0 / total);
        if (pct > 0) {
            out.add(new Share(key, pct));
        }
    }

    /** Largest first; ties broken by key so the same snapshot always gives the same order. */
    private static List<Map.Entry<String, Long>> largestFirst(Map<String, Long> counts, int limit) {
        return counts.entrySet().stream()
                .sorted(
                        Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder())
                                .thenComparing(Map.Entry.comparingByKey()))
                .limit(limit)
                .toList();
    }

    private static String genderWord(String metaCode) {
        return switch (metaCode) {
            case "F" -> "women";
            case "M" -> "men";
            default -> "unknown";
        };
    }

    private static long countOf(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static String sanitize(String raw) {
        return raw == null ? "" : CONTROL_CHARS.matcher(raw).replaceAll(" ").strip();
    }
}
