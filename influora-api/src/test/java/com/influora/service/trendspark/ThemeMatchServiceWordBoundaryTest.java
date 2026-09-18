package com.influora.service.trendspark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-0784 — {@link ThemeMatchService#themesForText(String)} matched taxonomy keywords by plain
 * case-insensitive substring containment, with no word-boundary anchoring. On the Trend-Spark
 * trend source (Indian entertainment news headlines, which are dense with personal names) that
 * fired constantly:
 *
 * <ul>
 *   <li>{@code "onam"} matched inside the personal name "Sonam" (e.g. "Sonam Kapoor"), tagging the
 *       headline festive/celebration/tradition/family — and, in the n8n tagger that shares this
 *       vocabulary, typing the trend SEASONAL with a 21-day peak window.
 *   <li>{@code "holi"} matched inside "holiday".
 *   <li>{@code "eid"} matched inside "Heidi".
 * </ul>
 *
 * These cases pin the boundary behaviour in both directions: the false positives must yield NO
 * themes, and the genuine keywords — single-word AND multi-word phrases from the taxonomy — must
 * still match. The fail-closed contract on null/blank input is pinned here too so the fix cannot
 * be "widen the guard".
 */
class ThemeMatchServiceWordBoundaryTest {

    private ThemeMatchService service;

    @BeforeEach
    void setUp() {
        service = new ThemeMatchService();
        // @PostConstruct, package-private: no Spring context needed — main resources are on the
        // test classpath, so the real trendspark/theme-taxonomy.json is what gets matched against.
        service.loadTaxonomy();
        assertFalse(
                service.knownThemes().isEmpty(),
                "taxonomy failed to load — the rest of this test would pass vacuously");
    }

    // ── false positives: keyword buried inside a longer, unrelated word ──────────────────────

    @Test
    @DisplayName("'onam' inside the name \"Sonam\" tags nothing")
    void sonamIsNotOnam() {
        assertEquals(Set.of(), service.themesForText("Sonam Kapoor spotted at the airport"));
    }

    @Test
    @DisplayName("'holi' inside \"holiday\" tags nothing")
    void holidayIsNotHoli() {
        assertEquals(Set.of(), service.themesForText("Book your holiday packages now"));
    }

    @Test
    @DisplayName("'eid' inside \"Heidi\" tags nothing")
    void heidiIsNotEid() {
        assertEquals(Set.of(), service.themesForText("Heidi Klum returns to television"));
    }

    // ── true positives: the same keywords as whole words still match ─────────────────────────

    @Test
    @DisplayName("'Onam' as its own word still tags festive/celebration/tradition/family")
    void onamAsAWordStillMatches() {
        assertEquals(
                Set.of("festive", "celebration", "tradition", "family"),
                service.themesForText("Onam celebrations begin in Kerala"));
    }

    @Test
    @DisplayName("'Holi' as its own word still tags festive/celebration/joy/tradition")
    void holiAsAWordStillMatches() {
        assertEquals(
                Set.of("festive", "celebration", "joy", "tradition"),
                service.themesForText("Holi colours light up the city"));
    }

    @Test
    @DisplayName("'Eid' as its own word still tags festive/family/tradition/celebration/devotion")
    void eidAsAWordStillMatches() {
        assertEquals(
                Set.of("festive", "family", "tradition", "celebration", "devotion"),
                service.themesForText("Eid Mubarak wishes flood timelines"));
    }

    @Test
    @DisplayName("punctuation, not whitespace, still counts as a boundary")
    void punctuationIsABoundary() {
        assertTrue(service.themesForText("Festive season: (Onam), then Pongal.").contains("festive"));
    }

    @Test
    @DisplayName("matching stays case-insensitive")
    void matchingStaysCaseInsensitive() {
        assertEquals(
                service.themesForText("Onam celebrations begin in Kerala"),
                service.themesForText("ONAM CELEBRATIONS BEGIN IN KERALA"));
    }

    // ── multi-word taxonomy keys: boundaries must anchor the PHRASE, not each token ──────────

    @Test
    @DisplayName("multi-word keyword 'durga puja' still matches across its internal space")
    void multiWordKeywordStillMatches() {
        assertEquals(
                Set.of("festive", "celebration", "tradition", "spirituality", "devotion"),
                service.themesForText("Durga Puja pandal hopping guide"));
    }

    @Test
    @DisplayName("multi-word keyword 'raksha bandhan' still matches")
    void multiWordKeywordRakshaBandhan() {
        assertEquals(
                Set.of("family", "togetherness", "tradition", "celebration"),
                service.themesForText("Raksha Bandhan gifting ideas"));
    }

    @Test
    @DisplayName("overlapping multi-word keys ('new year' ⊂ 'new year outfit') both still match")
    void overlappingMultiWordKeysBothMatch() {
        Set<String> themes = service.themesForText("New Year outfit inspiration");
        // 'new year' → celebration, joy, energy, innovation; 'new year outfit' → style,
        // celebration, confidence. Both keys are anchored and both fire, as before the fix.
        assertEquals(
                Set.of("celebration", "joy", "energy", "innovation", "style", "confidence"), themes);
    }

    // ── defence-in-depth: a keyword mapped outside the loaded taxonomy is never trusted ───────

    @Test
    @DisplayName("M5c: a keyword mapped to a theme outside the loaded taxonomy is filtered out, not"
            + " trusted")
    void keywordMappedToUnknownThemeIsFiltered() throws Exception {
        // T-GOLIVE-0918 repair round 2 [vikram · 2026-09-18] — regression test for mutation M5c
        // (replacing `knownThemes.contains(theme)` with `if (true)` in #themesForText survived
        // all 26 tests in this class + TrendPullJobTest). The SHIPPED taxonomy maps 0 keywords to
        // a theme outside its own `themes` array, so the real taxonomy can never falsify this
        // filter — it was defence-in-depth with no test able to defend it. Reflection injects a
        // synthetic taxonomy where one keyword's mapped theme is deliberately absent from
        // knownThemes, to prove the FILTER — not the taxonomy's own good luck — is what keeps it
        // out.
        java.lang.reflect.Method compile =
                ThemeMatchService.class.getDeclaredMethod("compileKeywordMatchers", java.util.Map.class);
        compile.setAccessible(true);
        Object compiledMatchers =
                compile.invoke(null, java.util.Map.of("zzzkeyword", java.util.List.of("not_a_real_theme")));

        java.lang.reflect.Field knownThemesField = ThemeMatchService.class.getDeclaredField("knownThemes");
        knownThemesField.setAccessible(true);
        knownThemesField.set(service, Set.of("festive")); // deliberately excludes "not_a_real_theme"

        java.lang.reflect.Field keywordMatchersField =
                ThemeMatchService.class.getDeclaredField("keywordMatchers");
        keywordMatchersField.setAccessible(true);
        keywordMatchersField.set(service, compiledMatchers);

        assertEquals(
                Set.of(),
                service.themesForText("this headline mentions zzzkeyword right here"),
                "a keyword mapped to a theme outside the loaded taxonomy must never be trusted");
    }

    // ── fail-closed contract on null/blank is unchanged ──────────────────────────────────────

    @Test
    @DisplayName("null/blank input still fails closed to an empty set")
    void nullAndBlankStillFailClosed() {
        assertEquals(Set.of(), service.themesForText(null));
        assertEquals(Set.of(), service.themesForText(""));
        assertEquals(Set.of(), service.themesForText("   \t\n "));
    }
}
