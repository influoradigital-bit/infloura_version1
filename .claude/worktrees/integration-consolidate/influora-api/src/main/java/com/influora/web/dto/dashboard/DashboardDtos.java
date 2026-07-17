package com.influora.web.dto.dashboard;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Brand dashboard response records (BACKEND-API-SPEC §33.6). Powers the "Now" action list and the
 * Pipeline card in {@code src/components/brand/dashboard/dashboard-page.tsx}.
 */
public final class DashboardDtos {

    private DashboardDtos() {}

    /**
     * A single priority item on the dashboard. {@code type} is one of {@code deliverable_review |
     * counter_proposal | payment_release | sign_contract}; {@code priority} is {@code urgent | high
     * | medium}. {@code deadline} is always non-null — the client calls {@code new Date(deadline)}
     * on it directly.
     */
    public record ActionItem(
            String id,
            String type,
            String title,
            String subtitle,
            Instant deadline,
            String priority,
            BigDecimal amount,
            String link) {}

    /** One collaboration-funnel bucket and its live count (e.g. {@code {"Negotiating", 4}}). */
    public record PipelineStage(String stage, long count) {}
}
