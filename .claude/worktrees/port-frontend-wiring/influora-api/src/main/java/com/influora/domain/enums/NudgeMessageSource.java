package com.influora.domain.enums;

/** {@code nudge_log.message_source} — whether the shown copy came from the influora-ai phrasing
 * call (AI) or the deterministic templated fallback (FALLBACK, used on any AI call/parse/
 * validation failure — §4 of the schema lock). */
public enum NudgeMessageSource {
    AI,
    FALLBACK
}
