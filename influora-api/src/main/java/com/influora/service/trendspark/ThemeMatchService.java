package com.influora.service.trendspark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.domain.entity.Trend;
import com.influora.domain.enums.TrendCampaignType;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * Loads {@code trendspark/theme-taxonomy.json} (Nisha, T5) from the classpath and computes the
 * theme-overlap score between a {@link Trend} and a brand's {@code theme_tags} — schema lock §2:
 * {@code score = count(overlap(trend.themes, brand.theme_tags))}. The controlled vocabulary
 * itself is not re-validated here at match time (n8n/Nisha's config own that); this service is
 * read-only against the taxonomy file, loaded once at startup.
 */
@Service
public class ThemeMatchService {

    private static final Logger log = LoggerFactory.getLogger(ThemeMatchService.class);
    private static final String TAXONOMY_PATH = "trendspark/theme-taxonomy.json";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private Set<String> knownThemes = Set.of();
    // T6: keyword phrase -> themes, same theme-taxonomy.json (Nisha, T5). Loaded here (not
    // re-parsed elsewhere) so BrandOwnContentService's caption-keyword theme signal reuses this
    // exact taxonomy load instead of standing up a second file reader. Held as pre-compiled
    // word-boundary matchers (F-0784, see #themesForText) rather than raw strings, so the
    // per-keyword Pattern is built once at startup, not once per caption.
    private List<KeywordMatcher> keywordMatchers = List.of();

    @PostConstruct
    void loadTaxonomy() {
        try (InputStream in = new ClassPathResource(TAXONOMY_PATH).getInputStream()) {
            TaxonomyFile taxonomy = objectMapper.readValue(in, TaxonomyFile.class);
            this.knownThemes =
                    taxonomy.themes() == null ? Set.of() : new HashSet<>(taxonomy.themes());
            this.keywordMatchers = compileKeywordMatchers(taxonomy.keywordToThemeMappings());
        } catch (IOException e) {
            // Fail-closed: an empty vocab makes every overlap score 0, which means the caller
            // stays silent (below threshold) rather than crash the app or nudge on garbage data.
            log.error("ThemeMatchService: failed to load {} — theme matching disabled", TAXONOMY_PATH, e);
            this.knownThemes = Set.of();
            this.keywordMatchers = List.of();
        }
    }

    /**
     * F-0784: anchor each taxonomy keyword at word boundaries instead of matching it as a bare
     * substring anywhere in the text.
     *
     * <p>Why lookarounds over {@code \b} or a tokenizer: {@code keyword_to_theme_mappings} holds
     * multi-word phrases ({@code "durga puja"}, {@code "raksha bandhan"}, {@code "wedding guest
     * dress"}), so splitting the caption into single-word tokens and comparing token-by-token
     * cannot express those keys at all. A regex over the whole text can. {@code \b} would work for
     * today's purely alphabetic vocabulary, but its meaning FLIPS if a key ever begins or ends with
     * a non-word character (a hyphen, an {@code &}) — {@code \bself-care\b} still happens to be
     * right, {@code \b&more\b} is silently inverted. Explicit {@code (?<![\p{L}\p{N}])} /
     * {@code (?![\p{L}\p{N}])} lookarounds say what is actually meant — "not glued to an adjacent
     * letter or digit" — for any key shape, and stay correct for non-ASCII text. Only the two edges
     * of the phrase are anchored; interior spaces and punctuation are untouched.
     *
     * <p>Keys are lowercased and {@link Pattern#quote quoted} (so a key is always literal text,
     * never an accidental regex) and matched against the lowercased caption — that is exactly the
     * previous case-insensitive semantics, with containment replaced by boundary-anchored search.
     */
    private static List<KeywordMatcher> compileKeywordMatchers(Map<String, List<String>> mappings) {
        if (mappings == null || mappings.isEmpty()) {
            return List.of();
        }
        List<KeywordMatcher> compiled = new ArrayList<>(mappings.size());
        for (Map.Entry<String, List<String>> entry : mappings.entrySet()) {
            String keyword = entry.getKey();
            if (keyword == null || keyword.isBlank() || entry.getValue() == null) {
                continue;
            }
            String lowered = keyword.toLowerCase(Locale.ROOT);
            Pattern pattern =
                    Pattern.compile(
                            "(?<![\\p{L}\\p{N}])" + Pattern.quote(lowered) + "(?![\\p{L}\\p{N}])");
            compiled.add(new KeywordMatcher(pattern, List.copyOf(entry.getValue())));
        }
        return List.copyOf(compiled);
    }

    /** A taxonomy keyword pre-compiled as a word-boundary-anchored pattern, plus its themes. */
    private record KeywordMatcher(Pattern pattern, List<String> themes) {}

    /** Overlap count between the trend's themes and the brand's theme_tags. Both inputs are
     * raw JSON arrays (as stored); malformed/null JSON parses to an empty set (fail-closed). */
    public int score(Trend trend, String brandThemeTagsJson) {
        Set<String> trendThemes = parseThemes(trend == null ? null : trend.getThemesJson());
        Set<String> brandThemes = parseThemes(brandThemeTagsJson);
        if (trendThemes.isEmpty() || brandThemes.isEmpty()) {
            return 0;
        }
        int overlap = 0;
        for (String theme : trendThemes) {
            if (brandThemes.contains(theme)) {
                overlap++;
            }
        }
        return overlap;
    }

    public TrendCampaignType campaignType(Trend trend) {
        return trend.getCampaignType();
    }

    /** Set of themes from the controlled vocabulary loaded at startup (may be empty on load
     * failure — fail-closed, never throws). */
    public Set<String> knownThemes() {
        return knownThemes;
    }

    /** Public JSON-array-of-themes parser (T6) — {@link BrandOwnContentService} needs this for
     * {@code trend.themesJson} the same way {@link #score} does internally; exposed rather than
     * duplicated. Malformed/null JSON parses to an empty set (fail-closed). */
    public Set<String> parseThemeJson(String json) {
        return parseThemes(json);
    }

    /**
     * T6: coarse theme signal for a free-text caption — keyword-containment match against {@code
     * theme-taxonomy.json}'s {@code keyword_to_theme_mappings} (same controlled vocabulary n8n
     * uses to tag trends, per {@link #TAXONOMY_PATH}). Not NLP — a caption is scored as
     * "matching" a theme if any of that theme's mapped keyword phrases appears in the caption as a
     * WHOLE WORD / whole phrase (case-insensitive). Null/blank captions and a load failure both
     * yield an empty set (fail-closed — never widens a match, only ever narrows it to "no signal").
     *
     * <p>F-0784: this used to be bare substring containment ({@code lower.contains(keyword)}), so a
     * keyword matched inside an unrelated longer word — {@code "onam"} inside the personal name
     * "Sonam" (Sonam Kapoor), {@code "holi"} inside "holiday", {@code "eid"} inside "Heidi". On the
     * Trend-Spark source feed (Indian entertainment headlines, dense with personal names) that
     * fired constantly, tagging names as festivals. Matching is now boundary-anchored — see
     * {@link #compileKeywordMatchers} for why lookarounds rather than {@code \b} or a tokenizer.
     * The same bug and the same fix exist in the n8n copies of this vocabulary
     * ({@code trendspark/n8n/theme-tagger.js} and the inline "Theme Tagger + row builder" Code node
     * in {@code trendspark/n8n/trend-pull-workflow.json}); keep all three in step — note that
     * {@code trendspark/n8n/tagger-sync.check.js} compares only the two JS copies' VOCAB to the
     * JSON configs, so it can never catch Java drifting on matching BEHAVIOUR.
     */
    public Set<String> themesForText(String freeText) {
        if (freeText == null || freeText.isBlank() || keywordMatchers.isEmpty()) {
            return Set.of();
        }
        String lower = freeText.toLowerCase(Locale.ROOT);
        Set<String> matched = new HashSet<>();
        for (KeywordMatcher matcher : keywordMatchers) {
            if (matcher.pattern().matcher(lower).find()) {
                // Restrict to the controlled vocabulary loaded at startup — defensive against
                // a malformed/edited taxonomy file mapping a keyword to a non-vocab theme.
                for (String theme : matcher.themes()) {
                    if (knownThemes.contains(theme)) {
                        matched.add(theme);
                    }
                }
            }
        }
        return matched;
    }

    private Set<String> parseThemes(String json) {
        if (json == null || json.isBlank()) {
            return Set.of();
        }
        try {
            List<String> themes = objectMapper.readValue(json, LIST_OF_STRING);
            return themes == null ? Set.of() : new HashSet<>(themes);
        } catch (IOException e) {
            return Set.of();
        }
    }

    private static final com.fasterxml.jackson.core.type.TypeReference<List<String>> LIST_OF_STRING =
            new com.fasterxml.jackson.core.type.TypeReference<>() {};

    /** Shape of {@code theme-taxonomy.json} consumed here: the top-level {@code themes} array
     * (controlled vocabulary) and {@code keyword_to_theme_mappings} (T6, caption keyword signal).
     * {@code niche_to_theme_mappings} stays n8n's (Dev, T3) concern for tagging trends before they
     * land in the {@code trends} table. {@code ignoreUnknown=true} because this record only reads
     * a subset of the file's top-level keys (also {@code description}/{@code notes}). */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private record TaxonomyFile(
            String version,
            List<String> themes,
            @com.fasterxml.jackson.annotation.JsonProperty("keyword_to_theme_mappings")
                    Map<String, List<String>> keywordToThemeMappings) {}
}
