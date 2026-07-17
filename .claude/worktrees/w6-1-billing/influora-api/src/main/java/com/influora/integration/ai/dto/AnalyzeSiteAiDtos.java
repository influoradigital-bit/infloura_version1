package com.influora.integration.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * Wire DTOs for influora-ai's {@code POST /analyze-site} (H-23). Field names/shape mirror {@code
 * app/routes/analyze_site.py}'s response body and {@code app/providers/gemini.py::ClassifyResult}
 * exactly — see those files for the authoritative contract.
 *
 * <p><b>Deliberately narrower than {@code
 * com.influora.web.dto.meera.MeeraDtos.AnalyzeSiteCallback}</b> (the {@code status}/{@code
 * brandAesthetic}/{@code toneProfile}/{@code competitorUrls} shape a {@code brand_profiles}
 * (V11) row expects): the real Python route only ever returns {@code source_url}, {@code
 * niche_tags}, {@code tone_dial} (a free-form object, not the DB's {@code
 * {formality,energy,emoji_ok,cultural_context}} shape), {@code brand_color} (a single hex
 * string), and {@code product_catalog} — no {@code competitor_urls} field exists on this route at
 * all. {@link com.influora.service.brand.AnalyzeSiteTriggerService} adapts this narrower shape
 * into an {@code AnalyzeSiteCallback} before handing it to {@code
 * BrandProfile#applyAnalysisResult}, rather than this DTO pretending to a contract Python doesn't
 * actually implement.
 */
public final class AnalyzeSiteAiDtos {

    private AnalyzeSiteAiDtos() {}

    /** Request body — {@code {workspace_id, url}}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AnalyzeSiteRequest(@JsonProperty("workspace_id") String workspaceId, String url) {}

    /**
     * Response envelope. On success: {@code {success: true, data: {...}}}. On a handled failure
     * (SSRF-blocked URL, empty page, classify failure) the route still returns HTTP 200 with
     * {@code success: false, error: {code, message}, degraded: "paste_a_link"} — never a raw 4xx
     * for these cases (see {@code analyze_site.py}).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AnalyzeSiteResponse(boolean success, Data data, ErrorDetail error, String degraded) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Data(
            @JsonProperty("source_url") String sourceUrl,
            @JsonProperty("niche_tags") List<String> nicheTags,
            @JsonProperty("tone_dial") Map<String, Object> toneDial,
            @JsonProperty("brand_color") String brandColor,
            @JsonProperty("product_catalog") List<Map<String, Object>> productCatalog) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ErrorDetail(String code, String message) {}

    /** Error envelope influora-ai returns on an actual 4xx/5xx (auth failure, malformed request) —
     * {@code {detail: {code, message}}}, the same FastAPI convention as every other route. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TransportErrorResponse(Detail detail) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Detail(String code, String message) {}
    }
}
