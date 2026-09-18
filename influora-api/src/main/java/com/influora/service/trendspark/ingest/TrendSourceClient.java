package com.influora.service.trendspark.ingest;

import java.util.List;

/**
 * T-GOLIVE-0918 [vikram · 2026-09-18] — one external trend data source for {@code TrendPullJob}.
 * Source: .proof-os/tasks/T-COPILOT-ON-0910/job-design.md step 4, porting
 * trendspark/n8n/trend-pull-workflow.json's TMDb/NewsAPI/YouTube fan-out nodes.
 *
 * <p>Contract, enforced by every implementation: {@link #fetch()} NEVER throws — it returns
 * {@link List#of()} on any transport, HTTP, or parsing failure. This is the Java equivalent of
 * the n8n workflow's per-node {@code onError: continueRegularOutput}, and is what lets one dead
 * source never sink the whole run (F-0781).
 */
public interface TrendSourceClient {

    /** Stable lowercase id stamped into {@code trends.source}, e.g. {@code "tmdb"}. Must match
     * exactly across runs — it is persisted as JSON and read back by downstream consumers. */
    String sourceId();

    /** False when this source's API key is blank — the job counts this as "skipped (no key)",
     * a distinct outcome from "failed", and never issues a request in that case. */
    boolean isConfigured();

    /** Never throws. Returns {@link List#of()} when not {@link #isConfigured()}, or on any
     * failure reaching or parsing the source. */
    List<RawTrend> fetch();

    /** One trend headline as pulled from a source, before word-filter/classifier screening or
     * theme tagging. {@code category} is carried for parity with the n8n normalizer's
     * {@code {text, source, category}} shape but is unused by every current source (each hard-codes
     * an empty category, same as the workflow it replaces). */
    record RawTrend(String text, String source, String category) {}
}
