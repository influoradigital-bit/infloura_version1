package com.influora.domain.enums;

/** Provenance of a {@code trends} row's tags (V20260716120000). KEYWORD = the
 * deterministic n8n theme-tagger (Dev, T3) matched directly; AI_RECOVERED = the
 * LLM Recovery Tagger ({@code POST /internal/trendspark/tag}) rescued a trend the
 * keyword tagger dropped, onto the same closed vocab. Closed vocab; do not add
 * values without updating the n8n pipeline + recovery route. */
public enum TrendThemeSource {
    KEYWORD,
    AI_RECOVERED
}
