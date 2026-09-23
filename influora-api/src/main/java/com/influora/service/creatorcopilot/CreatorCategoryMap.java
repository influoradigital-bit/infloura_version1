package com.influora.service.creatorcopilot;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One mapping from a creator's category to the content calendar's categories (audit 2026-09-24,
 * lane B1).
 *
 * <p>Onboarding stores a creator's categories from {@code CONTENT_VERTICALS} in {@code
 * src/pages/creator-onboarding.tsx} ("Food &amp; Cooking", "Tech &amp; Gaming", ...) and some
 * profiles hold free text ("tech", "gadgets"). The admin's {@code content_topics} rows and the
 * festival calendar use the calendar's own words ("Food", "Technology", "Local business", ...), so
 * an exact match served an onboarded creator nothing but the {@code ALL} rows.
 *
 * <p>The rule: normalise (trim, lower-case, collapse inner whitespace); a category that already is
 * a calendar category maps to itself; otherwise {@link #TABLE} decides; anything else maps to
 * nothing.
 *
 * <p><b>The same table lives in Python</b> ({@code influora-ai/app/planner/categories.py}), which
 * the week plan's festival calendar uses. {@code influora-ai/tests/planner/test_categories.py}
 * reads THIS source file and fails when the two tables differ, so keep each entry on the {@code
 * Map.entry("...", List.of("..."))} shape that test parses.
 */
public final class CreatorCategoryMap {

    private CreatorCategoryMap() {}

    /** Every category the calendar uses, apart from the ALL marker. */
    public static final List<String> CALENDAR_CATEGORIES =
            List.of(
                    "Food",
                    "Fitness",
                    "Beauty",
                    "Finance",
                    "Education",
                    "Gardening",
                    "Travel",
                    "Local business",
                    "DIY or crafts",
                    "Technology",
                    "Culture");

    /** Creator category (as onboarding or the creator writes it) to calendar categories. */
    static final Map<String, List<String>> TABLE =
            Map.ofEntries(
                    // The onboarding verticals (CONTENT_VERTICALS in creator-onboarding.tsx).
                    Map.entry("Fashion & Lifestyle", List.of("Beauty", "Culture")),
                    Map.entry("Beauty & Skincare", List.of("Beauty")),
                    Map.entry("Fitness & Health", List.of("Fitness")),
                    Map.entry("Food & Cooking", List.of("Food")),
                    Map.entry("Tech & Gaming", List.of("Technology")),
                    Map.entry("Travel & Adventure", List.of("Travel")),
                    Map.entry("Education & Learning", List.of("Education")),
                    Map.entry("Finance & Business", List.of("Finance", "Local business")),
                    Map.entry("Entertainment & Comedy", List.of("Culture")),
                    Map.entry("Parenting & Family", List.of("Education", "Food")),
                    Map.entry("Art & Photography", List.of("DIY or crafts", "Culture")),
                    Map.entry("Music & Dance", List.of("Culture")),
                    // Free-text aliases seen on real profiles.
                    Map.entry("tech", List.of("Technology")),
                    Map.entry("technology", List.of("Technology")),
                    Map.entry("gadgets", List.of("Technology")),
                    Map.entry("food", List.of("Food")),
                    Map.entry("cooking", List.of("Food")),
                    Map.entry("fitness", List.of("Fitness")),
                    Map.entry("health", List.of("Fitness")),
                    Map.entry("gym", List.of("Fitness")),
                    Map.entry("beauty", List.of("Beauty")),
                    Map.entry("skincare", List.of("Beauty")),
                    Map.entry("makeup", List.of("Beauty")),
                    Map.entry("travel", List.of("Travel")),
                    Map.entry("finance", List.of("Finance")),
                    Map.entry("money", List.of("Finance")),
                    Map.entry("education", List.of("Education")),
                    Map.entry("study", List.of("Education")),
                    Map.entry("art", List.of("DIY or crafts")),
                    Map.entry("craft", List.of("DIY or crafts")),
                    Map.entry("crafts", List.of("DIY or crafts")),
                    Map.entry("diy", List.of("DIY or crafts")));

    private static final Pattern WHITESPACE = Pattern.compile("(?U)\\s+");

    private static final Map<String, String> CALENDAR_BY_KEY = new HashMap<>();
    private static final Map<String, List<String>> TABLE_BY_KEY = new HashMap<>();

    static {
        for (String category : CALENDAR_CATEGORIES) {
            CALENDAR_BY_KEY.put(normalise(category), category);
        }
        TABLE.forEach((key, value) -> TABLE_BY_KEY.put(normalise(key), value));
    }

    /** Trimmed, lower-cased, inner whitespace collapsed; {@code null} becomes "". */
    public static String normalise(String value) {
        if (value == null) {
            return "";
        }
        return WHITESPACE.matcher(value.strip()).replaceAll(" ").toLowerCase(Locale.ROOT);
    }

    /** The calendar categories one creator category stands for; empty when it maps to none. */
    public static List<String> calendarCategoriesFor(String category) {
        String key = normalise(category);
        if (key.isEmpty()) {
            return List.of();
        }
        String calendar = CALENDAR_BY_KEY.get(key);
        if (calendar != null) {
            return List.of(calendar);
        }
        return TABLE_BY_KEY.getOrDefault(key, List.of());
    }

    /**
     * Every normalised name a topic's category may carry to match these creator categories: each
     * raw category itself plus every calendar category it maps to.
     */
    public static Set<String> matchKeys(List<String> creatorCategories) {
        Set<String> keys = new LinkedHashSet<>();
        if (creatorCategories == null) {
            return keys;
        }
        for (String category : creatorCategories) {
            String raw = normalise(category);
            if (raw.isEmpty()) {
                continue;
            }
            keys.add(raw);
            for (String calendar : calendarCategoriesFor(category)) {
                keys.add(normalise(calendar));
            }
        }
        return keys;
    }
}
