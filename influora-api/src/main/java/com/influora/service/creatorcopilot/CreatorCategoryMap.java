package com.influora.service.creatorcopilot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
 * <p>{@code ContentTopicService} runs both sides of a match through this one table, but not the
 * same way. A creator's categories become {@link #matchKeys}: each raw category plus EVERY calendar
 * category it maps to, so a "Parenting &amp; Family" creator also gets Food and Education topics.
 * A topic's category (split on commas by {@link #splitTopicCategories}) becomes {@link
 * #topicMatchKeys}: each raw part, plus its calendar category only when the table gives exactly
 * one ("Tech" is Technology, "Shopping" is Lifestyle). A part with several targets ("Parenting
 * &amp; Family") keeps only its raw name, so it reaches only creators who chose that onboarding
 * option instead of fanning out to every Food and Education creator. A topic reaches a creator
 * when the two key sets share a name; a word that maps to no calendar category only matches that
 * same word.
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
                    "Culture",
                    "Fashion",
                    "Lifestyle",
                    "Entertainment",
                    "Gaming",
                    "Music",
                    "Parenting");

    /** Creator category (as onboarding or the creator writes it) to calendar categories. */
    static final Map<String, List<String>> TABLE =
            Map.ofEntries(
                    // The onboarding verticals (CONTENT_VERTICALS in creator-onboarding.tsx).
                    Map.entry("Fashion & Lifestyle", List.of("Beauty", "Culture", "Fashion", "Lifestyle")),
                    Map.entry("Beauty & Skincare", List.of("Beauty")),
                    Map.entry("Fitness & Health", List.of("Fitness")),
                    Map.entry("Food & Cooking", List.of("Food")),
                    Map.entry("Tech & Gaming", List.of("Technology", "Gaming")),
                    Map.entry("Travel & Adventure", List.of("Travel")),
                    Map.entry("Education & Learning", List.of("Education")),
                    Map.entry("Finance & Business", List.of("Finance", "Local business")),
                    Map.entry("Entertainment & Comedy", List.of("Culture", "Entertainment")),
                    Map.entry("Parenting & Family", List.of("Education", "Food", "Parenting")),
                    Map.entry("Art & Photography", List.of("DIY or crafts", "Culture")),
                    Map.entry("Music & Dance", List.of("Culture", "Music", "Entertainment")),
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
                    Map.entry("diy", List.of("DIY or crafts")),
                    Map.entry("shopping", List.of("Lifestyle")),
                    Map.entry("comedy", List.of("Entertainment")),
                    Map.entry("games", List.of("Gaming")),
                    Map.entry("dance", List.of("Music")),
                    Map.entry("family", List.of("Parenting")));

    /**
     * The onboarding verticals, mirrored by hand from {@code CONTENT_VERTICALS} in {@code
     * src/pages/creator-onboarding.tsx}, in the same order (the first twelve {@link #TABLE} keys).
     * {@code influora-ai/tests/planner/test_categories.py} reads both files and fails when they
     * differ. {@link #calendarCategoriesNoVerticalReaches} uses it to find the calendar words no
     * onboarding option maps to.
     */
    public static final List<String> ONBOARDING_VERTICALS =
            List.of(
                    "Fashion & Lifestyle",
                    "Beauty & Skincare",
                    "Fitness & Health",
                    "Food & Cooking",
                    "Tech & Gaming",
                    "Travel & Adventure",
                    "Education & Learning",
                    "Finance & Business",
                    "Entertainment & Comedy",
                    "Parenting & Family",
                    "Art & Photography",
                    "Music & Dance");

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
     * A {@code content_topics.category} value split into its parts: split on ",", each part
     * trimmed, blank parts dropped ("Fashion, Culture" is two parts; "Food,," is one). The ONE place
     * a topic's category is split -- every reader of that column goes through here.
     */
    public static List<String> splitTopicCategories(String topicCategory) {
        List<String> parts = new ArrayList<>();
        if (topicCategory == null) {
            return parts;
        }
        for (String part : topicCategory.split(",")) {
            String trimmed = part.strip();
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }
        return parts;
    }

    /**
     * The CREATOR side of a match: every normalised name these creator categories stand for, each
     * raw category itself plus EVERY calendar category it maps to. A topic reaches the creator when
     * this set meets the topic's {@link #topicMatchKeys}.
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

    /**
     * The TOPIC side of a match: the keys of a topic's category parts (from {@link
     * #splitTopicCategories}). Each part always keeps its own normalised name; its calendar
     * category is added ONLY when {@link #calendarCategoriesFor} gives exactly one. So a calendar
     * word maps to itself ("Food") and a single-target alias resolves ("tech" is Technology,
     * "shopping" is Lifestyle, "comedy" is Entertainment), while a part whose table entry has
     * several targets -- the wide onboarding options "Parenting &amp; Family", "Music &amp; Dance",
     * "Fashion &amp; Lifestyle", "Finance &amp; Business", "Art &amp; Photography", "Tech &amp;
     * Gaming" -- keeps only its raw name and reaches only creators who chose that exact option.
     * Otherwise a "Parenting &amp; Family" topic would reach every Food and Education creator. The
     * creator side ({@link #matchKeys}) is not narrowed: it still expands to every target.
     */
    public static Set<String> topicMatchKeys(List<String> topicCategoryParts) {
        Set<String> keys = new LinkedHashSet<>();
        if (topicCategoryParts == null) {
            return keys;
        }
        for (String part : topicCategoryParts) {
            String raw = normalise(part);
            if (raw.isEmpty()) {
                continue;
            }
            keys.add(raw);
            List<String> calendar = calendarCategoriesFor(part);
            if (calendar.size() == 1) {
                keys.add(normalise(calendar.get(0)));
            }
        }
        return keys;
    }

    /**
     * The calendar categories no {@link #ONBOARDING_VERTICALS} entry maps to, in {@link
     * #CALENDAR_CATEGORIES} order (today only "Gardening"). A topic in one of them reaches only
     * creators who typed that word by hand.
     */
    public static List<String> calendarCategoriesNoVerticalReaches() {
        Set<String> reached = new HashSet<>();
        for (String vertical : ONBOARDING_VERTICALS) {
            reached.addAll(calendarCategoriesFor(vertical));
        }
        return CALENDAR_CATEGORIES.stream().filter(category -> !reached.contains(category)).toList();
    }
}
