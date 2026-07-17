package com.influora.service.trendspark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influora.domain.entity.Trend;
import com.influora.domain.enums.TrendCampaignType;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    // exact taxonomy load instead of standing up a second file reader.
    private Map<String, List<String>> keywordToThemeMappings = Map.of();

    @PostConstruct
    void loadTaxonomy() {
        try (InputStream in = new ClassPathResource(TAXONOMY_PATH).getInputStream()) {
            TaxonomyFile taxonomy = objectMapper.readValue(in, TaxonomyFile.class);
            this.knownThemes =
                    taxonomy.themes() == null ? Set.of() : new HashSet<>(taxonomy.themes());
            this.keywordToThemeMappings =
                    taxonomy.keywordToThemeMappings() == null ? Map.of() : taxonomy.keywordToThemeMappings();
        } catch (IOException e) {
            // Fail-closed: an empty vocab makes every overlap score 0, which means the caller
            // stays silent (below threshold) rather than crash the app or nudge on garbage data.
            log.error("ThemeMatchService: failed to load {} — theme matching disabled", TAXONOMY_PATH, e);
            this.knownThemes = Set.of();
            this.keywordToThemeMappings = Map.of();
        }
    }

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
     * "matching" a theme if any of that theme's mapped keyword phrases appears as a substring
     * (case-insensitive). Null/blank captions and a load failure both yield an empty set
     * (fail-closed — never widens a match, only ever narrows it to "no signal").
     */
    public Set<String> themesForText(String freeText) {
        if (freeText == null || freeText.isBlank() || keywordToThemeMappings.isEmpty()) {
            return Set.of();
        }
        String lower = freeText.toLowerCase(Locale.ROOT);
        Set<String> matched = new HashSet<>();
        for (Map.Entry<String, List<String>> entry : keywordToThemeMappings.entrySet()) {
            String keyword = entry.getKey();
            if (keyword != null && !keyword.isBlank() && lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                List<String> themes = entry.getValue();
                if (themes != null) {
                    // Restrict to the controlled vocabulary loaded at startup — defensive against
                    // a malformed/edited taxonomy file mapping a keyword to a non-vocab theme.
                    for (String theme : themes) {
                        if (knownThemes.contains(theme)) {
                            matched.add(theme);
                        }
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
