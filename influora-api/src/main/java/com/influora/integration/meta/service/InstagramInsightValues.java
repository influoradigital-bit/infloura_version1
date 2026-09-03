package com.influora.integration.meta.service;

import com.influora.integration.meta.dto.InstagramInsightsResponse;
import java.util.List;

/**
 * Reads one numeric value out of a {@code GET /{id}/insights} response by metric name.
 *
 * <p>Extracted from {@code DeliverableVerificationService}, which held the only copy as two private
 * statics. F-0479 needed the identical read to map insights onto {@code MediaMetric}, and a second
 * copy of a parser is how two call sites silently disagree about what "reach" means. One shared,
 * tested place — the same argument {@code gates/_code.sh} makes for its own distinction.
 *
 * <p><b>Absence is not zero.</b> Every accessor returns {@code null} — never {@code 0L} — when the
 * metric is missing, the response is null, the values array is empty, or the value will not parse.
 * Meta legitimately omits metrics it does not support for a given {@code media_type} (and 400s on
 * some combinations, which callers degrade to a null response), so "Meta did not report this" and
 * "Meta reported zero" are different facts and must stay distinguishable downstream. F-0478 is what
 * happens when they are not.
 */
public final class InstagramInsightValues {

    /** {@code views} — Meta's unified view count; superseded both {@code impressions} (deprecated
     *  2025-04-21) and {@code video_views} for media created after 2024-07-02. */
    public static final String VIEWS = "views";

    public static final String REACH = "reach";
    public static final String LIKES = "likes";
    public static final String COMMENTS = "comments";

    /** Meta spells this {@code saved}, not {@code saves}; the column it lands in is {@code saves}. */
    public static final String SAVED = "saved";

    public static final String SHARES = "shares";

    /** {@code likes + comments + saves + shares} as Meta computes it — matches the
     *  {@code media_metrics.engagement} column's own DDL comment. */
    public static final String TOTAL_INTERACTIONS = "total_interactions";

    private InstagramInsightValues() {}

    /**
     * @return the metric's first (media insights are single "lifetime" values) numeric value, or
     *     {@code null} if it is absent or unparseable — never a substituted zero.
     */
    public static Long metricValue(InstagramInsightsResponse insights, String metricName) {
        if (insights == null || insights.data() == null) {
            return null;
        }
        return insights.data().stream()
                .filter(m -> metricName.equals(m.name()))
                .findFirst()
                .map(InstagramInsightValues::firstValue)
                .orElse(null);
    }

    private static Long firstValue(InstagramInsightsResponse.InsightMetric metric) {
        List<InstagramInsightsResponse.InsightValue> values = metric.values();
        if (values == null || values.isEmpty() || values.get(0).value() == null) {
            return null;
        }
        Object raw = values.get(0).value();
        if (raw instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.valueOf(raw.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
