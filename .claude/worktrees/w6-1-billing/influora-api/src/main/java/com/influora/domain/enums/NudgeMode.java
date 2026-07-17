package com.influora.domain.enums;

/** {@code nudge_log.mode} — decided by {@code ContentGapService} per the anti-spam gate
 * (wiki/architecture/trendspark-priya-schema-lock.md §3). {@code OWN_CONTENT} never mentions the
 * Snapsby marketplace. */
public enum NudgeMode {
    SNAPSBY,
    OWN_CONTENT
}
